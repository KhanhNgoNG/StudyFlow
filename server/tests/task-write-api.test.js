import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';

const createBody = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const taskId = createBody.id;
const otherId = '8ba5f9d4-4264-4ed9-8b2b-778ffb052257';
const fields = ['id', 'title', 'description', 'subjectCode', 'dueDate', 'priority',
  'completed', 'isDeleted', 'version', 'serverUpdatedAt'];
const key = (number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;
const detailPath = (id = taskId) => `/api/v1/tasks/${id}`;

function setup(t) {
  const db = openDatabase(':memory:');
  migrateDatabase(db);
  t.after(() => { if (db.open) db.close(); });
  return db;
}

function snapshot(db) {
  return {
    tasks: db.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: db.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
  };
}

function receiptCount(db) {
  return db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count;
}

async function serve(t, db, { loseFirstPostResponse = false } = {}) {
  const logs = [];
  const app = createApp({ database: db, logger: { error: (...args) => logs.push(args) } });
  let lose = loseFirstPostResponse;
  const server = createServer((req, res) => {
    if (lose && req.method === 'POST' && req.url === '/api/v1/tasks') {
      const end = res.end;
      res.end = function (...args) {
        if (lose && this.statusCode === 201) {
          lose = false;
          // Test-only transport failure: the real application has completed its
          // service/transaction before attempting to send the success body.
          this.destroy();
          return this;
        }
        return end.apply(this, args);
      };
    }
    app(req, res);
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections();
  }));
  let nextKey = 100;
  async function send(method, path, options = {}) {
    const { body, rawBody, headers = {}, json = true } = options;
    const hasBody = Object.hasOwn(options, 'body') || Object.hasOwn(options, 'rawBody');
    const mutationKey = Object.hasOwn(options, 'key') ? options.key : key(nextKey++);
    return fetch(`http://127.0.0.1:${server.address().port}${path}`, {
      method,
      headers: {
        ...(hasBody && json ? { 'Content-Type': 'application/json' } : {}),
        ...(['POST', 'PUT', 'DELETE'].includes(method) && mutationKey !== null
          ? { 'Idempotency-Key': mutationKey } : {}),
        ...headers,
      },
      ...(hasBody ? { body: Object.hasOwn(options, 'rawBody') ? rawBody : JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(5000),
    });
  }
  return { send, logs };
}

async function expectJson(responsePromise, status) {
  const response = await responsePromise;
  assert.equal(response.status, status);
  assert.match(response.headers.get('content-type'), /^application\/json; charset=utf-8$/);
  return response.json();
}

function assertTask(task) {
  assert.deepEqual(Object.keys(task).sort(), [...fields].sort());
  assert.equal(typeof task.completed, 'boolean');
  assert.equal(typeof task.isDeleted, 'boolean');
  assert.ok(Number.isSafeInteger(task.version));
  assert.ok(task.dueDate === null || /^\d{4}-\d{2}-\d{2}$/.test(task.dueDate));
  assert.match(task.serverUpdatedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/);
  assert.ok(Number.isFinite(Date.parse(task.serverUpdatedAt)));
}

async function create(send, overrides = {}, mutationKey) {
  return expectJson(send('POST', '/api/v1/tasks', {
    body: { ...createBody, ...overrides }, ...(mutationKey ? { key: mutationKey } : {}),
  }), 201);
}

function updateBody(task, overrides = {}) {
  const { version, serverUpdatedAt, ...business } = task;
  return { ...business, baseVersion: version, ...overrides };
}

test('POST thật giữ UUID/tiếng Việt/null, trim title, server quản lý version/time và trả đủ schema', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const result = await create(send, {
    title: '  Học tiếng Việt 😀  ', dueDate: null,
    version: 900, serverUpdatedAt: '1900-01-01T00:00:00Z', localRevision: 7, pending: true,
  });
  assert.deepEqual(Object.keys(result), ['task']);
  assertTask(result.task);
  assert.equal(result.task.id, taskId);
  assert.equal(result.task.title, 'Học tiếng Việt 😀');
  assert.equal(result.task.dueDate, null);
  assert.equal(result.task.version, 1);
  assert.notEqual(result.task.serverUpdatedAt, '1900-01-01T00:00:00Z');
  assert.equal(receiptCount(db), 1);
  assert.deepEqual(await expectJson(send('GET', detailPath()), 200), result);
});

test('POST ID sống hoặc tombstone đã tồn tại: 409 currentTask đầy đủ, không ghi task/receipt mới', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  let current = await create(send);
  for (const deleted of [false, true]) {
    if (deleted) current = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 200);
    const before = snapshot(db);
    const error = await expectJson(send('POST', '/api/v1/tasks', { body: createBody }), 409);
    assert.deepEqual(error, {
      code: 'VERSION_CONFLICT', message: 'Task changed since baseVersion', taskId,
      currentTask: current.task,
    });
    assertTask(error.currentTask);
    assert.deepEqual(snapshot(db), before);
  }
});

test('PUT thay toàn bộ nghiệp vụ, lưu false/chuỗi rỗng/null và tăng đúng một version', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const first = await create(send, { completed: true, description: 'Nội dung cũ', dueDate: '2028-02-29', priority: 'HIGH' });
  const replacement = updateBody(first.task, {
    title: 'Thay toàn bộ', description: '', subjectCode: 'OTHER', dueDate: null,
    priority: 'LOW', completed: false, version: 999, serverUpdatedAt: '1900-01-01T00:00:00Z',
  });
  const result = await expectJson(send('PUT', detailPath(), { body: replacement }), 200);
  assertTask(result.task);
  assert.deepEqual(result.task, {
    id: taskId, title: replacement.title, description: '', subjectCode: 'OTHER', dueDate: null,
    priority: 'LOW', completed: false, isDeleted: false, version: 2, serverUpdatedAt: result.task.serverUpdatedAt,
  });
  assert.notEqual(result.task.serverUpdatedAt, replacement.serverUpdatedAt);
  const row = db.prepare('SELECT description, due_date, completed, version FROM tasks').get();
  assert.deepEqual(row, { description: '', due_date: null, completed: 0, version: 2 });
  assert.deepEqual(await expectJson(send('GET', detailPath()), 200), result);
});

test('PUT body ID khác URL bị 422; ID thiếu là 404, không upsert', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const first = await create(send);
  const before = snapshot(db);
  const mismatched = await expectJson(send('PUT', detailPath(), { body: updateBody(first.task, { id: otherId }) }), 422);
  assert.equal(mismatched.code, 'VALIDATION_ERROR');
  assert.ok(mismatched.fieldErrors.id);
  const missing = await expectJson(send('PUT', detailPath(otherId), { body: updateBody(first.task, { id: otherId }) }), 404);
  assert.deepEqual(missing, { code: 'TASK_NOT_FOUND', message: 'Task not found', taskId: otherId });
  assert.deepEqual(snapshot(db), before);
});

test('PUT version cũ vẫn conflict dù nội dung đã giống server, currentTask không bị thay đổi', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const first = await create(send);
  const second = await expectJson(send('PUT', detailPath(), { body: updateBody(first.task, { title: 'Phiên bản hai' }) }), 200);
  const before = snapshot(db);
  const stale = await expectJson(send('PUT', detailPath(), { body: updateBody(second.task, { baseVersion: 1 }) }), 409);
  assert.deepEqual(stale, {
    code: 'VERSION_CONFLICT', message: 'Task changed since baseVersion', taskId, currentTask: second.task,
  });
  assert.deepEqual(snapshot(db), before);
});

test('DELETE trả tombstone 200, giữ nghiệp vụ, GET đọc được và list false loại tombstone', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const first = await create(send, { completed: true, dueDate: '2028-02-29' });
  const result = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 200);
  assertTask(result.task);
  assert.deepEqual(result.task, { ...first.task, isDeleted: true, version: 2, serverUpdatedAt: result.task.serverUpdatedAt });
  assert.deepEqual(await expectJson(send('GET', detailPath()), 200), result);
  assert.deepEqual(await expectJson(send('GET', '/api/v1/tasks?includeDeleted=true'), 200), { tasks: [result.task] });
  assert.deepEqual(await expectJson(send('GET', '/api/v1/tasks?includeDeleted=false'), 200), { tasks: [] });
  assert.equal(db.prepare('SELECT count(*) AS count FROM tasks').get().count, 1);
});

test('DELETE ID thiếu/stale version/ID sai/query lỗi không ghi task hoặc receipt', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const first = await create(send);
  const before = snapshot(db);
  const missing = await expectJson(send('DELETE', `${detailPath(otherId)}?baseVersion=1`), 404);
  assert.equal(missing.code, 'TASK_NOT_FOUND');
  assert.equal(missing.taskId, otherId);
  const conflict = await expectJson(send('DELETE', `${detailPath()}?baseVersion=2`), 409);
  assert.deepEqual(conflict.currentTask, first.task);
  assert.equal(conflict.code, 'VERSION_CONFLICT');
  for (const query of ['', '?baseVersion=1abc', '?baseVersion=0', '?baseVersion=1&baseVersion=1']) {
    const error = await expectJson(send('DELETE', `${detailPath()}${query}`), 422);
    assert.equal(error.code, 'VALIDATION_ERROR');
  }
  assert.equal((await expectJson(send('DELETE', '/api/v1/tasks/not-a-uuid?baseVersion=1'), 422)).code, 'VALIDATION_ERROR');
  assert.deepEqual(snapshot(db), before);
});

test('PUT có version khớp phục hồi tombstone, tăng version và giữ GET không tự phục hồi', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  await create(send);
  const deleted = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 200);
  assert.deepEqual(await expectJson(send('GET', detailPath()), 200), deleted);
  const restored = await expectJson(send('PUT', detailPath(), {
    body: updateBody(deleted.task, { title: 'Người dùng giữ công việc', isDeleted: false }),
  }), 200);
  assert.equal(restored.task.isDeleted, false);
  assert.equal(restored.task.version, 3);
  assert.deepEqual(await expectJson(send('GET', detailPath()), 200), restored);
});

test('DELETE key mới trên tombstone là no-op nhưng có receipt; retry sau phục hồi trả snapshot cũ', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  await create(send);
  const deleted = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 200);
  const taskBefore = db.prepare('SELECT * FROM tasks').get();
  const receiptsBefore = receiptCount(db);
  const changesBefore = db.prepare('SELECT total_changes() AS count').get().count;
  const noopKey = key(500);
  const path = `${detailPath()}?baseVersion=2`;
  const noop = await expectJson(send('DELETE', path, { key: noopKey }), 200);
  assert.deepEqual(noop, deleted);
  assert.deepEqual(db.prepare('SELECT * FROM tasks').get(), taskBefore);
  assert.equal(receiptCount(db), receiptsBefore + 1);
  assert.equal(db.prepare('SELECT total_changes() AS count').get().count, changesBefore + 1);
  const afterNoop = snapshot(db);
  assert.deepEqual(await expectJson(send('DELETE', path, { key: noopKey }), 200), deleted);
  assert.deepEqual(snapshot(db), afterNoop);
  const stale = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 409);
  assert.deepEqual(stale.currentTask, deleted.task);
  assert.deepEqual(snapshot(db), afterNoop);

  const restored = await expectJson(send('PUT', detailPath(), {
    body: updateBody(deleted.task, { title: 'Đã phục hồi', isDeleted: false }),
  }), 200);
  const afterRestore = snapshot(db);
  assert.deepEqual(await expectJson(send('DELETE', path, { key: noopKey }), 200), deleted);
  assert.deepEqual(snapshot(db), afterRestore);
  assert.deepEqual(await expectJson(send('GET', detailPath()), 200), restored);
});

test('POST/PUT/DELETE replay status/body cũ sau các thay đổi tiếp theo, không tăng version/time', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const postKey = key(601), putKey = key(602), deleteKey = key(603);
  const first = await create(send, {}, postKey);
  const update = updateBody(first.task, { title: 'Cập nhật một lần' });
  const second = await expectJson(send('PUT', detailPath(), { key: putKey, body: update }), 200);
  const deletePath = `${detailPath()}?baseVersion=2`;
  const third = await expectJson(send('DELETE', deletePath, { key: deleteKey }), 200);
  const restored = await expectJson(send('PUT', detailPath(), { body: updateBody(third.task, { isDeleted: false }) }), 200);
  const beforeRetry = snapshot(db);
  assert.deepEqual(await expectJson(send('POST', '/api/v1/tasks', { key: postKey, body: createBody }), 201), first);
  assert.deepEqual(await expectJson(send('PUT', detailPath(), { key: putKey, body: update }), 200), second);
  assert.deepEqual(await expectJson(send('DELETE', deletePath, { key: deleteKey }), 200), third);
  assert.deepEqual(snapshot(db), beforeRetry);
  assert.equal(restored.task.version, 4);
});

test('cùng mutation ID khác method/path/query/payload bị 409 trước validation/version', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const originalKey = key(700);
  const first = await create(send, {}, originalKey);
  const before = snapshot(db);
  for (const [method, path, body] of [
    ['POST', '/api/v1/tasks', { ...createBody, title: '' }],
    ['POST', '/api/v1/tasks?unused=1', createBody],
    ['PUT', detailPath(), updateBody(first.task)],
    ['PUT', detailPath(otherId), updateBody(first.task, { id: otherId })],
    ['DELETE', `${detailPath()}?baseVersion=1`, undefined],
  ]) {
    const error = await expectJson(send(method, path, { key: originalKey, ...(body === undefined ? {} : { body }) }), 409);
    assert.equal(error.code, 'MUTATION_ID_REUSED');
    assert.deepEqual(snapshot(db), before);
  }
  const deleteKey = key(701);
  await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`, { key: deleteKey }), 200);
  const after = snapshot(db);
  const changedQuery = await expectJson(send('DELETE', `${detailPath()}?baseVersion=2`, { key: deleteKey }), 409);
  assert.equal(changedQuery.code, 'MUTATION_ID_REUSED');
  assert.deepEqual(snapshot(db), after);
});

test('receipt INSERT thất bại rollback cả POST, PUT và DELETE; client chỉ nhận 500 kín', async (t) => {
  const db = setup(t);
  const { send, logs } = await serve(t, db);
  const first = await create(send);
  db.exec(`CREATE TRIGGER reject_write_receipt BEFORE INSERT ON mutation_receipts
    BEGIN SELECT RAISE(ABORT, 'SELECT secret /private/internal.db'); END`);
  const before = snapshot(db);
  for (const [method, path, payload] of [
    ['POST', '/api/v1/tasks', { ...createBody, id: otherId }],
    ['PUT', detailPath(), updateBody(first.task, { title: 'Phải rollback' })],
    ['DELETE', `${detailPath()}?baseVersion=1`, undefined],
  ]) {
    const error = await expectJson(send(method, path, payload === undefined ? {} : { body: payload }), 500);
    assert.deepEqual(error, { code: 'INTERNAL_ERROR', message: 'Internal server error' });
    assert.deepEqual(snapshot(db), before);
  }
  assert.equal(logs.length, 3);
  assert.doesNotMatch(JSON.stringify(logs), /SELECT secret|internal.db/);
});

test('COMMIT thất bại do deferred FK không gửi success và rollback task/receipt', async (t) => {
  const db = setup(t);
  db.exec(`CREATE TABLE commit_guard (task_id TEXT REFERENCES tasks(id) DEFERRABLE INITIALLY DEFERRED);
    CREATE TRIGGER reject_commit AFTER INSERT ON mutation_receipts BEGIN
      INSERT INTO commit_guard (task_id) VALUES ('8ba5f9d4-4264-4ed9-8b2b-778ffb052257'); END;`);
  const { send } = await serve(t, db);
  const error = await expectJson(send('POST', '/api/v1/tasks', { body: createBody }), 500);
  assert.deepEqual(error, { code: 'INTERNAL_ERROR', message: 'Internal server error' });
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  assert.equal(db.prepare('SELECT count(*) AS count FROM commit_guard').get().count, 0);
});

test('version MAX-1 tăng tới MAX; matched MAX bị 500 không ghi, stale vẫn 409 trước overflow', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  const first = await create(send);
  db.prepare('UPDATE tasks SET version = ?').run(Number.MAX_SAFE_INTEGER - 1);
  const last = await expectJson(send('PUT', detailPath(), {
    body: updateBody(first.task, { baseVersion: Number.MAX_SAFE_INTEGER - 1, title: 'Version cuối an toàn' }),
  }), 200);
  assert.equal(last.task.version, Number.MAX_SAFE_INTEGER);
  const before = snapshot(db);
  const stale = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 409);
  assert.deepEqual(stale.currentTask, last.task);
  for (const [method, path, payload] of [
    ['PUT', detailPath(), updateBody(last.task)],
    ['DELETE', `${detailPath()}?baseVersion=${Number.MAX_SAFE_INTEGER}`, undefined],
  ]) {
    const result = await expectJson(send(method, path, payload === undefined ? {} : { body: payload }), 500);
    assert.deepEqual(result, { code: 'INTERNAL_ERROR', message: 'Internal server error' });
    assert.deepEqual(snapshot(db), before);
  }
});

test('DELETE tombstone ở MAX với version khớp vẫn no-op thành công và có receipt', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  await create(send);
  const deleted = await expectJson(send('DELETE', `${detailPath()}?baseVersion=1`), 200);
  db.prepare('UPDATE tasks SET version = ?').run(Number.MAX_SAFE_INTEGER);
  const taskBefore = db.prepare('SELECT * FROM tasks').get();
  const receiptBefore = receiptCount(db);
  const result = await expectJson(send('DELETE', `${detailPath()}?baseVersion=${Number.MAX_SAFE_INTEGER}`), 200);
  assert.deepEqual(result.task, { ...deleted.task, version: Number.MAX_SAFE_INTEGER });
  assert.deepEqual(db.prepare('SELECT * FROM tasks').get(), taskBefore);
  assert.equal(receiptCount(db), receiptBefore + 1);
});

test('minimum HTTP Content-Type và Idempotency-Key sai không bị nhận thành mutation thành công', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  for (const method of ['POST', 'PUT']) {
    const path = method === 'POST' ? '/api/v1/tasks' : detailPath();
    const payload = method === 'POST' ? createBody : { ...createBody, baseVersion: 1 };
    const missing = await expectJson(send(method, path, { rawBody: Buffer.from(JSON.stringify(payload)), json: false }), 415);
    assert.equal(missing.code, 'UNSUPPORTED_MEDIA_TYPE');
    const wrong = await expectJson(send(method, path, { body: payload, headers: { 'Content-Type': 'text/plain' } }), 415);
    assert.equal(wrong.code, 'UNSUPPORTED_MEDIA_TYPE');
    const badKey = await expectJson(send(method, path, { body: payload, key: 'bad-key' }), 422);
    assert.ok(badKey.fieldErrors['Idempotency-Key']);
  }
  const missingKey = await expectJson(send('POST', '/api/v1/tasks', { body: createBody, key: null }), 422);
  assert.ok(missingKey.fieldErrors['Idempotency-Key']);
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
});

test('DELETE JSON Content-Type với 0 byte là không body; JSON {} hoặc null thật không bị bỏ khỏi fingerprint', async (t) => {
  const db = setup(t);
  const { send } = await serve(t, db);
  await create(send);
  const deleteKey = key(900);
  const path = `${detailPath()}?baseVersion=1`;
  const deleted = await expectJson(send('DELETE', path, {
    key: deleteKey, headers: { 'Content-Type': 'application/json', 'Content-Length': '0' },
  }), 200);
  const before = snapshot(db);
  assert.deepEqual(await expectJson(send('DELETE', path, { key: deleteKey }), 200), deleted);
  assert.deepEqual(snapshot(db), before);
  for (const body of [{}, null]) {
    const reused = await expectJson(send('DELETE', path, { key: deleteKey, body }), 409);
    assert.equal(reused.code, 'MUTATION_ID_REUSED');
    const invalid = await expectJson(send('DELETE', `${detailPath()}?baseVersion=2`, { body }), 422);
    assert.ok(invalid.fieldErrors.body);
  }
  const text = await expectJson(send('DELETE', `${detailPath()}?baseVersion=2`, {
    rawBody: 'not-json', json: false, headers: { 'Content-Type': 'text/plain' },
  }), 415);
  assert.equal(text.code, 'UNSUPPORTED_MEDIA_TYPE');
  assert.deepEqual(snapshot(db), before);
});

test('mất response sau POST commit trên app chính: retry đúng key/request nhận receipt, không tạo lần hai', async (t) => {
  const db = setup(t);
  const { send, logs } = await serve(t, db, { loseFirstPostResponse: true });
  const mutationKey = key(950);
  await assert.rejects(send('POST', '/api/v1/tasks', { key: mutationKey, body: createBody }));
  assert.equal(db.prepare('SELECT count(*) AS count FROM tasks').get().count, 1);
  assert.equal(receiptCount(db), 1);
  const saved = db.prepare('SELECT response_status, response_body FROM mutation_receipts').get();
  const before = snapshot(db);
  const replay = await expectJson(send('POST', '/api/v1/tasks', { key: mutationKey, body: createBody }), 201);
  assert.equal(saved.response_status, 201);
  assert.deepEqual(replay, JSON.parse(saved.response_body));
  assert.deepEqual(snapshot(db), before);
  assert.equal(replay.task.version, 1);
  assert.equal(logs.length, 0);
});

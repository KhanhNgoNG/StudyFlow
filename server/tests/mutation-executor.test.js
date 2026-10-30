import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { Worker } from 'node:worker_threads';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { ApiError, taskNotFoundError, validationError, versionConflictError } from '../src/http/api-errors.js';
import { taskResponse } from '../src/http/task-responses.js';
import { createMutationExecutor } from '../src/mutations/mutation-executor.js';

const createBody = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const mutationId = '7fe7b179-6677-43ec-99e2-c0c035a184a8';
const updateId = '345ccf57-5bf5-43d7-9175-45375211bbaa';
const deleteId = '4099f1b3-084d-41ad-96f8-736621763d0d';
const otherTaskId = 'e5b951a7-797c-42e8-8369-3491c123b1a5';
const createdAt = '2026-10-29T03:00:00Z';
const updatedAt = '2026-10-29T03:05:00Z';

function setup(t, filename = ':memory:') {
  const db = openDatabase(filename);
  t.after(() => { if (db.open) db.close(); });
  migrateDatabase(db);
  return db;
}

function temporaryFile(t) {
  const directory = mkdtempSync(join(tmpdir(), 'studyflow-mutation-test-'));
  return { directory, filename: join(directory, 'test.db') };
}

function post(overrides = {}) {
  return {
    method: 'POST', path: '/api/v1/tasks', query: {},
    body: { ...createBody }, idempotencyKey: mutationId, ...overrides,
  };
}

function put(overrides = {}) {
  return {
    method: 'PUT', path: `/api/v1/tasks/${createBody.id}`, query: {},
    body: { ...createBody, baseVersion: 1, title: 'Sửa sau tạo' },
    idempotencyKey: updateId, ...overrides,
  };
}

function remove(overrides = {}) {
  return {
    method: 'DELETE', path: `/api/v1/tasks/${createBody.id}`,
    query: { baseVersion: '2' }, idempotencyKey: deleteId, ...overrides,
  };
}

function current(db) {
  return db.prepare('SELECT * FROM tasks WHERE id = ?').get(createBody.id);
}

function snapshot(db) {
  return {
    tasks: db.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: db.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
  };
}

// SQL here is test-only business behavior supplied to the shared executor.
// Production POST/PUT/DELETE handlers remain outside Step 7.
function createTask({ database, request }) {
  const task = request.task;
  database.prepare(`INSERT INTO tasks (id, title, description, subject_code, due_date,
    priority, completed, is_deleted, version, server_updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`).run(
    task.id, task.title, task.description, task.subjectCode, task.dueDate,
    task.priority, Number(task.completed), Number(task.isDeleted), createdAt,
  );
  return { status: 201, body: taskResponse(database.prepare('SELECT * FROM tasks WHERE id = ?').get(task.id)) };
}

function updateTask({ database, request }) {
  const task = request.task;
  const row = database.prepare('SELECT * FROM tasks WHERE id = ?').get(task.id);
  if (!row) throw taskNotFoundError(task.id);
  if (row.version !== request.baseVersion) throw versionConflictError(row);
  database.prepare(`UPDATE tasks SET title = ?, description = ?, subject_code = ?,
    due_date = ?, priority = ?, completed = ?, is_deleted = 0,
    version = version + 1, server_updated_at = ? WHERE id = ?`).run(
    task.title, task.description, task.subjectCode, task.dueDate, task.priority,
    Number(task.completed), updatedAt, task.id,
  );
  return { status: 200, body: taskResponse(database.prepare('SELECT * FROM tasks WHERE id = ?').get(task.id)) };
}

function deleteTask({ database, request }) {
  const row = database.prepare('SELECT * FROM tasks WHERE id = ?').get(request.id);
  if (!row) throw taskNotFoundError(request.id);
  if (row.version !== request.baseVersion) throw versionConflictError(row);
  database.prepare(`UPDATE tasks SET is_deleted = 1, version = version + 1,
    server_updated_at = ? WHERE id = ?`).run('2026-10-29T03:10:00Z', request.id);
  return { status: 200, body: taskResponse(database.prepare('SELECT * FROM tasks WHERE id = ?').get(request.id)) };
}

function expectCode(action, status, code) {
  assert.throws(action, (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, status);
    assert.equal(error.body.code, code);
    return true;
  });
}

function expectServerError(action) {
  assert.throws(action, (error) => {
    assert.ok(error instanceof Error);
    assert.equal(error instanceof ApiError, false);
    return true;
  });
}

test('mutation mới dùng cùng connection, validation sau fingerprint, task/receipt commit đủ', (t) => {
  const db = setup(t);
  const input = post({ body: { ...createBody, title: '  Học tiếng Việt 😀  ' } });
  const rawBefore = structuredClone(input);
  let calls = 0;
  const result = createMutationExecutor(db).execute(input, (context) => {
    calls += 1;
    assert.equal(context.database, db);
    assert.equal(db.inTransaction, true);
    assert.equal(context.request.task.title, 'Học tiếng Việt 😀');
    assert.equal(context.request.baseVersion, 0);
    assert.equal(context.request.mutationId, mutationId);
    return createTask(context);
  });
  assert.equal(calls, 1);
  assert.equal(result.status, 201);
  assert.equal(result.replayed, false);
  assert.equal(db.inTransaction, false);
  assert.deepEqual(input, rawBefore);
  const receipt = db.prepare('SELECT * FROM mutation_receipts WHERE mutation_id = ?').get(mutationId);
  assert.equal(receipt.task_id, createBody.id);
  assert.equal(receipt.method, 'POST');
  assert.equal(receipt.path, '/api/v1/tasks');
  assert.match(receipt.request_hash, /^[0-9a-f]{64}$/);
  assert.equal(receipt.response_status, 201);
  assert.deepEqual(JSON.parse(receipt.response_body), result.body);
  assert.ok(Number.isFinite(Date.parse(receipt.created_at)));
  assert.deepEqual(result.body, taskResponse(current(db)));
});

test('retry cùng key/request replay snapshot và không gọi lại callback hoặc sửa dữ liệu', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  const first = executor.execute(post(), createTask);
  const before = snapshot(db);
  const changes = db.prepare('SELECT total_changes() AS count').get().count;
  let calls = 0;
  const replay = executor.execute(post(), () => { calls += 1; throw new Error('Must not run'); });
  assert.deepEqual(replay, { ...first, replayed: true });
  assert.equal(calls, 0);
  assert.deepEqual(snapshot(db), before);
  assert.equal(db.prepare('SELECT total_changes() AS count').get().count, changes);
  assert.equal(typeof replay.body, 'object');
  assert.deepEqual(Object.keys(replay.body), ['task']);
});

test('PUT/DELETE giữ status 200 và retry qua receipt trước callback version đã cũ', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  executor.execute(post(), createTask);
  const updated = executor.execute(put(), updateTask);
  assert.equal(updated.status, 200);
  assert.equal(updated.body.task.version, 2);
  const deleted = executor.execute(remove(), deleteTask);
  assert.equal(deleted.status, 200);
  assert.equal(deleted.body.task.version, 3);
  assert.equal(deleted.body.task.isDeleted, true);
  assert.deepEqual(executor.execute(put(), updateTask), { ...updated, replayed: true });
  assert.deepEqual(executor.execute(remove(), deleteTask), { ...deleted, replayed: true });
  assert.equal(current(db).version, 3);
  assert.equal(db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count, 3);
});

test('task đổi tiếp hoặc thành tombstone không thay snapshot POST đã lưu để replay', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  const created = executor.execute(post(), createTask);
  executor.execute(put(), updateTask);
  executor.execute(remove(), deleteTask);
  const before = snapshot(db);
  const replay = executor.execute(post(), () => { throw new Error('Old POST must not run'); });
  assert.deepEqual(replay, { ...created, replayed: true });
  assert.equal(replay.body.task.isDeleted, false);
  assert.equal(replay.body.task.version, 1);
  assert.equal(current(db).version, 3);
  assert.equal(current(db).is_deleted, 1);
  assert.deepEqual(snapshot(db), before);
});

test('response trả cho caller có thể sửa nhưng không sửa receipt hoặc lần replay sau', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  const first = executor.execute(post(), createTask);
  const expected = structuredClone(first.body);
  first.body.task.title = 'Sửa object phía caller';
  const replay = executor.execute(post(), () => { throw new Error('Must not run'); });
  assert.deepEqual(replay.body, expected);
  replay.body.task.version = 999;
  assert.deepEqual(executor.execute(post(), createTask).body, expected);
});

test('cùng key khác payload bị 409 trước business validation, không che thành 422', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  executor.execute(post(), createTask);
  const before = snapshot(db);
  for (const input of [
    post({ body: { ...createBody, title: '' } }),
    post({ body: { ...createBody, title: ` ${createBody.title} ` } }),
    post({ body: { ...createBody, baseVersion: 1 } }),
    post({ body: { ...createBody, isDeleted: true } }),
    post({ body: { ...createBody, serverUpdatedAt: 'client data must not be ignored by hash' } }),
    post({ query: { ignoredByOldValidator: 'must still be fingerprinted' } }),
    post({ query: { ignoredByOldValidator: ['a', 'b'] } }),
  ]) {
    let calls = 0;
    expectCode(() => executor.execute(input, () => { calls += 1; }), 409, 'MUTATION_ID_REUSED');
    assert.equal(calls, 0);
    assert.deepEqual(snapshot(db), before);
  }
});

test('đổi method/path/query/baseVersion của một key đều bị nhận diện là request khác', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  executor.execute(post(), createTask);
  executor.execute(put(), updateTask);
  const before = snapshot(db);
  for (const input of [
    remove({ idempotencyKey: updateId }),
    put({ path: `/api/v1/tasks/${otherTaskId}`, body: { ...createBody, id: otherTaskId, baseVersion: 1 } }),
    put({ query: { extra: '1' } }),
    put({ body: { ...createBody, baseVersion: 2 } }),
  ]) {
    expectCode(() => executor.execute(input, () => { throw new Error('Must not run'); }), 409, 'MUTATION_ID_REUSED');
    assert.deepEqual(snapshot(db), before);
  }
});

test('mutation mới thiếu/sai nghiệp vụ hoặc query không hỗ trợ không tạo task/receipt', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  for (const input of [
    post({ body: null }), post({ body: { ...createBody, title: '  ' } }),
    post({ body: { ...createBody, completed: 'false' } }),
    post({ query: { extra: 'value' } }),
    remove({ query: { baseVersion: ['1', '2'] } }),
    remove({ query: { baseVersion: '1abc' } }),
    remove({ query: { baseVersion: '1', extra: 'value' } }),
  ]) {
    expectCode(() => executor.execute(input, () => { throw new Error('Must not run'); }), 422, 'VALIDATION_ERROR');
    assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  }
});

test('Idempotency-Key thiếu hoặc sai bị từ chối tối thiểu, không chạy callback', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  for (const idempotencyKey of [undefined, null, 'invalid', [mutationId]]) {
    expectCode(() => executor.execute(post({ idempotencyKey }), createTask), 422, 'VALIDATION_ERROR');
    assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  }
});

test('lỗi nghiệp vụ 404/409/422 giữ nguyên error và rollback các thay đổi trước lỗi', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  executor.execute(post(), createTask);
  const before = snapshot(db);
  for (const error of [taskNotFoundError(otherTaskId), versionConflictError(current(db)), validationError({ title: 'Bad title' }, createBody.id)]) {
    assert.throws(() => executor.execute(put(), ({ database }) => {
      database.prepare('UPDATE tasks SET title = ? WHERE id = ?').run('Phải rollback', createBody.id);
      throw error;
    }), (actual) => actual === error);
    assert.deepEqual(snapshot(db), before);
  }
});

test('callback lỗi bất ngờ sau khi ghi task thì rollback và không có receipt thành công', (t) => {
  const db = setup(t);
  const expected = new Error('Business callback failed');
  assert.throws(() => createMutationExecutor(db).execute(post(), (context) => {
    createTask(context);
    throw expected;
  }), (actual) => actual === expected);
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  assert.equal(db.inTransaction, false);
});

test('lỗi INSERT receipt rollback cả task vừa ghi', (t) => {
  const db = setup(t);
  db.exec(`CREATE TRIGGER reject_receipt BEFORE INSERT ON mutation_receipts
    BEGIN SELECT RAISE(ABORT, 'Receipt insert intentionally rejected'); END`);
  assert.throws(() => createMutationExecutor(db).execute(post(), createTask), { code: 'SQLITE_CONSTRAINT_TRIGGER' });
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
});

test('lỗi COMMIT do deferred FK không trả success và rollback task/receipt cùng nhau', (t) => {
  const db = setup(t);
  db.exec(`CREATE TABLE deferred_guard (task_id TEXT REFERENCES tasks(id) DEFERRABLE INITIALLY DEFERRED)`);
  assert.throws(() => createMutationExecutor(db).execute(post(), (context) => {
    const result = createTask(context);
    db.prepare('INSERT INTO deferred_guard (task_id) VALUES (?)').run(otherTaskId);
    return result;
  }), { code: 'SQLITE_CONSTRAINT_FOREIGNKEY' });
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  assert.equal(db.prepare('SELECT count(*) AS count FROM deferred_guard').get().count, 0);
  assert.equal(db.inTransaction, false);
});

test('callback phải trả đúng status/envelope/task đã lưu; kết quả sai không được commit', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  const invalidResults = [
    () => undefined,
    (result) => ({ ...result, status: 200 }),
    (result) => ({ ...result, body: JSON.stringify(result.body) }),
    (result) => ({ ...result, body: { task: result.body } }),
    (result) => ({ ...result, body: { ...result.body, metadata: 'not in envelope' } }),
    (result) => ({ ...result, body: { task: { ...result.body.task, completed: 0 } } }),
    (result) => ({ ...result, body: { task: { ...result.body.task, id: otherTaskId } } }),
    (result) => ({ ...result, body: { task: { ...result.body.task, title: 'Không khớp hàng đã lưu' } } }),
    (result) => ({ ...result, body: { task: { ...result.body.task, isDeleted: true } } }),
  ];
  for (const change of invalidResults) {
    expectServerError(() => executor.execute(post(), (context) => change(createTask(context))));
    assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  }
  expectServerError(() => executor.execute(post(), (context) => {
    context.request.task.id = otherTaskId;
    return createTask(context);
  }));
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
});

test('async callback kể cả bind bị chặn trước invocation, không có continuation ghi ngoài transaction', async (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  let calls = 0;
  async function invalidAsync(context) {
    calls += 1;
    createTask(context);
    await Promise.resolve();
    db.prepare('UPDATE tasks SET title = ?').run('Continuation không được chạy');
  }
  for (const apply of [invalidAsync, invalidAsync.bind(null)]) {
    expectServerError(() => executor.execute(post(), apply));
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(calls, 0);
    assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
  }
});

test('sync callback trả thenable bị rollback thay vì commit response không hợp lệ', (t) => {
  const db = setup(t);
  expectServerError(() => createMutationExecutor(db).execute(post(), (context) => Promise.resolve(createTask(context))));
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
});

test('executor từ chối transaction đang mở để không trả success trước outer COMMIT', (t) => {
  const db = setup(t);
  let calls = 0;
  db.transaction(() => {
    expectServerError(() => createMutationExecutor(db).execute(post(), () => { calls += 1; }));
    assert.equal(db.inTransaction, true);
  })();
  assert.equal(calls, 0);
  assert.deepEqual(snapshot(db), { tasks: [], receipts: [] });
});

test('receipt hỏng JSON/schema/metadata báo lỗi server và không áp dụng lại mutation', (t) => {
  const db = setup(t);
  const executor = createMutationExecutor(db);
  executor.execute(post(), createTask);
  const original = db.prepare('SELECT * FROM mutation_receipts').get();
  const badTask = { ...JSON.parse(original.response_body).task, id: otherTaskId };
  const corruptions = [
    ['response_body', 'not JSON'], ['response_body', '{"task":null}'],
    ['response_body', JSON.stringify({ task: badTask })], ['response_status', 200],
    ['path', '/api/v1/tasks/not-the-original-path'], ['created_at', 'not-a-date'],
    ['request_hash', 'a'.repeat(64) + '\n'], ['request_hash', 'not-a-hash'],
  ];
  db.pragma('ignore_check_constraints = ON');
  for (const [column, value] of corruptions) {
    db.prepare(`UPDATE mutation_receipts SET ${column} = ? WHERE mutation_id = ?`).run(value, mutationId);
    const before = snapshot(db);
    let calls = 0;
    expectServerError(() => executor.execute(post(), () => { calls += 1; return createTask({ database: db, request: { task: createBody } }); }));
    assert.equal(calls, 0);
    assert.deepEqual(snapshot(db), before);
    db.prepare(`UPDATE mutation_receipts SET ${column} = ? WHERE mutation_id = ?`).run(original[column], mutationId);
  }
  db.pragma('ignore_check_constraints = OFF');
});

test('task và receipt tồn tại sau khi đóng và mở connection file tạm', (t) => {
  const { directory, filename } = temporaryFile(t);
  let db;
  t.after(() => { if (db?.open) db.close(); rmSync(directory, { recursive: true, force: true }); });
  db = openDatabase(filename);
  migrateDatabase(db);
  const first = createMutationExecutor(db).execute(post(), createTask);
  db.close();
  db = openDatabase(filename);
  const replay = createMutationExecutor(db).execute(post(), () => { throw new Error('Must replay after reopening'); });
  assert.deepEqual(replay, { ...first, replayed: true });
  assert.equal(current(db).version, 1);
  assert.equal(db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count, 1);
});

test('commit xong nhưng bỏ phản hồi: gửi lại cùng key nhận snapshot đã lưu, không apply lần hai', (t) => {
  const db = setup(t);
  let applications = 0;
  const apply = (context) => { applications += 1; return createTask(context); };
  // Deliberately discard the first return value: simulate the client missing it.
  createMutationExecutor(db).execute(post(), apply);
  const persisted = db.prepare('SELECT response_status, response_body FROM mutation_receipts').get();
  const retry = createMutationExecutor(db).execute(post(), apply);
  assert.equal(applications, 1);
  assert.equal(retry.status, persisted.response_status);
  assert.deepEqual(retry.body, JSON.parse(persisted.response_body));
  assert.equal(current(db).version, 1);
  assert.equal(current(db).server_updated_at, createdAt);
});

function waitForMessage(worker, event) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error(`Worker did not send ${event}`)), 20000);
    function finish(error, message) {
      clearTimeout(timer);
      worker.off('message', receive);
      worker.off('error', failed);
      worker.off('exit', exited);
      if (error) reject(error); else resolve(message);
    }
    function receive(message) {
      if (message.event === 'failed') finish(new Error(message.message));
      else if (message.event === event) finish(undefined, message);
    }
    function failed(error) { finish(error); }
    function exited(code) { finish(new Error(`Worker exited (${code}) before ${event}`)); }
    worker.on('message', receive);
    worker.once('error', failed);
    worker.once('exit', exited);
  });
}

test('hai worker tranh cùng key: writer giữ khóa, contender BUSY rồi retry replay, callback chỉ áp dụng một lần', async (t) => {
  const { directory, filename } = temporaryFile(t);
  const db = openDatabase(filename);
  migrateDatabase(db);
  db.close();
  const gateBuffer = new SharedArrayBuffer(4);
  const gate = new Int32Array(gateBuffer);
  const workers = [];
  t.after(async () => {
    Atomics.store(gate, 0, 1);
    Atomics.notify(gate, 0);
    await Promise.all(workers.map((worker) => worker.terminate()));
    rmSync(directory, { recursive: true, force: true });
  });
  function start(role) {
    const worker = new Worker(new URL('../test-support/mutation-worker.js', import.meta.url), {
      workerData: { role, filename, gateBuffer, input: post(), createdAt },
    });
    // waitForMessage installs detailed rejection handlers during each protocol step.
    worker.on('error', () => {});
    workers.push(worker);
    return worker;
  }
  const writer = start('writer');
  await waitForMessage(writer, 'applied');
  const contender = start('contender');
  const busy = await waitForMessage(contender, 'busy');
  assert.equal(busy.code, 'SQLITE_BUSY');
  assert.equal(busy.calls, 0);
  const written = waitForMessage(writer, 'done');
  Atomics.store(gate, 0, 1);
  Atomics.notify(gate, 0);
  const committed = await written;
  assert.equal(committed.calls, 1);
  assert.equal(committed.result.replayed, false);
  const retried = waitForMessage(contender, 'done');
  contender.postMessage('retry');
  const replay = await retried;
  assert.equal(replay.calls, 0);
  assert.deepEqual(replay.result, { ...committed.result, replayed: true });
  const reopened = openDatabase(filename, { readonly: true });
  try {
    assert.equal(current(reopened).version, 1);
    assert.equal(reopened.prepare('SELECT count(*) AS count FROM tasks').get().count, 1);
    assert.equal(reopened.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count, 1);
  } finally { reopened.close(); }
});

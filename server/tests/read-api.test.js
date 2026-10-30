import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';

function example(name) {
  return JSON.parse(readFileSync(new URL(`../../contracts/examples/${name}.json`, import.meta.url), 'utf8'));
}

const tasks = example('task-list').tasks;
const missing = example('task-not-found-response');
const taskFields = ['id', 'title', 'description', 'subjectCode', 'dueDate', 'priority', 'completed', 'isDeleted', 'version', 'serverUpdatedAt'];

function setup(t, rows = []) {
  const db = openDatabase(':memory:');
  t.after(() => { if (db.open) db.close(); });
  migrateDatabase(db);
  const insert = db.prepare(`INSERT INTO tasks (
    id, title, description, subject_code, due_date, priority,
    completed, is_deleted, version, server_updated_at
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  for (const task of rows) {
    insert.run(task.id, task.title, task.description, task.subjectCode, task.dueDate,
      task.priority, Number(task.completed), Number(task.isDeleted), task.version, task.serverUpdatedAt);
  }
  return db;
}

async function serve(t, database) {
  const logs = [];
  const app = createApp({ database, logger: { error: (...args) => logs.push(args) } });
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections();
  }));
  return {
    logs,
    request: (path, options) => fetch(`http://127.0.0.1:${server.address().port}${path}`, options),
  };
}

function snapshot(db) {
  return {
    tasks: db.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: db.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
    migrations: db.prepare('SELECT * FROM schema_migrations ORDER BY name').all(),
  };
}

function assertTask(task) {
  assert.deepEqual(Object.keys(task).sort(), [...taskFields].sort());
  assert.equal(typeof task.completed, 'boolean');
  assert.equal(typeof task.isDeleted, 'boolean');
  assert.ok(Number.isSafeInteger(task.version));
  assert.ok(task.dueDate === null || /^\d{4}-\d{2}-\d{2}$/.test(task.dueDate));
}

test('GET danh sách: database rỗng trả 200 JSON {tasks: []}', async (t) => {
  const db = setup(t);
  const { request } = await serve(t, db);
  const response = await request('/api/v1/tasks?includeDeleted=true');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^application\/json; charset=utf-8$/);
  assert.deepEqual(await response.json(), example('empty-task-list'));
});

test('GET includeDeleted=true: đủ task sống/tombstone, ID duy nhất, kiểu JSON và thứ tự ổn định', async (t) => {
  const extra = {
    ...tasks[0], id: '8ba5f9d4-4264-4ed9-8b2b-778ffb052257',
    title: 'Ôn tập Cơ sở dữ liệu 😀', dueDate: '2028-02-29', completed: true,
  };
  const allTasks = [tasks[1], extra, tasks[0]];
  const db = setup(t, allTasks);
  const { request } = await serve(t, db);
  const first = await request('/api/v1/tasks?includeDeleted=true');
  assert.equal(first.status, 200);
  const result = await first.json();
  assert.deepEqual(Object.keys(result), ['tasks']);
  assert.equal(result.tasks.length, 3);
  assert.equal(new Set(result.tasks.map((task) => task.id)).size, 3);
  assert.deepEqual(result.tasks, [...allTasks].sort((left, right) => left.id.localeCompare(right.id)));
  for (const task of result.tasks) assertTask(task);
  assert.ok(result.tasks.some((task) => task.isDeleted));
  assert.ok(result.tasks.some((task) => task.completed));
  assert.ok(result.tasks.some((task) => task.dueDate === null));
  const second = await request('/api/v1/tasks?includeDeleted=true');
  assert.equal(second.status, 200);
  assert.deepEqual(await second.json(), result);
});

test('GET thiếu includeDeleted dùng default true và trả cùng toàn bộ dữ liệu, gồm tombstone', async (t) => {
  const db = setup(t, tasks);
  const { request } = await serve(t, db);
  const omitted = await request('/api/v1/tasks');
  const explicit = await request('/api/v1/tasks?includeDeleted=true');
  assert.equal(omitted.status, 200);
  assert.equal(explicit.status, 200);
  const result = await omitted.json();
  assert.deepEqual(result, await explicit.json());
  assert.deepEqual(result, example('task-list'));
  assert.equal(result.tasks.some((task) => task.isDeleted), true);
});

test('GET includeDeleted=false chỉ lấy task sống; chỉ có tombstone vẫn 200 danh sách rỗng', async (t) => {
  const db = setup(t, tasks);
  const { request } = await serve(t, db);
  const before = snapshot(db);
  const active = await request('/api/v1/tasks?includeDeleted=false');
  assert.equal(active.status, 200);
  const result = await active.json();
  assert.deepEqual(result, { tasks: tasks.filter((task) => !task.isDeleted) });
  for (const task of result.tasks) assert.equal(task.isDeleted, false);
  assert.deepEqual(snapshot(db), before);

  const onlyDeleted = setup(t, tasks.filter((task) => task.isDeleted));
  const second = await serve(t, onlyDeleted);
  const empty = await second.request('/api/v1/tasks?includeDeleted=false');
  assert.equal(empty.status, 200);
  assert.deepEqual(await empty.json(), { tasks: [] });
});

test('GET detail: task sống và tombstone đều trả 200, giữ tiếng Việt/null và đủ envelope', async (t) => {
  const db = setup(t, tasks);
  const { request } = await serve(t, db);
  const before = snapshot(db);
  for (const task of tasks) {
    const response = await request(`/api/v1/tasks/${task.id}`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /application\/json/);
    const result = await response.json();
    assert.deepEqual(result, { task });
    assertTask(result.task);
    assert.equal(result.task.dueDate, null);
  }
  assert.deepEqual(snapshot(db), before);
});

test('GET detail: UUID hợp lệ chưa tồn tại trả TASK_NOT_FOUND đúng fixture và taskId', async (t) => {
  const db = setup(t, tasks);
  const { request } = await serve(t, db);
  const response = await request(`/api/v1/tasks/${missing.taskId}`);
  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), missing);
});

test('GET detail: ID sai bị 422 trước truy vấn database, không giả thành TASK_NOT_FOUND', async (t) => {
  const db = setup(t, tasks);
  const { request, logs } = await serve(t, db);
  db.close();
  for (const id of ['not-a-uuid', encodeURIComponent("' OR 1=1 --"), `${tasks[0].id}x`]) {
    const response = await request(`/api/v1/tasks/${id}`);
    assert.equal(response.status, 422);
    const result = await response.json();
    assert.equal(result.code, 'VALIDATION_ERROR');
    assert.equal(typeof result.fieldErrors.id, 'string');
    assert.equal(Object.hasOwn(result, 'taskId'), false);
  }
  assert.equal(logs.length, 0);
});

test('GET detail: URL encoding sai trả 400 kín, không biến thành lỗi server 500', async (t) => {
  const db = setup(t, tasks);
  const { request, logs } = await serve(t, db);
  const before = snapshot(db);
  for (const encodedId of ['%ZZ', '%E0%A4%A']) {
    const response = await request(`/api/v1/tasks/${encodedId}`);
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { code: 'BAD_REQUEST', message: 'Invalid URL encoding' });
  }
  assert.equal(logs.length, 0);
  assert.deepEqual(snapshot(db), before);
});

test('GET danh sách: query sai hoặc lặp trả validation 422 trước truy vấn', async (t) => {
  const db = setup(t);
  const { request, logs } = await serve(t, db);
  db.close();
  for (const query of [
    'includeDeleted=0', 'includeDeleted=1', 'includeDeleted=False', 'includeDeleted=',
    'includeDeleted=true%20', 'includeDeleted=true&includeDeleted=false',
    'includeDeleted=true&includeDeleted=true', 'includeDeleted[]=false',
    'includeDeleted[value]=true',
  ]) {
    const response = await request(`/api/v1/tasks?${query}`);
    assert.equal(response.status, 422);
    const result = await response.json();
    assert.equal(result.code, 'VALIDATION_ERROR');
    assert.equal(typeof result.fieldErrors.includeDeleted, 'string');
    assert.equal(Object.hasOwn(result, 'taskId'), false);
  }
  assert.equal(logs.length, 0);
});

test('GET danh sách: includeDeleted sau 1000 query khác vẫn được kiểm tra và lọc đúng', async (t) => {
  const db = setup(t, tasks);
  const { request } = await serve(t, db);
  const before = snapshot(db);
  const padding = Array.from({ length: 1000 }, (_, index) => `x${index}=a`).join('&');
  const response = await request(`/api/v1/tasks?${padding}&includeDeleted=false`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { tasks: tasks.filter((task) => !task.isDeleted) });
  assert.deepEqual(snapshot(db), before);
});

test('GET danh sách: includeDeleted lặp sau 1000 query khác không bị parser bỏ qua', async (t) => {
  const db = setup(t, tasks);
  const { request, logs } = await serve(t, db);
  const before = snapshot(db);
  const padding = Array.from({ length: 1000 }, (_, index) => `x${index}=a`).join('&');
  const response = await request(`/api/v1/tasks?includeDeleted=false&${padding}&includeDeleted=true`);
  assert.equal(response.status, 422);
  const result = await response.json();
  assert.equal(result.code, 'VALIDATION_ERROR');
  assert.equal(typeof result.fieldErrors.includeDeleted, 'string');
  assert.deepEqual(snapshot(db), before);
  assert.equal(logs.length, 0);
});

test('GET không cập nhật task/version/timestamp, tạo receipt hoặc sửa receipt đã có', async (t) => {
  const db = setup(t, tasks);
  db.prepare(`INSERT INTO mutation_receipts (
    mutation_id, task_id, method, path, request_hash, response_status, response_body, created_at
  ) VALUES (?, ?, 'POST', '/api/v1/tasks', ?, 201, ?, ?)`).run(
    '7fe7b179-6677-43ec-99e2-c0c035a184a8', tasks[0].id, 'a'.repeat(64),
    JSON.stringify({ task: tasks[0] }), '2026-10-28T03:00:00Z',
  );
  const before = snapshot(db);
  const changesBefore = db.prepare('SELECT total_changes() AS count').get().count;
  const { request } = await serve(t, db);
  for (const path of [
    '/api/v1/tasks?includeDeleted=true', '/api/v1/tasks', '/api/v1/tasks?includeDeleted=false',
    `/api/v1/tasks/${tasks[0].id}`,
    `/api/v1/tasks/${tasks[1].id}`, '/api/v1/health',
  ]) {
    const response = await request(path);
    assert.equal(response.status, 200);
    await response.text();
  }
  assert.deepEqual(snapshot(db), before);
  assert.equal(db.prepare('SELECT total_changes() AS count').get().count, changesBefore);
});

test('GET lỗi database đóng/thiếu schema không thành 200/404 và không lộ SQL/đường dẫn/stack', async (t) => {
  const closed = setup(t, tasks);
  const emptySchema = openDatabase(':memory:');
  t.after(() => emptySchema.close());
  for (const db of [closed, emptySchema, undefined]) {
    const { request, logs } = await serve(t, db);
    if (db === closed) db.close();
    for (const path of ['/api/v1/tasks?includeDeleted=true', `/api/v1/tasks/${tasks[0].id}`]) {
      const response = await request(path);
      assert.equal(response.status, 500);
      assert.deepEqual(await response.json(), { code: 'INTERNAL_ERROR', message: 'Internal server error' });
    }
    assert.equal(logs.length, 2);
    assert.equal(logs[0][1].method, 'GET');
    assert.equal(typeof logs[0][1].stackFrames, 'string');
  }
});

test('GET bản ghi SQLite không hợp lệ gây 500, không tự sửa rồi trả thành công', async (t) => {
  const db = setup(t, tasks);
  // SQLite permits this title, but the API serializer requires canonical trimmed text.
  db.prepare('UPDATE tasks SET title = ? WHERE id = ?').run('  Chưa trim  ', tasks[0].id);
  const before = snapshot(db);
  const { request, logs } = await serve(t, db);
  for (const path of ['/api/v1/tasks?includeDeleted=true', `/api/v1/tasks/${tasks[0].id}`]) {
    const response = await request(path);
    assert.equal(response.status, 500);
    assert.deepEqual(await response.json(), { code: 'INTERNAL_ERROR', message: 'Internal server error' });
  }
  assert.equal(logs.length, 2);
  assert.deepEqual(snapshot(db), before);
});

test('health chỉ 200 khi database/schema sẵn sàng; không database/thiếu schema/đóng đều 503 rỗng', async (t) => {
  const ready = setup(t);
  const noSchema = openDatabase(':memory:');
  t.after(() => noSchema.close());
  for (const [db, expected] of [[ready, 200], [noSchema, 503], [undefined, 503]]) {
    const { request } = await serve(t, db);
    const response = await request('/api/v1/health');
    assert.equal(response.status, expected);
    assert.equal(await response.text(), '');
    assert.equal(response.headers.get('content-type'), null);
  }
  const { request } = await serve(t, ready);
  ready.close();
  const unavailable = await request('/api/v1/health');
  assert.equal(unavailable.status, 503);
  assert.equal(await unavailable.text(), '');
});

test('route HTTP không tồn tại trả ROUTE_NOT_FOUND và không đổi dữ liệu', async (t) => {
  const db = setup(t, tasks);
  const { request } = await serve(t, db);
  const before = snapshot(db);
  for (const [method, path] of [
    ['GET', '/api/v1/not-an-endpoint'], ['GET', `/api/v1/tasks/${tasks[0].id}/not-an-endpoint`],
  ]) {
    const response = await request(path, { method });
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { code: 'ROUTE_NOT_FOUND', message: 'Route not found' });
    assert.equal(response.headers.has('x-powered-by'), false);
  }
  assert.deepEqual(snapshot(db), before);
});

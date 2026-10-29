import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { createTaskRepository } from '../src/repositories/task-repository.js';

const fixtureTasks = JSON.parse(readFileSync(new URL('../../contracts/examples/task-list.json', import.meta.url), 'utf8')).tasks;
const missingId = '6a144198-9c2b-49dc-8c56-cf295b92d016';

function setup(t, filename = ':memory:') {
  const db = openDatabase(filename);
  t.after(() => { if (db.open) db.close(); });
  migrateDatabase(db);
  return db;
}

function insertTasks(db, tasks = fixtureTasks) {
  const insert = db.prepare(`INSERT INTO tasks (
    id, title, description, subject_code, due_date, priority,
    completed, is_deleted, version, server_updated_at
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  for (const task of tasks) {
    insert.run(task.id, task.title, task.description, task.subjectCode, task.dueDate,
      task.priority, Number(task.completed), Number(task.isDeleted), task.version, task.serverUpdatedAt);
  }
}

test('repository đọc database rỗng thành danh sách rỗng và detail không tìm thấy', (t) => {
  const db = setup(t);
  const repository = createTaskRepository(db);
  assert.deepEqual(repository.findAll(), []);
  assert.deepEqual(repository.findActive(), []);
  assert.equal(repository.findById(missingId), undefined);
});

test('repository trả đủ hàng SQLite, gồm tombstone, thứ tự ID ổn định và không trùng', (t) => {
  const db = setup(t);
  insertTasks(db, [...fixtureTasks].reverse());
  const repository = createTaskRepository(db);
  const rows = repository.findAll();
  const expected = db.prepare('SELECT * FROM tasks ORDER BY id').all();
  assert.deepEqual(rows, expected);
  assert.equal(rows.length, fixtureTasks.length);
  assert.equal(new Set(rows.map((row) => row.id)).size, rows.length);
  assert.equal(rows.find((row) => row.id === fixtureTasks[1].id).is_deleted, 1);
  assert.deepEqual(repository.findAll(), rows);
});

test('repository tìm task sống và tombstone bằng cùng ID, giữ 0/1 để mapper xử lý', (t) => {
  const db = setup(t);
  insertTasks(db);
  const repository = createTaskRepository(db);
  for (const task of fixtureTasks) {
    const row = repository.findById(task.id);
    assert.deepEqual(row, db.prepare('SELECT * FROM tasks WHERE id = ?').get(task.id));
    assert.equal(row.title, task.title);
    assert.equal(row.due_date, null);
    assert.equal(row.is_deleted, Number(task.isDeleted));
  }
});

test('repository findActive chỉ lấy task sống, giữ thứ tự ổn định và không sửa tombstone', (t) => {
  const db = setup(t);
  insertTasks(db, [...fixtureTasks].reverse());
  const before = db.prepare('SELECT * FROM tasks ORDER BY id').all();
  const repository = createTaskRepository(db);
  assert.deepEqual(repository.findActive(), before.filter((row) => row.is_deleted === 0));
  assert.deepEqual(repository.findAll(), before);
  assert.deepEqual(db.prepare('SELECT * FROM tasks ORDER BY id').all(), before);
  assert.equal(db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count, 0);
});

test('repository parameter binding không hiểu ID chứa SQL như câu lệnh', (t) => {
  const db = setup(t);
  insertTasks(db);
  const repository = createTaskRepository(db);
  for (const id of [missingId, `${fixtureTasks[0].id}' OR 1=1 --`, "'; DROP TABLE tasks; --"]) {
    assert.equal(repository.findById(id), undefined);
  }
  assert.equal(db.prepare('SELECT count(*) AS count FROM tasks').get().count, fixtureTasks.length);
});

test('repository không nuốt lỗi thiếu schema hoặc connection đóng thành []/undefined', (t) => {
  const noSchema = openDatabase(':memory:');
  t.after(() => { if (noSchema.open) noSchema.close(); });
  const repository = createTaskRepository(noSchema);
  assert.throws(() => repository.findAll(), { code: 'SQLITE_ERROR' });
  assert.throws(() => repository.findActive(), { code: 'SQLITE_ERROR' });
  assert.throws(() => repository.findById(missingId), { code: 'SQLITE_ERROR' });
  noSchema.close();
  assert.throws(() => repository.findAll());
  assert.throws(() => repository.findActive());
  assert.throws(() => repository.findById(missingId));
});

test('repository GET không sửa hàng, version/timestamp, lịch sử migration hoặc receipt', (t) => {
  const db = setup(t);
  insertTasks(db);
  const snapshot = () => ({
    tasks: db.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: db.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
    migrations: db.prepare('SELECT * FROM schema_migrations ORDER BY name').all(),
  });
  const before = snapshot();
  const changesBefore = db.prepare('SELECT total_changes() AS count').get().count;
  const repository = createTaskRepository(db);
  repository.findAll();
  repository.findActive();
  for (const task of fixtureTasks) repository.findById(task.id);
  repository.findById(missingId);
  assert.deepEqual(snapshot(), before);
  assert.equal(db.prepare('SELECT total_changes() AS count').get().count, changesBefore);
});

test('WAL: reader thấy trọn bản cũ khi writer chưa commit và trọn bản mới sau commit', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'studyflow-read-snapshot-test-'));
  const filename = join(directory, 'snapshot.db');
  let writer;
  let reader;
  t.after(() => {
    if (reader?.open) reader.close();
    if (writer?.open) writer.close();
    rmSync(directory, { recursive: true, force: true });
  });
  writer = openDatabase(filename);
  migrateDatabase(writer);
  const original = fixtureTasks.map((task) => ({ ...task, version: 1 }));
  insertTasks(writer, original);
  reader = openDatabase(filename, { readonly: true });
  assert.equal(writer.pragma('journal_mode', { simple: true }), 'wal');
  const repository = createTaskRepository(reader);
  writer.transaction(() => {
    writer.prepare('UPDATE tasks SET version = 2, server_updated_at = ?').run('2026-10-29T04:00:00Z');
    assert.deepEqual(writer.prepare('SELECT DISTINCT version FROM tasks').all(), [{ version: 2 }]);
    const rows = repository.findAll();
    assert.deepEqual(rows.map((row) => row.version), [1, 1]);
    assert.deepEqual(rows.map((row) => row.server_updated_at), original.map((task) => task.serverUpdatedAt));
  }).immediate();
  const afterCommit = repository.findAll();
  assert.deepEqual(afterCommit.map((row) => row.version), [2, 2]);
  assert.deepEqual(afterCommit.map((row) => row.server_updated_at), ['2026-10-29T04:00:00Z', '2026-10-29T04:00:00Z']);
  assert.equal(reader.inTransaction, false);
});

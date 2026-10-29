import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import {
  mutationIdReusedError,
  taskNotFoundError,
  versionConflictError,
} from '../src/http/api-errors.js';
import { taskFromRow, taskListResponse, taskResponse } from '../src/http/task-responses.js';

function example(name) {
  return JSON.parse(readFileSync(new URL(`../../contracts/examples/${name}.json`, import.meta.url), 'utf8'));
}

function rowFor(task) {
  return {
    id: task.id, title: task.title, description: task.description,
    subject_code: task.subjectCode, due_date: task.dueDate, priority: task.priority,
    completed: Number(task.completed), is_deleted: Number(task.isDeleted),
    version: task.version, server_updated_at: task.serverUpdatedAt,
  };
}

test('mapper/envelope khớp mọi fixture task của hợp đồng, cả tombstone và danh sách rỗng', () => {
  assert.deepEqual(taskFromRow(rowFor(example('task'))), example('task'));
  assert.deepEqual(taskFromRow(rowFor(example('tombstone'))), example('tombstone'));
  for (const name of ['task-response', 'update-task-response', 'delete-task-response']) {
    const expected = example(name);
    assert.deepEqual(taskResponse(rowFor(expected.task)), expected);
  }
  const list = example('task-list');
  assert.deepEqual(taskListResponse(list.tasks.map(rowFor)), list);
  assert.deepEqual(taskListResponse([]), example('empty-task-list'));
});

test('mapper đọc hàng SQLite thật và không lộ cột nội bộ, baseVersion hoặc metadata', (t) => {
  const db = openDatabase(':memory:');
  t.after(() => db.close());
  migrateDatabase(db);
  const task = { ...example('task'), completed: true, dueDate: null };
  db.prepare(`INSERT INTO tasks (id, title, description, subject_code, due_date, priority,
    completed, is_deleted, version, server_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(...Object.values(rowFor(task)));
  const row = db.prepare('SELECT * FROM tasks').get();
  assert.equal(row.completed, 1);
  assert.equal(row.is_deleted, 0);
  assert.deepEqual(taskFromRow({ ...row, baseVersion: 99, pending: true, localRevision: 4 }), task);
  assert.deepEqual(taskFromRow(row), task);
  assert.equal(row.completed, 1);
});

test('mapper không sửa hoặc trả thành công khi bản ghi database sai/thiếu trường', () => {
  const validRow = rowFor(example('task'));
  for (const [column, value] of [
    ['id', 'bad'], ['title', '  padded  '], ['title', '\t'], ['title', '😀'.repeat(121)],
    ['description', null], ['description', 'x\u0000y'], ['description', '\ud800'],
    ['subject_code', 'UNKNOWN'], ['priority', 'urgent'], ['due_date', ''],
    ['due_date', undefined], ['due_date', '1900-02-29'], ['completed', '0'],
    ['completed', false], ['completed', 2], ['is_deleted', null], ['is_deleted', '1'],
    ['version', 0], ['version', 1.5], ['version', Number.MAX_SAFE_INTEGER + 1],
    ['server_updated_at', '2026-02-30T03:00:00Z'], ['server_updated_at', '2026-10-29T24:00:00Z'],
    ['server_updated_at', '2026-10-29T03:00:00+07:00'],
  ]) assert.throws(() => taskFromRow({ ...validRow, [column]: value }), TypeError, column);
  for (const column of Object.keys(validRow)) {
    const row = { ...validRow };
    delete row[column];
    assert.throws(() => taskFromRow(row), TypeError, column);
  }
  for (const row of [undefined, null, [], 'task']) assert.throws(() => taskFromRow(row), TypeError);
  assert.throws(() => taskListResponse(undefined), TypeError);
  assert.throws(() => taskListResponse([validRow, { ...validRow, due_date: '' }]), TypeError);
  assert.equal(taskFromRow({ ...validRow, server_updated_at: '2026-10-29T03:00:00.123Z' }).serverUpdatedAt, '2026-10-29T03:00:00.123Z');
});

test('factory chuẩn bị 404 và hai loại 409 đúng fixture, currentTask luôn đầy đủ', () => {
  const missing = example('task-not-found-response');
  const notFound = taskNotFoundError(missing.taskId);
  assert.equal(notFound.status, 404);
  assert.deepEqual(notFound.body, missing);
  const reused = example('mutation-id-reused-response');
  assert.equal(mutationIdReusedError(reused.taskId).status, 409);
  assert.deepEqual(mutationIdReusedError(reused.taskId).body, reused);
  assert.equal(Object.hasOwn(mutationIdReusedError().body, 'taskId'), false);
  const conflict = example('conflict-response');
  const error = versionConflictError(rowFor(conflict.currentTask));
  assert.equal(error.status, 409);
  assert.deepEqual(error.body, conflict);
  assert.throws(() => versionConflictError({ id: conflict.taskId }), TypeError);
  assert.throws(() => taskNotFoundError('bad'), TypeError);
  assert.throws(() => mutationIdReusedError('bad'), TypeError);
});

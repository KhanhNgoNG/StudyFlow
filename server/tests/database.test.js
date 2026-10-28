import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { openDatabase, resolveDatabasePath } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { demoTasks, resetDatabase, seedDatabase } from '../src/database/seed.js';

function setup(t) {
  const directory = mkdtempSync(join(tmpdir(), 'studyflow-db-test-'));
  const filename = join(directory, 'test.db');
  const db = openDatabase(filename);
  t.after(() => {
    if (db.open) db.close();
    rmSync(directory, { recursive: true, force: true });
  });
  return { db, filename, directory };
}

function receipt(db, id = '7fe7b179-6677-43ec-99e2-c0c035a184a8') {
  db.prepare(`INSERT INTO mutation_receipts (
    mutation_id, task_id, method, path, request_hash, response_status, response_body, created_at
  ) VALUES (?, ?, 'POST', '/api/v1/tasks', ?, 201, ?, ?)`).run(
    id, demoTasks[0].id, 'a'.repeat(64),
    JSON.stringify({ task: demoTasks[0] }), '2026-10-28T03:00:00Z',
  );
}

test('migration có thể chạy lại và seed không trùng hoặc ghi đè sửa mới', (t) => {
  const { db } = setup(t);
  assert.deepEqual(migrateDatabase(db), ['001_initial.sql']);
  assert.deepEqual(migrateDatabase(db), []);
  assert.equal(seedDatabase(db).inserted, 3);
  db.prepare('UPDATE tasks SET title = ?, version = 2 WHERE id = ?').run('Sửa sau seed', demoTasks[0].id);
  assert.deepEqual(seedDatabase(db), { inserted: 0, skipped: true });
  assert.equal(db.prepare('SELECT count(*) AS n FROM tasks').get().n, 3);
  assert.equal(db.prepare('SELECT title FROM tasks WHERE id = ?').get(demoTasks[0].id).title, 'Sửa sau seed');
  assert.equal(db.prepare('SELECT count(*) AS n FROM tasks WHERE is_deleted = 1').get().n, 1);
});

test('migration lỗi rollback cả DDL và lịch sử', (t) => {
  const { db, directory } = setup(t);
  writeFileSync(join(directory, '001_bad.sql'), 'CREATE TABLE temporary_table (id INTEGER); INVALID SQL;');
  assert.throws(() => migrateDatabase(db, directory));
  assert.equal(db.prepare("SELECT count(*) AS n FROM sqlite_master WHERE type = 'table'").get().n, 0);
});

test('từ chối sửa migration đã chạy', (t) => {
  const { db, directory } = setup(t);
  const sql = readFileSync(new URL('../src/database/migrations/001_initial.sql', import.meta.url), 'utf8');
  const file = join(directory, '001_initial.sql');
  writeFileSync(file, sql);
  migrateDatabase(db, directory);
  writeFileSync(file, sql + '\n-- thay đổi sau khi chạy\n');
  assert.throws(() => migrateDatabase(db, directory), /Migration đã chạy/);
});

test('task và receipt tồn tại sau khi đóng và mở bằng tiến trình Node khác', (t) => {
  const { db, filename } = setup(t);
  migrateDatabase(db); seedDatabase(db); receipt(db); db.close();
  const moduleUrl = new URL('../src/database/connection.js', import.meta.url).href;
  const child = spawnSync(process.execPath, ['--input-type=module', '-e', `
    import { openDatabase } from ${JSON.stringify(moduleUrl)};
    const db = openDatabase(process.argv[1], { readonly: true });
    console.log(JSON.stringify({
      tasks: db.prepare('SELECT count(*) AS n FROM tasks').get().n,
      receipts: db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n,
      deleted: db.prepare('SELECT count(*) AS n FROM tasks WHERE is_deleted=1').get().n,
      integrity: db.pragma('integrity_check', { simple: true })
    })); db.close();
  `, filename], { encoding: 'utf8' });
  assert.equal(child.status, 0, child.stderr);
  assert.deepEqual(JSON.parse(child.stdout), { tasks: 3, receipts: 1, deleted: 1, integrity: 'ok' });
});

test('ràng buộc database chặn dữ liệu không hợp lệ', (t) => {
  const { db } = setup(t); migrateDatabase(db); seedDatabase(db);
  for (const [column, value] of [
    ['id', 'not-a-uuid'], ['title', '   '], ['title', 'a'.repeat(121)],
    ['description', 'a'.repeat(2001)], ['subject_code', 'UNKNOWN'], ['priority', 'URGENT'],
    ['completed', 2], ['is_deleted', -1], ['version', 0], ['version', 1.5],
    ['due_date', '2026-02-30'], ['due_date', '2026-13-01'], ['due_date', '2026-1-01'],
    ['server_updated_at', 'not-a-time'],
  ]) {
    assert.throws(() => db.prepare(`UPDATE tasks SET ${column} = ? WHERE id = ?`).run(value, demoTasks[0].id), column);
  }
  db.prepare('UPDATE tasks SET due_date = ? WHERE id = ?').run('2028-02-29', demoTasks[0].id);
  assert.equal(db.prepare('SELECT due_date FROM tasks WHERE id=?').get(demoTasks[0].id).due_date, '2028-02-29');
});

test('không cho trùng task ID hoặc mutation ID', (t) => {
  const { db } = setup(t); migrateDatabase(db); seedDatabase(db); receipt(db);
  assert.throws(() => db.exec('INSERT INTO tasks SELECT * FROM tasks'));
  assert.throws(() => receipt(db), /UNIQUE/);
});

test('lỗi ghi receipt rollback thay đổi task trong cùng transaction', (t) => {
  const { db } = setup(t); migrateDatabase(db); seedDatabase(db); receipt(db);
  assert.throws(() => db.transaction(() => {
    db.prepare('UPDATE tasks SET version = version + 1, title = ? WHERE id = ?').run('Không được lưu', demoTasks[0].id);
    receipt(db);
  }).immediate());
  const row = db.prepare('SELECT version, title FROM tasks WHERE id=?').get(demoTasks[0].id);
  assert.equal(row.version, 1); assert.equal(row.title, demoTasks[0].title);
  assert.equal(db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n, 1);
});

test('commit task và receipt cùng thành công', (t) => {
  const { db } = setup(t); migrateDatabase(db); seedDatabase(db);
  db.transaction(() => {
    db.prepare('UPDATE tasks SET version = 2 WHERE id = ?').run(demoTasks[0].id);
    receipt(db);
  }).immediate();
  assert.equal(db.prepare('SELECT version FROM tasks WHERE id=?').get(demoTasks[0].id).version, 2);
  assert.equal(db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n, 1);
});

test('khóa ghi IMMEDIATE chặn connection khác và conditional update bảo vệ version', (t) => {
  const { db, filename } = setup(t); migrateDatabase(db); seedDatabase(db);
  const second = openDatabase(filename); t.after(() => second.close());
  second.pragma('busy_timeout = 0');
  db.transaction(() => {
    assert.throws(() => second.prepare('UPDATE tasks SET version=2 WHERE id=?').run(demoTasks[0].id), { code: 'SQLITE_BUSY' });
    const result = db.prepare('UPDATE tasks SET version=version+1 WHERE id=? AND version=?').run(demoTasks[0].id, 1);
    assert.equal(result.changes, 1);
  }).immediate();
  assert.equal(second.prepare('UPDATE tasks SET version=version+1 WHERE id=? AND version=?').run(demoTasks[0].id, 1).changes, 0);
});

test('reset có bảo vệ và xóa cả task lẫn receipt, giữ migration', (t) => {
  const { db } = setup(t); migrateDatabase(db); seedDatabase(db); receipt(db);
  assert.throws(() => resetDatabase(db, { environment: 'production', confirmed: true }));
  assert.throws(() => resetDatabase(db, { environment: 'development', confirmed: false }));
  assert.equal(db.prepare('SELECT count(*) AS n FROM tasks').get().n, 3);
  resetDatabase(db, { environment: 'test', confirmed: true });
  assert.equal(db.prepare('SELECT count(*) AS n FROM tasks').get().n, 0);
  assert.equal(db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n, 0);
  assert.equal(db.prepare('SELECT count(*) AS n FROM schema_migrations').get().n, 1);
  assert.equal(seedDatabase(db).inserted, 3);
});

test('seed từ chối database có bộ dữ liệu khác hoặc thiếu một phần', (t) => {
  const { db } = setup(t); migrateDatabase(db); seedDatabase(db);
  db.prepare('DELETE FROM tasks WHERE id = ?').run(demoTasks[0].id);
  assert.throws(() => seedDatabase(db), /Database đã có dữ liệu/);
  assert.equal(db.prepare('SELECT count(*) AS n FROM tasks').get().n, 2);
});

test('đường dẫn tương đối tính từ server và không tạo file khi chỉ inspect', (t) => {
  const { directory } = setup(t);
  assert.equal(resolveDatabasePath('./storage/studyflow.db'), new URL('../storage/studyflow.db', import.meta.url).pathname);
  assert.throws(() => resolveDatabasePath(' '));
  assert.throws(() => openDatabase(join(directory, 'missing.db'), { readonly: true }));
});

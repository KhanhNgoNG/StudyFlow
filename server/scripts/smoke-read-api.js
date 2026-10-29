import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';

const serverRoot = fileURLToPath(new URL('../', import.meta.url));
const tasks = JSON.parse(readFileSync(new URL('../../contracts/examples/task-list.json', import.meta.url), 'utf8')).tasks;
const directory = mkdtempSync(join(tmpdir(), 'studyflow-http-smoke-'));
const filename = join(directory, 'smoke.db');
let database;
let child;

function snapshot(db) {
  return {
    tasks: db.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: db.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
    migrations: db.prepare('SELECT * FROM schema_migrations ORDER BY name').all(),
  };
}

async function stopChild() {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, 'exit');
  child.kill('SIGTERM');
  const timer = setTimeout(() => child.kill('SIGKILL'), 5000);
  try { await exited; } finally { clearTimeout(timer); }
}

try {
  database = openDatabase(filename);
  migrateDatabase(database);
  const insert = database.prepare(`INSERT INTO tasks (id, title, description, subject_code,
    due_date, priority, completed, is_deleted, version, server_updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
  for (const task of tasks) {
    insert.run(task.id, task.title, task.description, task.subjectCode, task.dueDate,
      task.priority, Number(task.completed), Number(task.isDeleted), task.version, task.serverUpdatedAt);
  }
  const before = snapshot(database);
  database.close();

  // Ask the OS for a free loopback port; never stop an existing listener.
  const probe = createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));

  child = spawn(process.execPath, ['src/server.js'], {
    cwd: serverRoot,
    env: { ...process.env, NODE_ENV: 'test', HOST: '127.0.0.1', PORT: String(port), DATABASE_PATH: filename },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  await new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error('Smoke server did not become ready')), 10000);
    child.once('error', (error) => { clearTimeout(timer); reject(error); });
    child.once('exit', () => { clearTimeout(timer); reject(new Error(`Smoke server exited: ${output}`)); });
    const receive = (chunk) => {
      output += chunk.toString();
      if (output.includes('StudyFlow server đang chạy')) { clearTimeout(timer); resolve(); }
    };
    child.stdout.on('data', receive);
    child.stderr.on('data', receive);
  });

  async function get(path, status) {
    const response = await fetch(`http://127.0.0.1:${port}${path}`, { signal: AbortSignal.timeout(5000) });
    const text = await response.text();
    assert.equal(response.status, status, path);
    if (text) assert.match(response.headers.get('content-type'), /^application\/json; charset=utf-8$/);
    console.log(`GET ${path} → ${response.status}`);
    return text ? JSON.parse(text) : undefined;
  }

  assert.equal(await get('/api/v1/health', 200), undefined);
  const all = await get('/api/v1/tasks', 200);
  assert.deepEqual(all, { tasks });
  assert.deepEqual(await get('/api/v1/tasks?includeDeleted=true', 200), all);
  assert.deepEqual(await get('/api/v1/tasks?includeDeleted=false', 200), { tasks: tasks.filter((task) => !task.isDeleted) });
  for (const task of all.tasks) assert.deepEqual(await get(`/api/v1/tasks/${task.id}`, 200), { task });
  const missingId = '6a144198-9c2b-49dc-8c56-cf295b92d016';
  assert.equal(all.tasks.some((task) => task.id === missingId), false);
  assert.deepEqual(await get(`/api/v1/tasks/${missingId}`, 404), {
    code: 'TASK_NOT_FOUND', message: 'Task not found', taskId: missingId,
  });
  assert.equal((await get('/api/v1/tasks/not-a-uuid', 422)).code, 'VALIDATION_ERROR');
  for (const query of ['includeDeleted=invalid', 'includeDeleted=true&includeDeleted=false']) {
    assert.ok((await get(`/api/v1/tasks?${query}`, 422)).fieldErrors.includeDeleted);
  }
  assert.deepEqual(await get('/api/v1/tasks/%ZZ', 400), { code: 'BAD_REQUEST', message: 'Invalid URL encoding' });

  await stopChild();
  database = openDatabase(filename, { readonly: true });
  assert.deepEqual(snapshot(database), before);
  assert.equal(database.pragma('integrity_check', { simple: true }), 'ok');
  console.log('Smoke passed: 11 HTTP requests; temporary database unchanged; own server stopped.');
} finally {
  await stopChild();
  if (database?.open) database.close();
  rmSync(directory, { recursive: true, force: true });
}

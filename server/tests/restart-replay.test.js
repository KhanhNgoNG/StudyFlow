import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';

const createBody = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const secondId = '8ba5f9d4-4264-4ed9-8b2b-778ffb052257';
const detail = (id) => `/api/v1/tasks/${id}`;
const mutationKey = (number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;

function readState(filename) {
  const database = openDatabase(filename, { readonly: true });
  try {
    return {
      tasks: database.prepare('SELECT * FROM tasks ORDER BY id').all(),
      receipts: database.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
      migrations: database.prepare('SELECT * FROM schema_migrations ORDER BY name').all(),
      integrity: database.pragma('integrity_check', { simple: true }),
    };
  } finally { database.close(); }
}

async function startProcess(filename, children) {
  const child = fork(new URL('../test-support/http-process.js', import.meta.url), [filename], {
    cwd: fileURLToPath(new URL('../', import.meta.url)),
    env: { ...process.env, NODE_ENV: 'test' },
    execArgv: [],
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  });
  const state = { child, output: '', exited: undefined, stopping: undefined };
  children.push(state); // Register ownership before waiting for startup.
  state.exited = new Promise((resolve) => {
    child.once('exit', (code, signal) => resolve({ code, signal }));
    // A spawn failure can emit error/close without an exit event. Cleanup must
    // still finish, while the readiness listener reports the original error.
    child.on('error', (error) => { if (!child.pid) resolve({ code: null, signal: null, error }); });
  });
  const capture = (chunk) => { state.output = (state.output + chunk.toString()).slice(-8192); };
  child.stdout.on('data', capture);
  child.stderr.on('data', capture);
  const port = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error(`HTTP process did not become ready: ${state.output}`)), 10000);
    function finish(error, value) {
      clearTimeout(timer);
      child.off('message', receive);
      child.off('error', failed);
      child.off('exit', exited);
      if (error) reject(error); else resolve(value);
    }
    function receive(message) {
      if (message?.event === 'ready') {
        if (!Number.isInteger(message.port) || message.port < 1 || message.port > 65535) {
          finish(new Error('HTTP process reported an invalid port'));
        } else finish(undefined, message.port);
      }
    }
    function failed(error) { finish(error); }
    function exited(code, signal) { finish(new Error(`HTTP process exited (${code}/${signal}): ${state.output}`)); }
    child.on('message', receive);
    child.once('error', failed);
    child.once('exit', exited);
  });
  state.send = async (request) => {
    const response = await fetch(`http://127.0.0.1:${port}${request.path}`, {
      method: request.method,
      headers: {
        ...(Object.hasOwn(request, 'body') ? { 'Content-Type': 'application/json' } : {}),
        ...(request.key ? { 'Idempotency-Key': request.key } : {}),
      },
      ...(Object.hasOwn(request, 'body') ? { body: JSON.stringify(request.body) } : {}),
      signal: AbortSignal.timeout(5000),
    });
    assert.match(response.headers.get('content-type'), /^application\/json; charset=utf-8$/);
    return { status: response.status, body: await response.json() };
  };
  return state;
}

function stopProcess(state) {
  if (state.stopping) return state.stopping;
  state.stopping = (async () => {
    const { child } = state;
    if (!child.pid || child.exitCode !== null || child.signalCode !== null) return state.exited;
    const force = setTimeout(() => child.kill('SIGKILL'), 3000);
    let deadline;
    try {
      if (child.connected) child.send({ event: 'shutdown' }, () => {});
      else child.kill('SIGTERM');
      return await Promise.race([
        state.exited,
        new Promise((_, reject) => {
          deadline = setTimeout(() => reject(new Error(`Owned HTTP process did not stop: ${state.output}`)), 6000);
        }),
      ]);
    } finally { clearTimeout(force); clearTimeout(deadline); }
  })();
  return state.stopping;
}

function updateBody(task, overrides) {
  const { version, serverUpdatedAt, ...business } = task;
  return { ...business, baseVersion: version, ...overrides };
}

test('HTTP qua tiến trình mới replay POST/PUT/DELETE/no-op từ receipt bền vững, giữ task sống và tombstone hiện tại', { timeout: 45000 }, async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'studyflow-http-restart-'));
  const filename = join(directory, 'test.db');
  const children = [];
  t.after(async () => {
    const stopped = await Promise.allSettled(children.map(stopProcess));
    rmSync(directory, { recursive: true, force: true });
    for (const result of stopped) if (result.status === 'rejected') throw result.reason;
  });
  const database = openDatabase(filename);
  try { migrateDatabase(database); } finally { database.close(); }
  const initialMigrations = readState(filename).migrations;

  const firstProcess = await startProcess(filename, children);
  const recorded = [];
  let nextKey = 9100;
  async function write(method, path, body, expectedStatus) {
    const request = { method, path, key: mutationKey(nextKey++), ...(body === undefined ? {} : { body }) };
    const result = await firstProcess.send(request);
    assert.equal(result.status, expectedStatus);
    recorded.push({ request, result });
    return result.body.task;
  }

  const first = await write('POST', '/api/v1/tasks', createBody, 201);
  const updated = await write('PUT', detail(first.id), updateBody(first, {
    title: 'Đã sửa trước khi khởi động lại', description: '', dueDate: null, completed: true,
  }), 200);
  const deleted = await write('DELETE', `${detail(first.id)}?baseVersion=${updated.version}`, undefined, 200);
  const noop = await write('DELETE', `${detail(first.id)}?baseVersion=${deleted.version}`, undefined, 200);
  assert.deepEqual(noop, deleted);
  const restored = await write('PUT', detail(first.id), updateBody(deleted, {
    title: 'Bản sống hiện tại phải được giữ', isDeleted: false, completed: false,
  }), 200);
  assert.equal(restored.version, 4);
  const second = await write('POST', '/api/v1/tasks', { ...createBody, id: secondId, title: 'Giữ tombstone qua tiến trình mới' }, 201);
  const tombstone = await write('DELETE', `${detail(secondId)}?baseVersion=${second.version}`, undefined, 200);
  assert.equal(tombstone.version, 2);
  assert.equal(tombstone.isDeleted, true);
  assert.deepEqual(await stopProcess(firstProcess), { code: 0, signal: null });

  const beforeReplay = readState(filename);
  assert.equal(beforeReplay.tasks.length, 2);
  assert.equal(beforeReplay.receipts.length, 7);
  assert.deepEqual(beforeReplay.migrations, initialMigrations);
  assert.equal(beforeReplay.integrity, 'ok');

  // A different OS process creates a new app, connection and executor. No
  // in-memory object from the first server can supply these replay responses.
  const secondProcess = await startProcess(filename, children);
  for (const { request, result } of recorded) {
    assert.deepEqual(await secondProcess.send(request), result);
  }
  assert.deepEqual(await secondProcess.send({ method: 'GET', path: detail(first.id) }), {
    status: 200, body: { task: restored },
  });
  assert.deepEqual(await secondProcess.send({ method: 'GET', path: detail(secondId) }), {
    status: 200, body: { task: tombstone },
  });
  const expectedTasks = [restored, tombstone].sort((left, right) => left.id.localeCompare(right.id));
  assert.deepEqual(await secondProcess.send({ method: 'GET', path: '/api/v1/tasks' }), {
    status: 200, body: { tasks: expectedTasks },
  });
  assert.deepEqual(await secondProcess.send({ method: 'GET', path: '/api/v1/tasks?includeDeleted=false' }), {
    status: 200, body: { tasks: [restored] },
  });
  assert.deepEqual(await stopProcess(secondProcess), { code: 0, signal: null });
  assert.equal(firstProcess.output, '');
  assert.equal(secondProcess.output, '');
  assert.deepEqual(readState(filename), beforeReplay);
});

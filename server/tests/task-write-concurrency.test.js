import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { Worker } from 'node:worker_threads';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { taskResponse } from '../src/http/task-responses.js';
import { createTaskMutationService } from '../src/services/task-mutation-service.js';

const createBody = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const createId = '7fe7b179-6677-43ec-99e2-c0c035a184a8';
const writerId = '345ccf57-5bf5-43d7-9175-45375211bbaa';
const contenderId = '4099f1b3-084d-41ad-96f8-736621763d0d';

function waitForMessage(worker, event) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error(`Write worker did not send ${event}`)), 20000);
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
    function exited(code) { finish(new Error(`Write worker exited (${code}) before ${event}`)); }
    worker.on('message', receive);
    worker.once('error', failed);
    worker.once('exit', exited);
  });
}

async function contend(t, sameMutation) {
  const directory = mkdtempSync(join(tmpdir(), 'studyflow-task-write-race-'));
  const filename = join(directory, 'test.db');
  const gateBuffer = new SharedArrayBuffer(4);
  const gate = new Int32Array(gateBuffer);
  const workers = [];
  let initial;
  t.after(async () => {
    Atomics.store(gate, 0, 1);
    Atomics.notify(gate, 0);
    await Promise.all(workers.map((worker) => worker.terminate()));
    if (initial?.open) initial.close();
    rmSync(directory, { recursive: true, force: true });
  });
  initial = openDatabase(filename);
  migrateDatabase(initial);
  assert.equal(initial.pragma('journal_mode', { simple: true }), 'wal');
  const created = createTaskMutationService(initial).execute({
    method: 'POST', path: '/api/v1/tasks', query: {}, body: createBody, idempotencyKey: createId,
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.task.version, 1);
  initial.close();

  const writerInput = {
    method: 'PUT', path: `/api/v1/tasks/${createBody.id}`, query: {},
    body: { ...createBody, baseVersion: 1, title: 'Thay đổi từ thiết bị A' },
    idempotencyKey: writerId,
  };
  const contenderInput = sameMutation ? structuredClone(writerInput) : {
    ...writerInput, body: { ...writerInput.body, title: 'Thay đổi độc lập từ thiết bị B' }, idempotencyKey: contenderId,
  };
  function start(role, input) {
    const worker = new Worker(new URL('../test-support/task-write-worker.js', import.meta.url), {
      workerData: { role, filename, gateBuffer, input },
    });
    worker.on('error', () => {});
    workers.push(worker);
    return worker;
  }

  const writer = start('writer', writerInput);
  await waitForMessage(writer, 'locked');
  const contender = start('contender', contenderInput);
  const busy = await waitForMessage(contender, 'busy');
  // BUSY is direct evidence that the second connection attempted a write while
  // the first service invocation still owned its real SQLite transaction.
  assert.equal(busy.code, 'SQLITE_BUSY');
  assert.equal(busy.changes, 0);
  assert.equal(busy.inTransaction, false);

  const waitWritten = waitForMessage(writer, 'done');
  Atomics.store(gate, 0, 1);
  Atomics.notify(gate, 0);
  const committed = await waitWritten;
  assert.equal(committed.error, undefined);
  assert.equal(committed.result.status, 200);
  assert.equal(committed.result.replayed, false);
  assert.equal(committed.result.body.task.version, 2);
  assert.equal(committed.result.body.task.title, writerInput.body.title);
  assert.equal(committed.triggerCalls, 1);
  assert.equal(committed.changes, 2); // one task UPDATE and one receipt INSERT
  assert.equal(committed.inTransaction, false);

  const waitRetried = waitForMessage(contender, 'done');
  contender.postMessage('retry');
  const retried = await waitRetried;
  assert.equal(retried.changes, 0);
  assert.equal(retried.inTransaction, false);
  if (sameMutation) {
    assert.equal(retried.error, undefined);
    assert.deepEqual(retried.result, { ...committed.result, replayed: true });
  } else {
    assert.equal(retried.result, undefined);
    assert.equal(retried.error.status, 409);
    assert.equal(retried.error.body.code, 'VERSION_CONFLICT');
    assert.equal(retried.error.body.taskId, createBody.id);
    assert.deepEqual(retried.error.body.currentTask, committed.result.body.task);
  }

  const db = openDatabase(filename, { readonly: true });
  try {
    assert.deepEqual(taskResponse(db.prepare('SELECT * FROM tasks WHERE id = ?').get(createBody.id)), committed.result.body);
    assert.equal(db.prepare('SELECT count(*) AS count FROM tasks').get().count, 1);
    assert.equal(db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count, 2);
    assert.deepEqual(db.prepare('SELECT mutation_id FROM mutation_receipts ORDER BY mutation_id').all(),
      [writerId, createId].sort().map((mutation_id) => ({ mutation_id })));
    assert.equal(db.pragma('integrity_check', { simple: true }), 'ok');
  } finally { db.close(); }
}

test('hai mutation khác key cùng baseVersion cạnh tranh thật: một update thành công, một VERSION_CONFLICT', { timeout: 30000 }, async (t) => {
  await contend(t, false);
});

test('hai request cùng mutation ID cạnh tranh thật: retry replay và task chỉ tăng version một lần', { timeout: 30000 }, async (t) => {
  await contend(t, true);
});

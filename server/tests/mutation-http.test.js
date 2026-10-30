import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import express from 'express';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { taskResponse } from '../src/http/task-responses.js';
import { createErrorHandler } from '../src/middleware/error-handler.js';
import { createMutationExecutor } from '../src/mutations/mutation-executor.js';

const body = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const mutationId = '7fe7b179-6677-43ec-99e2-c0c035a184a8';
const otherMutationId = '345ccf57-5bf5-43d7-9175-45375211bbaa';

function setup(t) {
  const db = openDatabase(':memory:');
  migrateDatabase(db);
  t.after(() => db.close());
  return db;
}

async function serve(t, db, apply) {
  const logs = [];
  const executor = createMutationExecutor(db);
  const app = express();
  app.use(express.json({ strict: false }));
  // Only this test app has a write route. Full business validation belongs inside
  // the new-mutation branch, so reused keys cannot be hidden by a pre-validator.
  app.post('/api/v1/tasks', (req, res) => {
    const result = executor.execute({
      method: req.method,
      path: req.originalUrl.split('?')[0],
      query: req.query,
      body: req.body,
      idempotencyKey: req.get('Idempotency-Key'),
    }, apply);
    // Deliberately lose the response AFTER the executor committed successfully.
    if (req.get('X-Test-Drop-Response') === 'true') res.destroy();
    else res.status(result.status).json(result.body);
  });
  app.use(createErrorHandler({ logger: { error: (...args) => logs.push(args) } }));
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections();
  }));
  return {
    logs,
    send: (requestBody, { key = mutationId, query = '', drop = false } = {}) => fetch(
      `http://127.0.0.1:${server.address().port}/api/v1/tasks${query}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(key === null ? {} : { 'Idempotency-Key': key }),
          ...(drop ? { 'X-Test-Drop-Response': 'true' } : {}),
        },
        body: JSON.stringify(requestBody),
        signal: AbortSignal.timeout(5000),
      },
    ),
  };
}

function insertFixture({ database, request }) {
  const task = request.task;
  database.prepare(`INSERT INTO tasks (id, title, description, subject_code, due_date,
    priority, completed, is_deleted, version, server_updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, '2026-10-29T03:00:00Z')`).run(
    task.id, task.title, task.description, task.subjectCode, task.dueDate, task.priority, Number(task.completed),
  );
  return { status: 201, body: taskResponse(database.prepare('SELECT * FROM tasks WHERE id = ?').get(task.id)) };
}

test('HTTP mất response sau commit: retry nhận đúng envelope cũ dù task đã thành tombstone', async (t) => {
  const db = setup(t);
  let calls = 0;
  const { send, logs } = await serve(t, db, (context) => { calls += 1; return insertFixture(context); });
  const input = { ...body, title: `  ${body.title}  ` };
  await assert.rejects(send(input, { drop: true }));
  assert.equal(db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n, 1);
  assert.equal(calls, 1);
  const saved = JSON.parse(db.prepare('SELECT response_body FROM mutation_receipts').get().response_body);
  assert.equal(saved.task.title, body.title);
  db.prepare(`UPDATE tasks SET title = 'Sửa sau commit', is_deleted = 1,
    version = 2, server_updated_at = '2026-10-29T04:00:00Z' WHERE id = ?`).run(body.id);

  const replay = await send(Object.fromEntries(Object.entries(input).reverse()));
  assert.equal(replay.status, 201);
  assert.match(replay.headers.get('content-type'), /application\/json/);
  assert.deepEqual(await replay.json(), saved);
  assert.equal(calls, 1);
  assert.equal(db.prepare('SELECT version FROM tasks').get().version, 2);
  assert.equal(logs.length, 0);
});

test('HTTP receipt được kiểm tra trước full validation; unknown query không bị lọc khỏi fingerprint', async (t) => {
  const db = setup(t);
  let calls = 0;
  const { send } = await serve(t, db, (context) => { calls += 1; return insertFixture(context); });
  assert.equal((await send(body)).status, 201);
  for (const [input, options] of [
    [{ ...body, title: '' }, {}],
    [body, { query: '?unused=1' }],
    [{ ...body, version: 123, serverUpdatedAt: 'client-value' }, {}],
  ]) {
    const response = await send(input, options);
    assert.equal(response.status, 409);
    assert.deepEqual(await response.json(), {
      code: 'MUTATION_ID_REUSED',
      message: 'Mutation ID was already used for a different request',
      taskId: body.id,
    });
  }
  for (const options of [{ key: null }, { key: 'bad-key' }]) {
    const response = await send(body, options);
    assert.equal(response.status, 422);
    assert.ok((await response.json()).fieldErrors['Idempotency-Key']);
  }
  const newInvalid = await send({ ...body, title: '' }, { key: otherMutationId });
  assert.equal(newInvalid.status, 422);
  assert.ok((await newInvalid.json()).fieldErrors.title);
  const unsupported = await send(body, { key: otherMutationId, query: '?unused=1' });
  assert.equal(unsupported.status, 422);
  assert.ok((await unsupported.json()).fieldErrors.query);
  assert.equal(calls, 1);
  assert.equal(db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n, 1);
});

test('HTTP callback trả response sai: rollback và 500 kín, không biến thành validation hoặc thành công', async (t) => {
  const db = setup(t);
  const { send, logs } = await serve(t, db, (context) => {
    insertFixture(context);
    return { status: 201, body: { task: 'SELECT secret; /private/internal.db' } };
  });
  const response = await send(body);
  assert.equal(response.status, 500);
  assert.deepEqual(await response.json(), { code: 'INTERNAL_ERROR', message: 'Internal server error' });
  assert.equal(db.prepare('SELECT count(*) AS n FROM tasks').get().n, 0);
  assert.equal(db.prepare('SELECT count(*) AS n FROM mutation_receipts').get().n, 0);
  assert.equal(logs.length, 1);
  assert.doesNotMatch(JSON.stringify(logs), /SELECT secret|internal.db/);
});

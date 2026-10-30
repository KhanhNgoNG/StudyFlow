import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import { createServer } from 'node:http';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { isUuid } from '../src/validation/task-fields.js';
import { example, snapshot } from '../test-support/http-fixture.js';

const body = example('create-task-request');
const collection = '/api/v1/tasks';
const detail = `${collection}/${body.id}`;
const otherId = '8ba5f9d4-4264-4ed9-8b2b-778ffb052257';
const key = (number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;

function captureLogs(observe = () => {}) {
  const records = [];
  const received = new EventEmitter();
  function write(event, record) {
    records.push({ ...record, event });
    observe(event, record);
    received.emit('record');
  }
  return {
    records,
    logger: { info: write, error: write },
    waitFor(predicate) {
      const found = records.find(predicate);
      if (found) return Promise.resolve(found);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => finish(new Error('Expected log event was not emitted')), 5000);
        function finish(error, record) {
          clearTimeout(timer);
          received.off('record', check);
          if (error) reject(error); else resolve(record);
        }
        function check() {
          const record = records.find(predicate);
          if (record) finish(undefined, record);
        }
        received.on('record', check);
      });
    },
  };
}

async function fixture(t, logger, { dropFirstPost = false } = {}) {
  let database;
  let server;
  t.after(async () => {
    try {
      if (server?.listening) await new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
        server.closeAllConnections();
      });
    } finally { if (database?.open) database.close(); }
  });
  database = openDatabase(':memory:');
  migrateDatabase(database);
  const app = createApp({ database, logger });
  let drop = dropFirstPost;
  server = createServer((req, res) => {
    if (drop && req.method === 'POST' && req.url === collection) {
      const end = res.end;
      res.end = function (...args) {
        if (drop && this.statusCode === 201) {
          drop = false;
          // Test-only transport wrapper: the real controller/executor has
          // finished committing before it attempts to send this success body.
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
  return {
    database,
    async request(method, path, mutationId, payload, extraHeaders = {}) {
      const response = await fetch(`http://127.0.0.1:${server.address().port}${path}`, {
        method,
        headers: {
          ...(payload === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(mutationId === undefined ? {} : { 'Idempotency-Key': mutationId }),
          ...extraHeaders,
        },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
        signal: AbortSignal.timeout(5000),
      });
      const text = await response.text();
      if (text) assert.match(response.headers.get('content-type'), /^application\/json; charset=utf-8$/);
      return { response, body: text ? JSON.parse(text) : undefined };
    },
  };
}

function requestId(result) {
  const id = result.response.headers.get('x-request-id');
  assert.ok(isUuid(id), 'Server supplies a valid, optional request correlation ID');
  return id;
}

function recordsFor(capture, result, event) {
  return capture.records.filter((record) => record.requestId === requestId(result) && record.event === event);
}

function assertTimed(record) {
  assert.match(record.time, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  assert.ok(Number.isFinite(Date.parse(record.time)));
  assert.ok(Number.isFinite(record.durationMs) && record.durationMs >= 0);
}

test('log applied/replay/no-op phát ra sau commit, mỗi HTTP request có ID riêng nhưng giữ mutation ID', { timeout: 15000 }, async (t) => {
  const observed = [];
  let database;
  const capture = captureLogs((event, record) => {
    if (event === 'mutation.completed') observed.push({
      outcome: record.outcome, inTransaction: database.inTransaction,
      receipt: database.prepare('SELECT response_status FROM mutation_receipts WHERE mutation_id = ?').get(key(1)),
    });
  });
  const context = await fixture(t, capture.logger);
  database = context.database;
  const first = await context.request('POST', collection, key(1), body, { 'X-Request-ID': key(999) });
  assert.equal(first.response.status, 201);
  const before = snapshot(database);
  const replay = await context.request('POST', collection, key(1), body);
  assert.equal(replay.response.status, 201);
  assert.deepEqual(replay.body, first.body);
  assert.deepEqual(snapshot(database), before);
  assert.notEqual(requestId(first), requestId(replay));
  assert.notEqual(requestId(first), key(999));
  const completed = capture.records.filter((record) => record.event === 'mutation.completed');
  assert.equal(completed.length, 2);
  assert.deepEqual(completed.map((record) => record.outcome), ['applied', 'replay']);
  assert.deepEqual(completed.map((record) => record.taskChanged), [true, false]);
  assert.deepEqual(completed.map((record) => record.replayed), [false, true]);
  for (const record of completed) {
    assert.equal(record.mutationId, key(1));
    assert.equal(record.taskId, body.id);
    assert.equal(record.method, 'POST');
    assert.equal(record.status, 201);
    assert.equal(record.transaction, 'committed');
    assert.equal(record.level, 'info');
    assertTimed(record);
  }
  assert.deepEqual(observed, [
    { outcome: 'applied', inTransaction: false, receipt: { response_status: 201 } },
    { outcome: 'replay', inTransaction: false, receipt: { response_status: 201 } },
  ]);
  for (const result of [first, replay]) {
    const finish = await capture.waitFor((record) => record.event === 'request.finished' && record.requestId === requestId(result));
    assert.equal(finish.transport, 'server_finished');
    assert.equal(finish.status, 201);
    assert.equal(finish.transaction, 'committed');
    assertTimed(finish);
  }
  const deleted = await context.request('DELETE', `${detail}?baseVersion=1`, key(2));
  assert.equal(deleted.response.status, 200);
  const beforeNoop = snapshot(database);
  const noop = await context.request('DELETE', `${detail}?baseVersion=2`, key(3));
  assert.equal(noop.response.status, 200);
  assert.deepEqual(noop.body, deleted.body);
  assert.deepEqual(snapshot(database).tasks, beforeNoop.tasks);
  assert.equal(snapshot(database).receipts.length, beforeNoop.receipts.length + 1);
  const accepted = recordsFor(capture, noop, 'mutation.completed');
  assert.equal(accepted.length, 1);
  assert.equal(accepted[0].outcome, 'accepted_noop');
  assert.equal(accepted[0].taskChanged, false);
  assert.equal(accepted[0].replayed, false);
  assert.equal(accepted[0].transaction, 'committed');
  const beforeNoopReplay = snapshot(database);
  const noopReplay = await context.request('DELETE', `${detail}?baseVersion=2`, key(3));
  assert.equal(noopReplay.response.status, 200);
  assert.deepEqual(noopReplay.body, noop.body);
  assert.deepEqual(snapshot(database), beforeNoopReplay);
  const replayedNoop = recordsFor(capture, noopReplay, 'mutation.completed');
  assert.equal(replayedNoop.length, 1);
  assert.equal(replayedNoop[0].outcome, 'replay');
  assert.equal(replayedNoop[0].taskChanged, false);
  assert.equal(replayedNoop[0].replayed, true);
});

test('log phân loại lỗi nghiệp vụ và lỗi SQLite thật, không ghi commit khi task/receipt rollback', { timeout: 20000 }, async (t) => {
  const capture = captureLogs();
  const context = await fixture(t, capture.logger);
  const { database } = context;
  assert.equal((await context.request('POST', collection, key(10), body)).response.status, 201);
  const initial = snapshot(database);
  const failures = [
    ['PUT', detail, key(11), { ...body, baseVersion: 2 }, 409, 'version_conflict'],
    ['POST', collection, key(10), { ...body, title: '' }, 409, 'mutation_id_reused'],
    ['PUT', detail, key(12), { ...body, baseVersion: 1, completed: 'false' }, 422, 'validation_error'],
    ['PUT', `${collection}/${otherId}`, key(13), { ...body, id: otherId, baseVersion: 1 }, 404, 'not_found'],
  ];
  const failedResults = [];
  for (const [method, path, mutationId, payload, status, outcome] of failures) {
    const result = await context.request(method, path, mutationId, payload);
    assert.equal(result.response.status, status);
    failedResults.push([result, outcome]);
    assert.deepEqual(snapshot(database), initial);
  }
  const update = { ...body, baseVersion: 1, title: 'Retry nguyên snapshot sau lỗi trước commit' };
  database.exec(`CREATE TEMP TRIGGER reject_logging_receipt BEFORE INSERT ON mutation_receipts
    BEGIN SELECT RAISE(ABORT, 'secret SQL /private/internal.db'); END;`);
  const insertFailure = await context.request('PUT', detail, key(14), update);
  assert.equal(insertFailure.response.status, 500);
  failedResults.push([insertFailure, 'temporary_error']);
  assert.deepEqual(snapshot(database), initial);
  database.exec('DROP TRIGGER reject_logging_receipt');
  database.exec(`CREATE TABLE logging_commit_guard (task_id TEXT REFERENCES tasks(id) DEFERRABLE INITIALLY DEFERRED);
    CREATE TEMP TRIGGER reject_logging_commit AFTER INSERT ON mutation_receipts BEGIN
      INSERT INTO logging_commit_guard (task_id) VALUES ('${otherId}'); END;`);
  const commitFailure = await context.request('PUT', detail, key(14), update);
  assert.equal(commitFailure.response.status, 500);
  failedResults.push([commitFailure, 'temporary_error']);
  assert.deepEqual(snapshot(database), initial);
  assert.equal(database.prepare('SELECT count(*) AS count FROM logging_commit_guard').get().count, 0);
  database.exec('DROP TRIGGER reject_logging_commit');
  for (const [result, outcome] of failedResults) {
    const failed = recordsFor(capture, result, 'mutation.failed');
    assert.equal(failed.length, 1);
    assert.equal(failed[0].outcome, outcome);
    assert.equal(failed[0].transaction, 'not_committed');
    assert.equal(failed[0].status, result.response.status);
    assertTimed(failed[0]);
    assert.equal(recordsFor(capture, result, 'mutation.completed').length, 0);
  }
  const retried = await context.request('PUT', detail, key(14), update);
  assert.equal(retried.response.status, 200);
  assert.equal(retried.body.task.version, 2);
  assert.equal(recordsFor(capture, retried, 'mutation.completed')[0].outcome, 'applied');
  assert.equal(snapshot(database).receipts.length, 2);
});

test('mất response sau commit ghi connection_closed, không suy ra rollback; retry replay snapshot cũ', { timeout: 15000 }, async (t) => {
  const capture = captureLogs();
  const context = await fixture(t, capture.logger, { dropFirstPost: true });
  await assert.rejects(context.request('POST', collection, key(20), body));
  const closed = await capture.waitFor((record) => record.event === 'request.closed');
  assert.equal(closed.transport, 'connection_closed');
  assert.equal(closed.transaction, 'committed');
  assert.equal(closed.outcome, 'applied');
  assertTimed(closed);
  assert.equal(capture.records.filter((record) => record.event === 'request.finished' && record.requestId === closed.requestId).length, 0);
  assert.equal(capture.records.filter((record) => record.event === 'mutation.failed' && record.requestId === closed.requestId).length, 0);
  const saved = JSON.parse(context.database.prepare('SELECT response_body FROM mutation_receipts WHERE mutation_id = ?').get(key(20)).response_body);
  const changed = await context.request('PUT', detail, key(21), { ...body, baseVersion: 1, title: 'Task đã được sửa sau mất phản hồi' });
  assert.equal(changed.response.status, 200);
  const beforeReplay = snapshot(context.database);
  const retried = await context.request('POST', collection, key(20), body);
  assert.equal(retried.response.status, 201);
  assert.deepEqual(retried.body, saved);
  assert.equal(retried.body.task.version, 1);
  assert.deepEqual(snapshot(context.database), beforeReplay);
  assert.equal(beforeReplay.tasks[0].version, 2);
  assert.notEqual(requestId(retried), closed.requestId);
  assert.equal(recordsFor(capture, retried, 'mutation.completed')[0].outcome, 'replay');
});

test('sink logging ném lỗi hoặc reject không đổi thành công, replay, rollback hoặc health response', { timeout: 25000 }, async (t) => {
  for (const mode of ['throw', 'reject', 'getter']) {
    let calls = 0;
    const fail = () => {
      calls += 1;
      if (mode === 'reject') return Promise.reject(new Error('Log sink unavailable'));
      throw new Error('Log sink unavailable');
    };
    const logger = mode === 'getter' ? Object.defineProperties({}, {
      info: { get: fail }, error: { get: fail },
    }) : { info: fail, error: fail };
    const context = await fixture(t, logger);
    const first = await context.request('POST', collection, key(30), body);
    assert.equal(first.response.status, 201, mode);
    const before = snapshot(context.database);
    const replay = await context.request('POST', collection, key(30), body);
    assert.equal(replay.response.status, 201, mode);
    assert.deepEqual(replay.body, first.body);
    assert.deepEqual(snapshot(context.database), before);
    context.database.exec(`CREATE TEMP TRIGGER reject_sink_receipt BEFORE INSERT ON mutation_receipts
      BEGIN SELECT RAISE(ABORT, 'receipt failure'); END;`);
    const failed = await context.request('PUT', detail, key(31), { ...body, baseVersion: 1, title: 'Must roll back' });
    assert.equal(failed.response.status, 500, mode);
    assert.deepEqual(failed.body, { code: 'INTERNAL_ERROR', message: 'Internal server error' });
    assert.deepEqual(snapshot(context.database), before);
    context.database.close();
    const health = await context.request('GET', '/api/v1/health');
    assert.equal(health.response.status, 503, mode);
    assert.equal(health.body, undefined);
    assert.ok(calls > 0, `The ${mode} sink was actually called`);
    // Flush already-rejected logging promises; never wait here to guess commit.
    await new Promise((resolve) => setImmediate(resolve));
  }
});

test('log không lộ payload/query/header/path không hợp lệ; fault header không kích hoạt lỗi production', { timeout: 15000 }, async (t) => {
  const capture = captureLogs();
  const context = await fixture(t, capture.logger);
  const privateBody = { ...body, title: 'secret-title\n{"event":"forged"}', description: 'secret-description', localRevision: 991234 };
  const result = await context.request('POST', collection, key(40), privateBody, {
    'X-Request-ID': 'caller-correlation-secret',
    'X-Test-Drop-Response': 'true', 'X-Fault': 'drop',
  });
  assert.equal(result.response.status, 201);
  assert.equal(result.body.task.title, privateBody.title);
  assert.ok(isUuid(requestId(result)));
  const listed = await context.request('GET', '/api/v1/tasks?includeDeleted=true&fault=drop&token=secret-query');
  assert.equal(listed.response.status, 200);
  assert.equal(listed.body.tasks.length, 1);
  const invalidId = 'secret-invalid-path\n{"event":"forged"}';
  assert.equal((await context.request('GET', `${collection}/${encodeURIComponent(invalidId)}?secret=secret-query`)).response.status, 422);
  const invalidKey = await context.request('POST', collection, 'secret-invalid-key', body);
  assert.equal(invalidKey.response.status, 422);
  await capture.waitFor((record) => record.event === 'request.finished' && record.requestId === requestId(invalidKey));
  const serialized = capture.records.map((record) => JSON.stringify(record)).join('\n');
  assert.doesNotMatch(serialized, /secret-title|secret-description|secret-query|secret-invalid-path|secret-invalid-key|caller-correlation-secret|991234|"forged"|\/Users\//);
  for (const line of serialized.split('\n')) {
    const record = JSON.parse(line);
    assert.ok(isUuid(record.requestId));
    if (record.mutationId !== undefined) assert.ok(isUuid(record.mutationId));
    if (record.taskId !== undefined) assert.ok(isUuid(record.taskId));
    assert.equal(Object.hasOwn(record, 'body'), false);
    assert.equal(Object.hasOwn(record, 'query'), false);
  }
  assert.equal(snapshot(context.database).tasks.length, 1);
  assert.equal(snapshot(context.database).receipts.length, 1);
});

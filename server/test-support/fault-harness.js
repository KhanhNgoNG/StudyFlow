import assert from 'node:assert/strict';
import { fork } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';

export const faultScenarioNames = Object.freeze([
  'unavailable', 'timeout-before-commit', 'transient-before-commit',
  'timeout-after-commit', 'lost-response',
]);

const createFixture = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const uuid = (number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;
const serverRoot = fileURLToPath(new URL('../', import.meta.url));

function bounded(promise, milliseconds, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(label)), milliseconds);
      timer.unref();
    }),
  ]).finally(() => clearTimeout(timer));
}

function stateOf(database, taskId) {
  const task = database.prepare('SELECT * FROM tasks WHERE id = ?').get(taskId);
  return {
    tasks: database.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: database.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
    task,
  };
}

function summary(state) {
  return {
    tasks: state.tasks.length, receipts: state.receipts.length,
    task: state.task ? {
      id: state.task.id, version: state.task.version,
      serverUpdatedAt: state.task.server_updated_at, isDeleted: Boolean(state.task.is_deleted),
    } : null,
  };
}

function immutablePost(index) {
  return Object.freeze({
    method: 'POST', path: '/api/v1/tasks', key: uuid(10200 + index), taskId: uuid(10100 + index),
    body: JSON.stringify({ ...createFixture, id: uuid(10100 + index), title: 'Kiểm thử lỗi có kiểm soát' }),
  });
}

function attemptSummary(result, mutationId, outcome) {
  return {
    requestId: result.requestId ?? null, mutationId,
    httpStatus: result.kind === 'response' ? result.status : null,
    clientError: result.kind === 'error' ? result.code : null,
    outcome,
  };
}

function assertResponse(result, status) {
  assert.equal(result.kind, 'response', `Expected HTTP ${status}, observed ${result.code ?? result.kind}`);
  assert.equal(result.status, status);
  assert.match(result.contentType ?? '', /^application\/json; charset=utf-8$/);
  assert.match(result.requestId ?? '', /^[0-9a-f-]{36}$/i, 'Production request ID must be present');
}

function assertClientError(result, code) {
  assert.equal(result.kind, 'error', 'Client must not receive a success response');
  if (code) assert.equal(result.code, code);
  assert.equal(result.status, undefined, 'A connection error or timeout is not an HTTP status');
}

// This JavaScript API is test tooling only. No option is read by production
// app/server code or controlled through incoming HTTP headers/query parameters.
export async function runFaultScenarios({ scenario = 'all', report = () => {}, onCheckpoint } = {}) {
  assert.ok(scenario === 'all' || faultScenarioNames.includes(scenario), `Unknown scenario: ${scenario}`);
  assert.equal(typeof report, 'function', 'report must be a function');
  assert.ok(onCheckpoint === undefined || typeof onCheckpoint === 'function', 'onCheckpoint must be a function');

  const directory = mkdtempSync(join(tmpdir(), 'studyflow-faults-'));
  const filename = join(directory, 'faults.db');
  const children = [];
  const proxies = [];
  const clients = new Set();
  const results = [];
  let database;
  let backend;
  let active = true;
  let currentScenario;
  let failure;
  let cleanup;

  function ensureActive() {
    if (!active) throw new Error('Fault harness is shutting down');
  }

  function emit(event, details = {}) {
    report({ time: new Date().toISOString(), level: 'info', event, ...details });
  }

  function flushLogs() {
    for (const state of children) {
      while (state.flushed < state.logs.length) {
        report({ source: 'backend', ...state.logs[state.flushed++] });
      }
    }
  }

  async function startBackend() {
    ensureActive();
    const child = fork(new URL('./http-process.js', import.meta.url), [filename], {
      cwd: serverRoot, env: { ...process.env, NODE_ENV: 'test' }, execArgv: [],
      stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
    });
    const state = { child, logs: [], flushed: 0, output: '', events: new EventEmitter() };
    children.push(state);
    state.exited = new Promise((resolve) => {
      child.once('exit', (code, signal) => { state.exit = { code, signal }; resolve(state.exit); });
      child.on('error', (error) => {
        if (!child.pid) { state.exit = { code: null, signal: null, error }; resolve(state.exit); }
      });
    });
    const capture = (chunk) => { state.output = (state.output + chunk.toString()).slice(-4096); };
    child.stdout.on('data', capture);
    child.stderr.on('data', capture);
    child.on('message', (message) => {
      if (message?.event === 'log') {
        state.logs.push(message.record);
        state.events.emit('record', message.record);
      }
    });
    state.port = await bounded(new Promise((resolve, reject) => {
      function dispose() { child.off('message', receive); child.off('error', failed); child.off('exit', exited); }
      function receive(message) {
        if (message?.event !== 'ready') return;
        dispose();
        if (Number.isInteger(message.port) && message.port > 0 && message.port < 65536) resolve(message.port);
        else reject(new Error('Owned backend reported an invalid port'));
      }
      function failed(error) { dispose(); reject(error); }
      function exited() { dispose(); reject(new Error('Owned backend exited before readiness')); }
      child.on('message', receive); child.once('error', failed); child.once('exit', exited);
    }), 8000, 'Owned backend startup timed out');
    state.url = `http://127.0.0.1:${state.port}`;
    return state;
  }

  function stopBackend(state) {
    if (state.stopping) return state.stopping;
    state.stopping = (async () => {
      if (!state.child.pid || state.exit) return state.exited;
      const force = setTimeout(() => state.child.kill('SIGKILL'), 2000);
      try {
        if (state.child.connected) state.child.send({ event: 'shutdown' }, () => {});
        else state.child.kill('SIGTERM');
        return await bounded(state.exited, 5000, 'Owned backend did not stop');
      } finally { clearTimeout(force); }
    })();
    return state.stopping;
  }

  async function mutationLog(state, requestId, outcome) {
    const match = (record) => record.requestId === requestId
      && record.event === 'mutation.completed' && record.outcome === outcome;
    const existing = state.logs.find(match);
    if (existing) return existing;
    let receive;
    try {
      return await bounded(new Promise((resolve) => {
        receive = (record) => { if (match(record)) resolve(record); };
        state.events.on('record', receive);
      }), 3000, `Missing correlated mutation log: ${outcome}`);
    } finally { state.events.off('record', receive); }
  }

  function startAttempt(url, input) {
    ensureActive();
    let timer;
    let timeout;
    let finish;
    const result = new Promise((resolve) => { finish = resolve; });
    let settled = false;
    let observedId;
    const req = httpRequest(url + input.path, {
      method: input.method,
      headers: {
        'Content-Type': 'application/json', 'Idempotency-Key': input.key,
        'Content-Length': Buffer.byteLength(input.body), Connection: 'close',
      },
    });
    clients.add(req);
    function done(value) {
      if (settled) return;
      settled = true; clearTimeout(timer); clearTimeout(timeout); clients.delete(req); finish(value);
    }
    function expire(code) {
      const error = new Error('Client deadline reached'); error.code = code; req.destroy(error);
    }
    // The safety deadline is deliberately different from the scenario timeout:
    // it must never count as proof that the intended gate was reached first.
    timer = setTimeout(() => expire('CLIENT_DEADLINE'), 5000);
    req.on('response', (res) => {
      observedId = res.headers['x-request-id'];
      const bytes = [];
      res.on('data', (chunk) => bytes.push(chunk));
      res.on('error', (error) => done({ kind: 'error', code: error.code ?? 'RESPONSE_ERROR', requestId: observedId }));
      res.on('end', () => {
        try {
          done({ kind: 'response', status: res.statusCode, requestId: observedId,
            contentType: res.headers['content-type'], body: JSON.parse(Buffer.concat(bytes).toString('utf8')) });
        } catch { done({ kind: 'error', code: 'INVALID_JSON_RESPONSE', requestId: observedId }); }
      });
    });
    req.on('error', (error) => done({ kind: 'error', code: error.code ?? 'CONNECTION_ERROR', requestId: observedId }));
    req.end(input.body);
    return {
      result,
      // Arm after the proxy confirms the intended phase, avoiding guesses about
      // whether SQLite has committed while still enforcing a finite timeout.
      timeoutAfter(milliseconds) { timeout = setTimeout(() => expire('CLIENT_TIMEOUT'), milliseconds); },
    };
  }

  async function createProxy(mode) {
    ensureActive();
    const sockets = new Set();
    const upstreams = new Set();
    let reached;
    let rejected;
    const gate = new Promise((resolve, reject) => { reached = resolve; rejected = reject; });
    // Gate rejection is also consumed by the scenario; avoid an early unhandled
    // rejection if a client/server fails before the await is attached.
    gate.catch(() => {});
    let used = false;
    const server = createServer((req, res) => {
      if (used) { res.destroy(); return; }
      used = true;
      if (mode === 'before') {
        req.resume();
        reached({ phase: 'before-dispatch', requestId: null });
        return; // Deliberately never forwards this timed-out request.
      }
      const upstream = httpRequest(backend.url + req.url, {
        method: req.method, headers: { ...req.headers, host: `127.0.0.1:${backend.port}`, connection: 'close' },
      }, (response) => {
        const bytes = [];
        response.on('data', (chunk) => bytes.push(chunk));
        response.on('error', rejected);
        response.on('end', () => {
          upstreams.delete(upstream);
          try {
            const observed = {
              phase: 'upstream-response-complete', status: response.statusCode,
              requestId: response.headers['x-request-id'],
              contentType: response.headers['content-type'],
              body: JSON.parse(Buffer.concat(bytes).toString('utf8')),
            };
            reached(observed);
            // Neither mode sends any downstream header/body. A completed
            // upstream HTTP response proves processing ended, not client delivery.
            if (mode === 'drop') res.destroy();
          } catch (error) { rejected(error); res.destroy(); }
        });
      });
      upstreams.add(upstream);
      upstream.on('error', (error) => { upstreams.delete(upstream); rejected(error); res.destroy(); });
      req.pipe(upstream);
    });
    const state = { server, sockets, upstreams, gate };
    proxies.push(state);
    server.on('connection', (socket) => {
      sockets.add(socket); socket.on('close', () => sockets.delete(socket));
    });
    state.close = async () => {
      state.closing = true;
      for (const upstream of upstreams) upstream.destroy();
      for (const socket of sockets) socket.destroy();
      if (server.listening) {
        await bounded(new Promise((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve()); server.closeAllConnections();
        }), 3000, 'Fault proxy did not stop');
      }
    };
    // Register cleanup before startup can fail. If a late listening event ever
    // arrives after a startup deadline, immediately close this owned listener.
    server.on('listening', () => { if (state.closing) { server.closeAllConnections(); server.close(); } });
    await bounded(new Promise((resolve, reject) => {
      server.once('error', reject); server.listen(0, '127.0.0.1', resolve);
    }), 3000, 'Fault proxy startup timed out');
    state.port = server.address().port;
    state.url = `http://127.0.0.1:${state.port}`;
    return state;
  }

  function receipt(input) {
    const saved = database.prepare('SELECT * FROM mutation_receipts WHERE mutation_id = ?').get(input.key);
    assert.ok(saved, 'Successful mutation must have a durable receipt');
    return { status: saved.response_status, body: JSON.parse(saved.response_body) };
  }

  async function replay(input, expected, before) {
    const result = await startAttempt(backend.url, input).result;
    assertResponse(result, expected.status);
    assert.deepEqual(result.body, expected.body, 'Replay must return the saved snapshot, not the current task');
    const log = await mutationLog(backend, result.requestId, 'replay');
    assert.equal(log.transaction, 'committed');
    assert.deepEqual(stateOf(database, input.taskId), before, 'Retry must not change any task/receipt');
    return result;
  }

  async function scenarioRun(name) {
    ensureActive();
    currentScenario = name;
    emit('scenario.started', { scenario: name });
    const input = immutablePost(faultScenarioNames.indexOf(name));
    const before = stateOf(database, input.taskId);
    let first;
    let upstream;
    let proxy;
    const attempts = [];
    let expected;
    let beforeRetry;
    let afterFault;
    let retry;

    if (name === 'unavailable') {
      first = await startAttempt(backend.url, input).result;
      assertResponse(first, 201);
      await mutationLog(backend, first.requestId, 'applied');
      expected = receipt(input);
      const committed = stateOf(database, input.taskId);
      assert.deepEqual(await stopBackend(backend), { code: 0, signal: null });
      const unavailable = await startAttempt(backend.url, input).result;
      assertClientError(unavailable, 'ECONNREFUSED');
      assert.deepEqual(stateOf(database, input.taskId), committed);
      afterFault = committed;
      attempts.push(attemptSummary(first, input.key, 'applied'), attemptSummary(unavailable, input.key, 'not_connected'));
      backend = await startBackend();
      beforeRetry = stateOf(database, input.taskId);
      retry = await replay(input, expected, beforeRetry);
    } else if (name === 'timeout-before-commit') {
      proxy = await createProxy('before');
      const pending = startAttempt(proxy.url, input);
      await bounded(proxy.gate, 3000, 'Before-dispatch gate not reached');
      assert.deepEqual(stateOf(database, input.taskId), before);
      pending.timeoutAfter(100);
      first = await pending.result;
      assertClientError(first, 'CLIENT_TIMEOUT');
      assert.equal(first.requestId, undefined, 'No production handler ran for this held request');
      assert.deepEqual(stateOf(database, input.taskId), before);
      await proxy.close();
      afterFault = stateOf(database, input.taskId);
      attempts.push(attemptSummary(first, input.key, 'not_dispatched'));
      beforeRetry = stateOf(database, input.taskId);
      retry = await startAttempt(backend.url, input).result;
      assertResponse(retry, 201);
      await mutationLog(backend, retry.requestId, 'applied');
      expected = receipt(input);
      assert.deepEqual(retry.body, expected.body);
    } else if (name === 'transient-before-commit') {
      database.exec(`CREATE TRIGGER harness_reject_receipt BEFORE INSERT ON mutation_receipts
        BEGIN SELECT RAISE(ABORT, 'controlled temporary receipt failure'); END;`);
      try { first = await startAttempt(backend.url, input).result; }
      finally { database.exec('DROP TRIGGER harness_reject_receipt'); }
      assertResponse(first, 500);
      assert.deepEqual(first.body, { code: 'INTERNAL_ERROR', message: 'Internal server error' });
      assert.deepEqual(stateOf(database, input.taskId), before, 'Receipt failure must roll back the inserted task');
      afterFault = stateOf(database, input.taskId);
      attempts.push(attemptSummary(first, input.key, 'temporary_error_rolled_back'));
      beforeRetry = stateOf(database, input.taskId);
      retry = await startAttempt(backend.url, input).result;
      assertResponse(retry, 201);
      await mutationLog(backend, retry.requestId, 'applied');
      expected = receipt(input);
      assert.deepEqual(retry.body, expected.body);
    } else {
      proxy = await createProxy(name === 'lost-response' ? 'drop' : 'after');
      const pending = startAttempt(proxy.url, input);
      upstream = await bounded(proxy.gate, 5000, 'Complete upstream response not observed');
      assert.equal(upstream.status, 201);
      assert.match(upstream.requestId ?? '', /^[0-9a-f-]{36}$/i);
      await mutationLog(backend, upstream.requestId, 'applied');
      expected = receipt(input);
      assert.deepEqual(upstream.body, expected.body);
      const committed = stateOf(database, input.taskId);
      assert.equal(committed.task.version, 1);
      if (name === 'timeout-after-commit') {
        await onCheckpoint?.({
          phase: 'upstream-held', directory, childPid: backend.child.pid,
          ports: [backend.port, proxy.port],
        });
        pending.timeoutAfter(100);
      }
      first = await pending.result;
      assertClientError(first, name === 'timeout-after-commit' ? 'CLIENT_TIMEOUT' : 'ECONNRESET');
      assert.equal(first.requestId, undefined, 'Proxy must send no downstream success header/body');
      attempts.push({ ...attemptSummary(first, input.key, 'committed_response_not_received'), upstreamRequestId: upstream.requestId });
      assert.deepEqual(stateOf(database, input.taskId), committed);
      afterFault = committed;
      await proxy.close();
      if (name === 'lost-response') {
        const update = Object.freeze({
          method: 'PUT', path: `/api/v1/tasks/${input.taskId}`, key: uuid(10300), taskId: input.taskId,
          body: JSON.stringify({ ...JSON.parse(input.body), baseVersion: 1, title: 'Thay đổi mới sau response bị mất' }),
        });
        const changed = await startAttempt(backend.url, update).result;
        assertResponse(changed, 200);
        assert.equal(changed.body.task.version, 2);
        await mutationLog(backend, changed.requestId, 'applied');
        attempts.push(attemptSummary(changed, update.key, 'applied_new_mutation'));
      }
      beforeRetry = stateOf(database, input.taskId);
      retry = await replay(input, expected, beforeRetry);
    }

    const after = stateOf(database, input.taskId);
    assert.equal(after.task.version, name === 'lost-response' ? 2 : 1);
    assert.equal(after.tasks.length, before.tasks.length + 1);
    assert.equal(after.receipts.length, before.receipts.length + (name === 'lost-response' ? 2 : 1));
    assert.equal(database.pragma('integrity_check', { simple: true }), 'ok');
    const initialId = upstream?.requestId ?? first?.requestId;
    if (initialId) assert.notEqual(retry.requestId, initialId, 'Retry must have a new HTTP request ID');
    const replayed = ['unavailable', 'timeout-after-commit', 'lost-response'].includes(name);
    const appliedForMutation = children.flatMap((state) => state.logs)
      .filter((record) => record.event === 'mutation.completed' && record.mutationId === input.key && record.outcome === 'applied').length;
    assert.equal(appliedForMutation, 1, 'Original mutation must be applied exactly once across attempts');
    attempts.push(attemptSummary(retry, input.key, replayed ? 'replay' : 'applied_after_unconfirmed_attempt'));
    const result = {
      name, status: 'pass', mutationId: input.key, attempts,
      before: summary(before), afterFault: summary(afterFault), beforeRetry: summary(beforeRetry), after: summary(after),
      appliedForMutation,
      retry: {
        requestId: retry.requestId, httpStatus: retry.status, outcome: replayed ? 'replay' : 'applied',
        responseVersion: retry.body.task.version, responseTimestamp: retry.body.task.serverUpdatedAt,
        currentVersion: after.task.version, currentTimestamp: after.task.server_updated_at,
        ...(replayed ? { receiptCountUnchanged: after.receipts.length === beforeRetry.receipts.length } : {}),
      },
    };
    flushLogs();
    results.push(result);
    emit('scenario.passed', { scenario: name, ...result });
  }

  try {
    await bounded((async () => {
      database = openDatabase(filename);
      migrateDatabase(database);
      backend = await startBackend();
      await onCheckpoint?.({ phase: 'ready', directory, childPid: backend.child.pid, ports: [backend.port] });
      for (const name of scenario === 'all' ? faultScenarioNames : [scenario]) await scenarioRun(name);
    })(), 30000, 'Total fault scenario deadline exceeded');
    flushLogs();
  } catch (error) {
    failure = error;
    if (currentScenario) failure.scenario = currentScenario;
  } finally {
    active = false;
    for (const req of clients) req.destroy();
    const proxyResults = await Promise.allSettled(proxies.map((state) => state.close?.()));
    const childResults = await Promise.allSettled(children.map(stopBackend));
    if (database?.open) database.close();
    rmSync(directory, { recursive: true, force: true });
    cleanup = {
      childrenStopped: children.every((state) => state.exit !== undefined),
      proxyClosed: proxies.every((state) => !state.server.listening),
      connectionsClosed: !database?.open,
      directoryRemoved: !existsSync(directory),
    };
    const rejected = [...proxyResults, ...childResults].find((result) => result.status === 'rejected');
    if (!failure && rejected) failure = rejected.reason;
  }
  if (failure) { failure.cleanup = cleanup; throw failure; }
  assert.ok(Object.values(cleanup).every(Boolean), 'Fault harness cleanup incomplete');
  try { flushLogs(); emit('run.passed', { scenarios: results.length, cleanup }); }
  catch (error) { error.cleanup = cleanup; throw error; }
  return { scenarios: results, cleanup };
}

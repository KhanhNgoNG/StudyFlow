import assert from 'node:assert/strict';
import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import express from 'express';
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';
import { taskNotFoundError, validationError } from '../src/http/api-errors.js';
import { taskResponse } from '../src/http/task-responses.js';
import { createErrorHandler, routeNotFound } from '../src/middleware/error-handler.js';
import {
  validateCreateTask,
  validateDeleteTask,
  validateGetTask,
  validateListTasks,
  validateUpdateTask,
} from '../src/middleware/validate-task-request.js';

const body = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const mutationId = '7fe7b179-6677-43ec-99e2-c0c035a184a8';
const jsonHeaders = { 'Content-Type': 'application/json', 'Idempotency-Key': mutationId };

async function serve(t, app) {
  const server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections();
  }));
  return (path, options) => fetch(`http://127.0.0.1:${server.address().port}${path}`, options);
}

function validationApp() {
  const app = express();
  const logs = [];
  app.use(express.json({ strict: false }));
  // Test-only handlers expose validated values, without pretending to persist tasks.
  const echoValidated = (req, res) => res.json(res.locals.validatedRequest);
  app.post('/tasks', validateCreateTask, echoValidated);
  app.put('/tasks/:id', validateUpdateTask, echoValidated);
  app.delete('/tasks/:id', validateDeleteTask, echoValidated);
  app.get('/tasks/:id', validateGetTask, echoValidated);
  app.get('/tasks', validateListTasks, echoValidated);
  app.get('/validation-error', (req, res, next) => next(validationError({ title: 'Title is required' }, body.id)));
  app.get('/missing-task', (req, res, next) => next(taskNotFoundError(body.id)));
  app.get('/unexpected', async () => {
    throw Object.assign(new Error('SELECT secret FROM tasks; /private/internal.db; secret-payload'), { code: 'SQLITE_ERROR', status: 422, expose: true });
  });
  app.get('/invalid-row', (req, res) => res.json(taskResponse({ id: body.id, completed: 0, is_deleted: 0 })));
  app.use(routeNotFound);
  app.use(createErrorHandler({ logger: { error: (...args) => logs.push(args) } }));
  return { app, logs };
}

test('HTTP POST/PUT hợp lệ: middleware cung cấp dữ liệu đã trim, không thêm trường server', async (t) => {
  const { app } = validationApp();
  const request = await serve(t, app);
  for (const [method, path, baseVersion] of [['POST', '/tasks', 0], ['PUT', `/tasks/${body.id}`, 2]]) {
    const response = await request(path, {
      method, headers: jsonHeaders,
      body: JSON.stringify({ ...body, baseVersion, title: '  Học 😀  ', version: 999 }),
    });
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.task.title, 'Học 😀');
    assert.equal(result.baseVersion, baseVersion);
    assert.equal(result.mutationId, mutationId);
    assert.equal(Object.hasOwn(result.task, 'version'), false);
  }
});

test('HTTP validation trả 422 với fieldErrors và taskId đúng hợp đồng', async (t) => {
  const { app, logs } = validationApp();
  const request = await serve(t, app);
  const fixture = JSON.parse(readFileSync(new URL('../../contracts/examples/error-response.json', import.meta.url), 'utf8'));
  const prepared = await request('/validation-error');
  assert.equal(prepared.status, 422);
  assert.deepEqual(await prepared.json(), fixture);
  const response = await request('/tasks', {
    method: 'POST', headers: jsonHeaders,
    body: JSON.stringify({ ...body, title: '  ', completed: 'false' }),
  });
  assert.equal(response.status, 422);
  assert.match(response.headers.get('content-type'), /application\/json/);
  const error = await response.json();
  assert.equal(error.code, 'VALIDATION_ERROR');
  assert.equal(error.message, 'Invalid task data');
  assert.equal(error.taskId, body.id);
  assert.deepEqual(Object.keys(error.fieldErrors).sort(), ['completed', 'title']);
  assert.equal(logs.length, 0);
});

test('HTTP parser phân biệt JSON sai cú pháp (400) với JSON hợp lệ sai root (422)', async (t) => {
  const { app, logs } = validationApp();
  const request = await serve(t, app);
  for (const text of ['{"title":"secret-payload",}', '{']) {
    const response = await request('/tasks', { method: 'POST', headers: jsonHeaders, body: text });
    assert.equal(response.status, 400);
    assert.deepEqual(await response.json(), { code: 'INVALID_JSON', message: 'Malformed JSON body' });
  }
  for (const text of ['null', '[]', 'true', '42', '"text"']) {
    const response = await request('/tasks', { method: 'POST', headers: jsonHeaders, body: text });
    assert.equal(response.status, 422);
    const error = await response.json();
    assert.equal(error.code, 'VALIDATION_ERROR');
    assert.equal(typeof error.fieldErrors.body, 'string');
    assert.equal(Object.hasOwn(error, 'taskId'), false);
  }
  assert.equal(logs.length, 0);
});

test('HTTP thiếu body/header, Content-Type sai và ID PUT không khớp', async (t) => {
  const { app } = validationApp();
  const request = await serve(t, app);
  const missingBody = await request('/tasks', { method: 'POST', headers: jsonHeaders });
  assert.equal(missingBody.status, 422);
  // Express parses a zero-byte JSON body as {}; required fields must still fail.
  assert.match((await missingBody.json()).fieldErrors.id, /required/);
  for (const key of [undefined, 'invalid']) {
    const headers = { 'Content-Type': 'application/json' };
    if (key !== undefined) headers['Idempotency-Key'] = key;
    const response = await request('/tasks', { method: 'POST', headers, body: JSON.stringify(body) });
    assert.equal(response.status, 422);
    assert.ok((await response.json()).fieldErrors['Idempotency-Key']);
  }
  const wrongType = await request('/tasks', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: JSON.stringify(body) });
  assert.equal(wrongType.status, 415);
  assert.equal((await wrongType.json()).code, 'UNSUPPORTED_MEDIA_TYPE');
  const mismatch = await request('/tasks/e5b951a7-797c-42e8-8369-3491c123b1a5', {
    method: 'PUT', headers: jsonHeaders, body: JSON.stringify({ ...body, baseVersion: 1 }),
  });
  assert.equal(mismatch.status, 422);
  assert.ok((await mismatch.json()).fieldErrors.id);
});

test('HTTP query: true/false, thiếu includeDeleted, lặp query và baseVersion sai', async (t) => {
  const { app } = validationApp();
  const request = await serve(t, app);
  for (const [query, expected] of [['', { includeDeleted: true }], ['?includeDeleted=true', { includeDeleted: true }], ['?includeDeleted=false', { includeDeleted: false }]]) {
    const response = await request(`/tasks${query}`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), expected);
  }
  for (const query of ['?includeDeleted=0', '?includeDeleted=', '?includeDeleted=true&includeDeleted=false']) {
    const response = await request(`/tasks${query}`);
    assert.equal(response.status, 422);
    assert.ok((await response.json()).fieldErrors.includeDeleted);
  }
  const remove = await request(`/tasks/${body.id}?baseVersion=3`, { method: 'DELETE', headers: { 'Idempotency-Key': mutationId } });
  assert.equal(remove.status, 200);
  assert.deepEqual(await remove.json(), { id: body.id, baseVersion: 3, mutationId });
  for (const query of ['', '?baseVersion=1abc', '?baseVersion=1&baseVersion=2', '?baseVersion=9007199254740992', '?baseVersion=1%0A']) {
    const response = await request(`/tasks/${body.id}${query}`, { method: 'DELETE', headers: { 'Idempotency-Key': mutationId } });
    assert.equal(response.status, 422);
    assert.ok((await response.json()).fieldErrors.baseVersion);
  }
  const noKey = await request(`/tasks/${body.id}?baseVersion=1`, { method: 'DELETE' });
  assert.equal(noKey.status, 422);
  assert.ok((await noKey.json()).fieldErrors['Idempotency-Key']);
  const invalidId = await request('/tasks/not-a-uuid');
  assert.equal(invalidId.status, 422);
  assert.ok((await invalidId.json()).fieldErrors.id);
  const validId = await request(`/tasks/${body.id}`);
  assert.equal(validId.status, 200);
  assert.deepEqual(await validId.json(), { id: body.id });
});

test('HTTP parser giữ lỗi quá dung lượng/encoding ở 413/415, không thành validation', async (t) => {
  const { app } = validationApp();
  const request = await serve(t, app);
  const tooLarge = await request('/tasks', { method: 'POST', headers: jsonHeaders, body: JSON.stringify({ text: 'a'.repeat(110000) }) });
  assert.equal(tooLarge.status, 413);
  assert.deepEqual(await tooLarge.json(), { code: 'PAYLOAD_TOO_LARGE', message: 'Request body too large' });
  const encoding = await request('/tasks', { method: 'POST', headers: { ...jsonHeaders, 'Content-Encoding': 'unsupported' }, body: '{}' });
  assert.equal(encoding.status, 415);
  assert.equal((await encoding.json()).code, 'UNSUPPORTED_MEDIA_TYPE');
});

test('lỗi bất ngờ và hàng database sai trả 500 kín; log giữ vị trí gọi, bỏ payload/SQL', async (t) => {
  const { app, logs } = validationApp();
  const request = await serve(t, app);
  for (const path of ['/unexpected?token=secret-query', '/invalid-row']) {
    const response = await request(path, { headers: { Authorization: 'secret-header' } });
    assert.equal(response.status, 500);
    assert.deepEqual(await response.json(), { code: 'INTERNAL_ERROR', message: 'Internal server error' });
  }
  assert.equal(logs.length, 2);
  assert.equal(logs[0][1].method, 'GET');
  assert.equal(logs[0][1].route, '/unexpected');
  assert.equal(logs[0][1].errorCode, 'SQLITE_ERROR');
  assert.match(logs[0][1].stackFrames, /http.test.js/);
  assert.doesNotMatch(JSON.stringify(logs), /SELECT secret|internal.db|secret-payload|secret-query|secret-header/);
});

test('URL không tồn tại khác với task chưa tồn tại; app chính chưa có route ghi', async (t) => {
  const { app } = validationApp();
  const request = await serve(t, app);
  const missingTask = await request('/missing-task');
  assert.equal(missingTask.status, 404);
  assert.deepEqual(await missingTask.json(), { code: 'TASK_NOT_FOUND', message: 'Task not found', taskId: body.id });
  const production = await serve(t, createApp());
  for (const [path, method] of [['/typo', 'GET'], ['/api/v1/tasks', 'POST']]) {
    const response = await production(path, { method });
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { code: 'ROUTE_NOT_FOUND', message: 'Route not found' });
    assert.equal(response.headers.has('x-powered-by'), false);
  }
  const invalidJson = await production('/api/v1/tasks', { method: 'POST', headers: jsonHeaders, body: '{' });
  assert.equal(invalidJson.status, 400);
  assert.equal((await invalidJson.json()).code, 'INVALID_JSON');
});

test('health giữ body rỗng: 200 với schema thật, 503 khi database không còn truy vấn được', async (t) => {
  const db = openDatabase(':memory:');
  t.after(() => { if (db.open) db.close(); });
  migrateDatabase(db);
  const logger = { error() {} };
  const request = await serve(t, createApp({ database: db, logger }));
  const healthy = await request('/api/v1/health');
  assert.equal(healthy.status, 200);
  assert.equal(await healthy.text(), '');
  db.close();
  const unavailable = await request('/api/v1/health');
  assert.equal(unavailable.status, 503);
  assert.equal(await unavailable.text(), '');
  const noDatabase = await serve(t, createApp({ logger }));
  const scaffold = await noDatabase('/api/v1/health');
  assert.equal(scaffold.status, 503);
  assert.equal(await scaffold.text(), '');
});

test('error middleware chuyển tiếp khi response đã gửi header', () => {
  const forwarded = [];
  const error = new Error('late failure');
  const handler = createErrorHandler({ logger: { error() {} } });
  handler(error, { method: 'GET', route: { path: '/late' } }, { headersSent: true }, (value) => forwarded.push(value));
  assert.deepEqual(forwarded, [error]);
});

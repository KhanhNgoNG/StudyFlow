import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHttpFixture, example, snapshot } from '../test-support/http-fixture.js';

const createBody = example('create-task-request');
const collection = '/api/v1/tasks';
const detail = `${collection}/${createBody.id}`;
const createKey = '00000000-0000-4000-8000-000000009001';
const updateKey = '00000000-0000-4000-8000-000000009002';
const failedKey = '00000000-0000-4000-8000-000000009003';
const headers = (key) => ({ 'Content-Type': 'application/json', 'Idempotency-Key': key });

async function jsonRequest(request, method, path, key, body) {
  const response = await request(path, {
    method, headers: headers(key), ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  assert.match(response.headers.get('content-type'), /^application\/json; charset=utf-8$/);
  return { status: response.status, body: await response.json() };
}

test('route ghi thật lưu biên Unicode/trim/description/ngày nhuận, không mất null hoặc false', { timeout: 15000 }, async (t) => {
  const { database, request, logs } = await createHttpFixture(t);
  const title = '😀'.repeat(120);
  const description = '😀'.repeat(2000);
  const created = await jsonRequest(request, 'POST', collection, createKey, {
    ...createBody, title: `\u00a0${title}\uFEFF`, description, dueDate: '2000-02-29', completed: true,
  });
  assert.equal(created.status, 201);
  assert.deepEqual(created.body.task, {
    id: createBody.id, title, description, subjectCode: createBody.subjectCode,
    dueDate: '2000-02-29', priority: createBody.priority, completed: true,
    isDeleted: false, version: 1, serverUpdatedAt: created.body.task.serverUpdatedAt,
  });
  const combined = 'e\u0301'.repeat(60); // 120 code points; do not normalize to 60.
  const updated = await jsonRequest(request, 'PUT', detail, updateKey, {
    ...createBody, baseVersion: 1, title: combined, description: '', dueDate: null, completed: false,
  });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.task.title, combined);
  assert.equal(updated.body.task.version, 2);
  assert.equal(updated.body.task.description, '');
  assert.equal(updated.body.task.dueDate, null);
  assert.equal(updated.body.task.completed, false);
  const fetched = await request(detail);
  assert.equal(fetched.status, 200);
  assert.deepEqual(await fetched.json(), updated.body);
  assert.equal(snapshot(database).tasks.length, 1);
  assert.equal(snapshot(database).receipts.length, 2);
  assert.equal(logs.length, 0);
});

test('validation qua POST/PUT production trả 422 và giữ nguyên task/receipt, không chỉ qua adapter test', { timeout: 20000 }, async (t) => {
  const { database, request, logs } = await createHttpFixture(t);
  const created = await jsonRequest(request, 'POST', collection, createKey, createBody);
  assert.equal(created.status, 201);
  const before = snapshot(database);
  const postCases = [
    ['body', undefined], ['body', null], ['body', []],
    ['title', { ...createBody, title: ' \t\n' }],
    ['title', { ...createBody, title: '😀'.repeat(121) }],
    ['description', { ...createBody, description: '😀'.repeat(2001) }],
    ['title', { ...createBody, title: '\ud800' }],
    ['description', { ...createBody, description: 'a\u0000b' }],
    ['dueDate', { ...createBody, dueDate: '1900-02-29' }],
    ['completed', { ...createBody, completed: 'false' }],
    ['completed', { ...createBody, completed: 0 }],
    ['priority', { ...createBody, priority: 'high' }],
    ['subjectCode', { ...createBody, subjectCode: 'UNKNOWN' }],
    ['id', { ...createBody, id: 'not-a-uuid' }],
    ['baseVersion', { ...createBody, baseVersion: 1 }],
  ];
  const updateBody = { ...createBody, baseVersion: 1 };
  const { title, ...withoutTitle } = updateBody;
  const putCases = [
    ['title', withoutTitle],
    ...[-1, 0, 1.5, Number.MAX_SAFE_INTEGER + 1, '1'].map((baseVersion) => [
      'baseVersion', { ...updateBody, baseVersion },
    ]),
  ];
  for (const [method, path, cases] of [['POST', collection, postCases], ['PUT', detail, putCases]]) {
    for (const [field, body] of cases) {
      // Reuse one unsuccessful mutation ID: validation never creates a receipt.
      const result = await jsonRequest(request, method, path, failedKey, body);
      assert.equal(result.status, 422, `${method} ${field}`);
      assert.equal(result.body.code, 'VALIDATION_ERROR');
      assert.equal(typeof result.body.fieldErrors[field], 'string', `${method} ${field}`);
      assert.equal(Object.hasOwn(result.body, 'currentTask'), false);
      assert.deepEqual(snapshot(database), before, `${method} ${field} must not commit`);
    }
  }
  assert.equal(logs.length, 0);
});

function reversedKeys(value) {
  if (Array.isArray(value)) return value.map(reversedKeys);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).reverse().map(([key, item]) => [key, reversedKeys(item)]));
  }
  return value;
}

test('production HTTP replay không phụ thuộc thứ tự key JSON, vẫn phân biệt kiểu và trường bị whitelist bỏ', { timeout: 15000 }, async (t) => {
  const { database, request, logs } = await createHttpFixture(t);
  const extra = { nullable: null, flag: false, count: 0, list: [1, 2] };
  const body = { ...createBody, unused: extra };
  const created = await jsonRequest(request, 'POST', collection, createKey, body);
  assert.equal(created.status, 201);
  assert.equal(Object.hasOwn(created.body.task, 'unused'), false);
  const before = snapshot(database);
  assert.deepEqual(await jsonRequest(request, 'POST', collection, createKey, reversedKeys(body)), created);
  const { nullable, ...withoutNullable } = extra;
  for (const unused of [
    withoutNullable, { ...extra, nullable: false }, { ...extra, flag: 'false' },
    { ...extra, count: '0' }, { ...extra, list: [2, 1] },
  ]) {
    const reused = await jsonRequest(request, 'POST', collection, createKey, { ...body, unused });
    assert.equal(reused.status, 409);
    assert.deepEqual(reused.body, {
      code: 'MUTATION_ID_REUSED', message: 'Mutation ID was already used for a different request',
      taskId: createBody.id,
    });
    assert.deepEqual(snapshot(database), before);
  }
  assert.deepEqual(snapshot(database), before);
  assert.equal(logs.length, 0);
});

test('receipt hỏng qua app chính trả 500 kín, không chạy lại mutation hoặc sửa task hiện tại', { timeout: 15000 }, async (t) => {
  const { database, request, logs } = await createHttpFixture(t);
  const created = await jsonRequest(request, 'POST', collection, createKey, createBody);
  assert.equal(created.status, 201);
  const updated = await jsonRequest(request, 'PUT', detail, updateKey, {
    ...createBody, baseVersion: 1, title: 'Bản mới phải giữ nguyên khi receipt cũ hỏng',
  });
  assert.equal(updated.status, 200);
  const clean = snapshot(database);
  const original = clean.receipts.find((row) => row.mutation_id === createKey);
  const update = database.prepare(`UPDATE mutation_receipts SET response_body = ?, request_hash = ?,
    path = ?, created_at = ? WHERE mutation_id = ?`);
  function replaceReceipt(overrides) {
    const row = { ...original, ...overrides };
    // Deliberate corruption of this test-only DB, including values real CHECKs
    // normally prevent. Restore CHECK enforcement before sending any request.
    database.pragma('ignore_check_constraints = ON');
    try { update.run(row.response_body, row.request_hash, row.path, row.created_at, createKey); }
    finally { database.pragma('ignore_check_constraints = OFF'); }
  }
  for (const corrupt of [
    { response_body: '{"secret":"SELECT /private/internal.db"' },
    { response_body: JSON.stringify({ task: { id: createBody.id } }) },
    { request_hash: 'f'.repeat(63) },
    { path: '/api/v1/tasks/wrong-post-path' },
    { created_at: 'not-a-UTC-timestamp' },
  ]) {
    replaceReceipt(corrupt);
    const before = snapshot(database);
    const replay = await jsonRequest(request, 'POST', collection, createKey, createBody);
    assert.equal(replay.status, 500);
    assert.deepEqual(replay.body, { code: 'INTERNAL_ERROR', message: 'Internal server error' });
    assert.deepEqual(snapshot(database), before);
  }
  assert.equal(logs.length, 5);
  assert.doesNotMatch(JSON.stringify(logs), /SELECT \/private\/internal\.db/);
  replaceReceipt({});
  assert.deepEqual(snapshot(database), clean);
  assert.deepEqual(await jsonRequest(request, 'POST', collection, createKey, createBody), created);
  const fetched = await request(detail);
  assert.equal(fetched.status, 200);
  assert.deepEqual(await fetched.json(), updated.body);
  assert.deepEqual(snapshot(database), clean);
});

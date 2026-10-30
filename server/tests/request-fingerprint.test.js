import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonicalJson, fingerprintRequest } from '../src/mutations/request-fingerprint.js';

const request = {
  method: 'PUT',
  path: '/api/v1/tasks/612954cb-842b-4d60-a0c8-48a7cc401814',
  query: {},
  body: { id: '612954cb-842b-4d60-a0c8-48a7cc401814', baseVersion: 1, title: '  Học tiếng Việt 😀  ' },
};

function expectValidation(action, field) {
  assert.throws(action, (error) => {
    assert.equal(error.status, 422);
    assert.equal(error.body.code, 'VALIDATION_ERROR');
    assert.equal(typeof error.body.fieldErrors[field], 'string');
    return true;
  });
}

test('canonical JSON sort key đệ quy, kể cả key giống số và __proto__, không gọi toJSON', () => {
  const left = JSON.parse('{"2":"hai","10":"mười","nested":{"z":false,"a":null},"__proto__":{"b":2,"a":1}}');
  const right = JSON.parse('{"__proto__":{"a":1,"b":2},"nested":{"a":null,"z":false},"10":"mười","2":"hai"}');
  assert.equal(canonicalJson(left), canonicalJson(right));
  assert.equal(canonicalJson(left), '{"10":"mười","2":"hai","__proto__":{"a":1,"b":2},"nested":{"a":null,"z":false}}');
  assert.equal(canonicalJson({ toJSON: 'ordinary field' }), '{"toJSON":"ordinary field"}');
  let calls = 0;
  expectValidation(() => canonicalJson({ toJSON() { calls += 1; return 'hidden'; } }), 'body');
  assert.equal(calls, 0);
  const nullPrototype = Object.assign(Object.create(null), { title: 'Tiếng Việt' });
  assert.equal(canonicalJson(nullPrototype), canonicalJson({ title: 'Tiếng Việt' }));
});

test('thứ tự key JSON khác nhau không đổi SHA-256, kể cả object lồng trong array', () => {
  const left = { ...request, body: { ...request.body, extra: [{ z: 2, a: 1 }, { b: false, a: null }] } };
  const right = { body: { extra: [{ a: 1, z: 2 }, { a: null, b: false }], title: request.body.title, baseVersion: 1, id: request.body.id }, query: {}, path: request.path, method: 'PUT' };
  assert.match(fingerprintRequest(left), /^[0-9a-f]{64}$/);
  assert.equal(fingerprintRequest(left), fingerprintRequest(right));
});

test('fingerprint giữ nguyên thứ tự array và mọi trường kể cả trường validator chưa dùng', () => {
  const left = fingerprintRequest({ ...request, body: { ...request.body, unused: [1, 2] } });
  assert.notEqual(left, fingerprintRequest({ ...request, body: { ...request.body, unused: [2, 1] } }));
  assert.notEqual(left, fingerprintRequest({ ...request, body: { ...request.body, unused: [1, 3] } }));
  assert.notEqual(left, fingerprintRequest(request));
});

test('null, thiếu trường, false, số và string có fingerprint khác nhau', () => {
  const bodies = [{}, { value: null }, { value: false }, { value: 0 }, { value: '0' }, { value: 'false' }, { value: '' }];
  assert.equal(new Set(bodies.map((body) => fingerprintRequest({ ...request, body }))).size, bodies.length);
  assert.notEqual(canonicalJson(-0), canonicalJson(0));
  assert.equal(canonicalJson(1e100), '1e+100');
});

test('body vắng hoặc undefined là không có payload; null và object rỗng vẫn khác', () => {
  const { body, ...withoutBody } = request;
  assert.equal(fingerprintRequest(withoutBody), fingerprintRequest({ ...withoutBody, body: undefined }));
  const values = [fingerprintRequest(withoutBody), fingerprintRequest({ ...withoutBody, body: null }), fingerprintRequest({ ...withoutBody, body: {} })];
  assert.equal(new Set(values).size, 3);
});

test('đổi method, path, query hoặc baseVersion ở body/query làm fingerprint thay đổi', () => {
  const original = fingerprintRequest(request);
  for (const changed of [
    { ...request, method: 'POST' },
    { ...request, method: 'DELETE' },
    { ...request, path: '/api/v1/tasks/e5b951a7-797c-42e8-8369-3491c123b1a5' },
    { ...request, query: { baseVersion: '1' } },
    { ...request, body: { ...request.body, baseVersion: 2 } },
  ]) assert.notEqual(fingerprintRequest(changed), original);
  const remove = { method: 'DELETE', path: request.path, query: { baseVersion: '1' } };
  assert.notEqual(fingerprintRequest(remove), fingerprintRequest({ ...remove, query: { baseVersion: '2' } }));
  assert.notEqual(fingerprintRequest(remove), fingerprintRequest({ ...remove, query: { baseVersion: '01' } }));
});

test('query sort tên khóa nhưng giữ nguyên mọi giá trị và thứ tự lặp', () => {
  const left = { ...request, query: { unknown: 'keep me', baseVersion: ['1', '2'] } };
  const right = { ...request, query: { baseVersion: ['1', '2'], unknown: 'keep me' } };
  assert.equal(fingerprintRequest(left), fingerprintRequest(right));
  assert.notEqual(fingerprintRequest(left), fingerprintRequest({ ...request, query: { baseVersion: ['2', '1'], unknown: 'keep me' } }));
  assert.notEqual(fingerprintRequest(left), fingerprintRequest({ ...request, query: { baseVersion: ['1', '2'] } }));
  assert.notEqual(fingerprintRequest({ ...request, query: { unknown: 'a' } }), fingerprintRequest({ ...request, query: { unknown: 'b' } }));
  const { query, ...withoutQuery } = request;
  assert.equal(fingerprintRequest(withoutQuery), fingerprintRequest(request));
});

test('không trim/coerce/normalize Unicode hoặc thêm mutation key và timestamp vào hash', () => {
  const original = fingerprintRequest(request);
  assert.notEqual(original, fingerprintRequest({ ...request, body: { ...request.body, title: request.body.title.trim() } }));
  assert.notEqual(fingerprintRequest({ ...request, body: { title: 'á' } }), fingerprintRequest({ ...request, body: { title: 'a\u0301' } }));
  assert.equal(original, fingerprintRequest({ ...request, idempotencyKey: 'first', mutationId: 'first', timestamp: 'first' }));
  assert.equal(original, fingerprintRequest({ ...request, idempotencyKey: 'second', mutationId: 'second', timestamp: 'second' }));
  // Invalid business text is still identifiable before the receipt lookup.
  assert.match(fingerprintRequest({ ...request, body: { title: '\ud800' } }), /^[0-9a-f]{64}$/);
});

test('không sửa request, query hoặc object/array payload kể cả khi đã freeze', () => {
  const body = Object.freeze({ nested: Object.freeze([{ z: 2, a: 1 }]), title: '  Giữ nguyên  ' });
  const input = Object.freeze({ ...request, query: Object.freeze({ b: '2', a: '1' }), body });
  const before = structuredClone(input);
  fingerprintRequest(input);
  assert.deepEqual(input, before);
});

test('reject dữ liệu không phải JSON thay vì stringify làm mất hoặc ép thành null', () => {
  for (const value of [undefined, NaN, Infinity, -Infinity, 1n, Symbol('value'), () => {}, new Date(), new Map(), new Set(), /pattern/]) {
    expectValidation(() => canonicalJson(value), 'body');
    expectValidation(() => fingerprintRequest({ ...request, body: { value } }), 'body');
  }
  const circular = {};
  circular.self = circular;
  expectValidation(() => canonicalJson(circular), 'body');
  const shared = { value: 1 };
  assert.equal(canonicalJson({ left: shared, right: shared }), '{"left":{"value":1},"right":{"value":1}}');
});

test('reject sparse array, array có named property, symbol và getter mà không chạy getter', () => {
  const sparse = [1, , 3];
  const extra = [1];
  extra.name = 'not JSON';
  const symbol = { [Symbol('secret')]: 'not JSON' };
  let calls = 0;
  const getter = Object.defineProperty({}, 'value', { enumerable: true, get() { calls += 1; return 1; } });
  const hidden = Object.defineProperty({}, 'value', { value: 1 });
  for (const value of [sparse, extra, symbol, getter, hidden]) expectValidation(() => canonicalJson(value), 'body');
  assert.equal(calls, 0);
});

test('minimal method/path/query validation dùng lỗi 422 chung và không ép kiểu query', () => {
  expectValidation(() => fingerprintRequest(null), 'request');
  for (const method of [undefined, 'post', 'GET', 1]) expectValidation(() => fingerprintRequest({ ...request, method }), 'method');
  for (const path of [undefined, '', 'tasks', '/api/v1/tasks?x=1', '/api/v1/tasks#fragment', '/api/v1/tasks\n']) {
    expectValidation(() => fingerprintRequest({ ...request, path }), 'path');
  }
  for (const query of [null, [], 'baseVersion=1', { baseVersion: 1 }, { baseVersion: true }, { baseVersion: ['1', 2] }, { nested: {} }]) {
    expectValidation(() => fingerprintRequest({ ...request, query }), 'query');
  }
  let calls = 0;
  const accessor = Object.defineProperty({ ...request }, 'body', { enumerable: true, get() { calls += 1; return {}; } });
  expectValidation(() => fingerprintRequest(accessor), 'body');
  assert.equal(calls, 0);
});

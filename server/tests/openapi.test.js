import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { test } from 'node:test';
import { createHttpFixture } from '../test-support/http-fixture.js';
import { contract, createContract, inspectContract, parseContractYaml, readExample } from '../test-support/openapi-contract.js';

const key = (number) => `00000000-0000-4000-8000-${String(number).padStart(12, '0')}`;
const id = readExample('create-task-request').id;
const detail = `/api/v1/tasks/${id}`;
const missing = '6a144198-9c2b-49dc-8c56-cf295b92d016';

test('OpenAPI YAML/local refs, schema vocabulary và parameter bắt buộc đúng hợp đồng', () => {
  const { spec, dereference } = contract;
  const inspected = inspectContract();
  assert.equal(spec.openapi, '3.0.3');
  assert.ok(inspected.references > 0);
  assert.ok(inspected.externalExamples > 0);
  assert.deepEqual(Object.keys(spec.paths).sort(), ['/health', '/tasks', '/tasks/{id}']);
  const expected = {
    '/tasks': { get: ['query:includeDeleted'], post: ['header:Idempotency-Key'] },
    '/tasks/{id}': {
      get: ['path:id'], put: ['header:Idempotency-Key', 'path:id'],
      delete: ['header:Idempotency-Key', 'path:id', 'query:baseVersion'],
    },
    '/health': { get: [] },
  };
  const operationIds = new Set();
  for (const [path, operations] of Object.entries(expected)) {
    assert.deepEqual(Object.keys(spec.paths[path]).sort(), Object.keys(operations).sort());
    for (const [method, names] of Object.entries(operations)) {
      const op = spec.paths[path][method];
      assert.equal(typeof op.operationId, 'string');
      assert.ok(!operationIds.has(op.operationId), 'Unique operationId');
      operationIds.add(op.operationId);
      const parameters = (op.parameters ?? []).map(dereference);
      assert.deepEqual(parameters.map((item) => `${item.in}:${item.name}`).sort(), [...names].sort());
      for (const parameter of parameters) {
        assert.equal(parameter.required === true, parameter.name !== 'includeDeleted');
        if (parameter.in === 'path' || parameter.in === 'header') {
          assert.deepEqual(parameter.schema, { type: 'string', format: 'uuid' });
        }
      }
      for (const status of Object.keys(op.responses)) assert.match(status, /^(?:[1-5]\d\d|[1-5]XX)$/);
      if (['post', 'put'].includes(method)) {
        assert.equal(op.requestBody.required, true);
        assert.deepEqual(Object.keys(op.requestBody.content), ['application/json']);
      } else assert.equal(Object.hasOwn(op, 'requestBody'), false);
    }
  }
  assert.deepEqual(spec.components.parameters.DeleteBaseVersion.schema, { type: 'integer', minimum: 1 });
  assert.deepEqual(spec.paths['/tasks'].get.parameters[0].schema, { type: 'boolean', default: true });
  assert.ok(spec.paths['/tasks'].get.responses['422']);
  assert.ok(spec.paths['/tasks/{id}'].delete.responses['200'].content['application/json']);
});

test('Tất cả JSON fixture và external example khớp subset schema OpenAPI', () => {
  const schemas = {
    task: 'Task', tombstone: 'Task', 'create-task-request': 'CreateTaskRequest',
    'update-task-request': 'UpdateTaskRequest', 'task-response': 'TaskResponse',
    'update-task-response': 'TaskResponse', 'delete-task-response': 'TaskResponse',
    'task-list': 'TaskListResponse', 'empty-task-list': 'TaskListResponse',
    'conflict-response': 'VersionConflictError', 'error-response': 'ValidationError',
    'task-not-found-response': 'TaskNotFoundError', 'mutation-id-reused-response': 'MutationIdReusedError',
  };
  const actual = readdirSync(new URL('../../contracts/examples/', import.meta.url)).filter((name) => name.endsWith('.json')).sort();
  assert.deepEqual(actual, Object.keys(schemas).map((name) => `${name}.json`).sort(), 'Every fixture must have a schema mapping');
  for (const [name, schema] of Object.entries(schemas)) {
    contract.assertSchema(readExample(name), contract.spec.components.schemas[schema], name);
  }
  let checked = 0;
  for (const operations of Object.values(contract.spec.paths)) {
    for (const op of Object.values(operations)) {
      const blocks = [op.requestBody, ...Object.values(op.responses)].filter(Boolean).map(contract.dereference);
      for (const block of blocks) {
        for (const content of Object.values(block.content ?? {})) {
          for (const example of Object.values(content.examples ?? {})) {
            const filename = example.externalValue?.match(/^\.\/examples\/([^/]+)\.json$/)?.[1];
            assert.ok(filename, 'Current examples must reference local JSON fixtures');
            contract.assertSchema(readExample(filename), content.schema, example.externalValue);
            checked += 1;
          }
        }
      }
    }
  }
  assert.equal(checked, inspectContract().externalExamples);
});

test('Contract checker không bỏ qua YAML trùng key, schema lạ hoặc giá trị sai', () => {
  assert.throws(() => parseContractYaml('name: first\nname: second\n'), /unique|duplicate/i);
  assert.throws(() => createContract({}).resolve('#/missing'), /Unresolved/);
  assert.throws(() => contract.assertSchema('x', { type: 'string', pattern: 'x' }), /Unsupported schema keyword/);
  const task = readExample('task');
  for (const [field, value] of [
    ['completed', 'false'], ['dueDate', '2026-02-30'], ['dueDate', '2028-02-29\n'], ['version', 1.5],
    ['title', '😀'.repeat(121)], ['id', `${id}\n`], ['serverUpdatedAt', '2026-02-30T03:00:00Z'],
  ]) assert.throws(() => contract.assertSchema({ ...task, [field]: value }, contract.spec.components.schemas.Task), assert.AssertionError);
  contract.assertSchema({ ...task, title: '😀'.repeat(120) }, contract.spec.components.schemas.Task);
  contract.assertSchema({ ...task, dueDate: '2028-02-29' }, contract.spec.components.schemas.Task);
  contract.assertSchema({ ...task, dueDate: null }, contract.spec.components.schemas.Task);
  const conflict = contract.dereference(contract.spec.components.responses.WriteConflict).content['application/json'].schema;
  contract.assertSchema(readExample('conflict-response'), conflict);
  contract.assertSchema(readExample('mutation-id-reused-response'), conflict);
  assert.throws(() => contract.assertSchema({ code: 'OTHER', message: 'Error' }, conflict), /oneOf/);
});

test('HTTP production CRUD, lỗi và tombstone được đối chiếu với schema/parameter OpenAPI thật', async (t) => {
  const fixture = await createHttpFixture(t);
  async function request(method, openapiPath, path, status, { body, mutation, invalid = false } = {}) {
    const headers = {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(mutation !== undefined ? { 'Idempotency-Key': key(mutation) } : {}),
    };
    const url = new URL(path, 'http://localhost');
    if (!invalid) {
      contract.assertRequest(method, openapiPath, {
        headers, body, query: Object.fromEntries(url.searchParams),
        pathParameters: { id: decodeURIComponent(url.pathname.split('/').at(-1)) },
      });
    }
    const response = await fixture.request(path, {
      method, headers, ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
    assert.equal(response.status, status, `${method} ${path}`);
    const result = await response.json();
    contract.assertResponse(method, openapiPath, response, result);
    return result;
  }
  const createBody = readExample('create-task-request');
  const updateBody = readExample('update-task-request');
  assert.deepEqual(await request('GET', '/tasks', '/api/v1/tasks', 200), { tasks: [] });
  const created = await request('POST', '/tasks', '/api/v1/tasks', 201, { body: createBody, mutation: 1 });
  assert.equal(created.task.id, id);
  assert.equal(created.task.version, 1);
  await request('GET', '/tasks/{id}', detail, 200);
  await request('GET', '/tasks/{id}', `/api/v1/tasks/${missing}`, 404);
  await request('GET', '/tasks/{id}', '/api/v1/tasks/not-uuid', 422, { invalid: true });
  await request('GET', '/tasks', '/api/v1/tasks?includeDeleted=wrong', 422, { invalid: true });
  const invalidBody = { ...createBody, title: '' };
  await request('POST', '/tasks', '/api/v1/tasks', 422, { body: invalidBody, mutation: 2, invalid: true });
  await request('POST', '/tasks', '/api/v1/tasks', 409, { body: createBody, mutation: 3 });
  await request('POST', '/tasks', '/api/v1/tasks', 409, { body: { ...createBody, title: 'Request khác' }, mutation: 1 });
  const updated = await request('PUT', '/tasks/{id}', detail, 200, { body: updateBody, mutation: 4 });
  assert.equal(updated.task.version, 2);
  await request('PUT', '/tasks/{id}', detail, 409, { body: updateBody, mutation: 5 });
  await request('PUT', '/tasks/{id}', `/api/v1/tasks/${missing}`, 404, { body: { ...updateBody, id: missing }, mutation: 6 });
  const deleted = await request('DELETE', '/tasks/{id}', `${detail}?baseVersion=2`, 200, { mutation: 7 });
  assert.equal(deleted.task.isDeleted, true);
  await request('GET', '/tasks/{id}', detail, 200);
  const omitted = await request('GET', '/tasks', '/api/v1/tasks', 200);
  assert.deepEqual(omitted, await request('GET', '/tasks', '/api/v1/tasks?includeDeleted=true', 200));
  assert.deepEqual(omitted, { tasks: [deleted.task] });
  assert.deepEqual(await request('GET', '/tasks', '/api/v1/tasks?includeDeleted=false', 200), { tasks: [] });
  const noop = await request('DELETE', '/tasks/{id}', `${detail}?baseVersion=3`, 200, { mutation: 8 });
  assert.deepEqual(noop, deleted);
  await request('DELETE', '/tasks/{id}', `${detail}?baseVersion=2`, 409, { mutation: 9 });
  await request('DELETE', '/tasks/{id}', `/api/v1/tasks/${missing}?baseVersion=1`, 404, { mutation: 10 });
  const restored = await request('PUT', '/tasks/{id}', detail, 200, {
    body: { ...updateBody, baseVersion: 3, title: 'Khôi phục để đối chiếu replay' }, mutation: 11,
  });
  assert.equal(restored.task.isDeleted, false);
  assert.deepEqual(await request('DELETE', '/tasks/{id}', `${detail}?baseVersion=3`, 200, { mutation: 8 }), deleted);
  assert.deepEqual(await request('GET', '/tasks/{id}', detail, 200), restored);
  assert.equal(fixture.logs.length, 0);
});

test('HTTP health 200/503 khớp OpenAPI và thiết kế body rỗng hiện tại', async (t) => {
  const fixture = await createHttpFixture(t);
  for (const expected of [200, 503]) {
    if (expected === 503) fixture.database.close();
    const response = await fixture.request('/api/v1/health');
    assert.equal(response.status, expected);
    contract.assertResponse('GET', '/health', response);
    assert.equal(await response.text(), '');
    assert.equal(response.headers.get('content-type'), null);
  }
});

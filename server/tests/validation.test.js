import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { openDatabase } from '../src/database/connection.js';
import { ApiError } from '../src/http/api-errors.js';
import {
  validateCreateTaskRequest,
  validateDeleteTaskRequest,
  validateListTasksQuery,
  validateTaskId,
  validateUpdateTaskRequest,
} from '../src/validation/task-requests.js';

const createBody = JSON.parse(readFileSync(new URL('../../contracts/examples/create-task-request.json', import.meta.url), 'utf8'));
const updateBody = JSON.parse(readFileSync(new URL('../../contracts/examples/update-task-request.json', import.meta.url), 'utf8'));
const mutationId = '7fe7b179-6677-43ec-99e2-c0c035a184a8';
const otherId = 'e5b951a7-797c-42e8-8369-3491c123b1a5';
const create = (body = createBody, idempotencyKey = mutationId) => validateCreateTaskRequest({ body, idempotencyKey });

function expectValidation(action, field, message) {
  assert.throws(action, (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 422);
    assert.equal(error.body.code, 'VALIDATION_ERROR');
    assert.equal(typeof error.body.fieldErrors[field], 'string', field);
    if (message) assert.match(error.body.fieldErrors[field], message);
    return true;
  });
}

test('POST/PUT chấp nhận chính fixture của hợp đồng và không sửa snapshot đầu vào', () => {
  const input = Object.freeze({ ...createBody, title: ' \t Học tiếng Việt 😀\n ' });
  const result = create(input);
  assert.equal(result.task.title, 'Học tiếng Việt 😀');
  assert.equal(input.title, ' \t Học tiếng Việt 😀\n ');
  assert.equal(result.mutationId, mutationId);
  assert.equal(result.baseVersion, 0);
  assert.equal(result.task.dueDate, null);
  const updated = validateUpdateTaskRequest({ body: updateBody, id: updateBody.id, idempotencyKey: mutationId });
  assert.equal(updated.baseVersion, 1);
  assert.equal(updated.task.title, updateBody.title);
});

test('whitelist bỏ version, serverUpdatedAt và metadata; không đọc giá trị để lưu', () => {
  const result = create({ ...createBody, version: 999, serverUpdatedAt: 'client-time', localRevision: 4, pending: true });
  assert.deepEqual(Object.keys(result.task).sort(), [
    'id', 'title', 'description', 'subjectCode', 'dueDate', 'priority', 'completed', 'isDeleted',
  ].sort());
  assert.equal(result.task.description, createBody.description);
});

test('title trim khoảng trắng JavaScript, giới hạn 1–120 và description 0–2000', () => {
  assert.equal(create({ ...createBody, title: '\u00a0\tX\n\uFEFF' }).task.title, 'X');
  assert.equal(create({ ...createBody, title: '😀'.repeat(120) }).task.title, '😀'.repeat(120));
  assert.equal(create({ ...createBody, description: '' }).task.description, '');
  assert.equal(create({ ...createBody, description: '😀'.repeat(2000) }).task.description, '😀'.repeat(2000));
  for (const title of ['', ' \t\n\u00a0', 'a'.repeat(121), '😀'.repeat(121)]) {
    expectValidation(() => create({ ...createBody, title }), 'title');
  }
  expectValidation(() => create({ ...createBody, description: '😀'.repeat(2001) }), 'description');
});

test('tiếng Việt, dấu tách rời và emoji có độ dài thống nhất với SQLite thực tế', (t) => {
  const db = openDatabase(':memory:');
  t.after(() => db.close());
  for (const text of ['Tiếng Việt', '😀', 'a\u0301', '👨‍👩‍👧‍👦']) {
    assert.equal([...text].length, db.prepare('SELECT length(?) AS length').get(text).length);
    assert.equal(create({ ...createBody, title: text }).task.title, text);
  }
  assert.equal(create({ ...createBody, title: 'a\u0301'.repeat(60) }).task.title, 'a\u0301'.repeat(60));
  expectValidation(() => create({ ...createBody, title: 'a\u0301'.repeat(61) }), 'title');
  assert.equal(db.prepare('SELECT length(?) AS length').get('a\u0000b').length, 1);
  for (const field of ['title', 'description']) {
    for (const value of ['a\u0000b', '\ud800', '\udfff']) {
      expectValidation(() => create({ ...createBody, [field]: value }), field);
    }
  }
});

test('phân biệt body thiếu, null và sai kiểu', () => {
  expectValidation(() => validateCreateTaskRequest({ idempotencyKey: mutationId }), 'body', /required/);
  expectValidation(() => create(null), 'body', /null/);
  for (const body of [[], 'text', 42, true]) expectValidation(() => create(body), 'body', /JSON object/);
});

for (const field of Object.keys(createBody)) {
  test(`${field}: thiếu trường không được thay bằng default`, () => {
    const body = { ...createBody };
    delete body[field];
    expectValidation(() => create(body), field, /required/);
    expectValidation(() => validateUpdateTaskRequest({ body, id: createBody.id, idempotencyKey: mutationId }), field, /required/);
  });
  if (field !== 'dueDate') {
    test(`${field}: null khác với sai kiểu`, () => {
      expectValidation(() => create({ ...createBody, [field]: null }), field, /null/);
      const wrongType = typeof createBody[field] === 'string' ? 17 : 'false';
      expectValidation(() => create({ ...createBody, [field]: wrongType }), field, /must be a/);
    });
  }
}

test('dueDate null hợp lệ, sai kiểu không hợp lệ; enum phân biệt hoa thường', () => {
  assert.equal(create({ ...createBody, dueDate: null }).task.dueDate, null);
  for (const dueDate of [false, 20261029, [], {}]) expectValidation(() => create({ ...createBody, dueDate }), 'dueDate');
  for (const subjectCode of ['mobile', 'UNKNOWN', '']) expectValidation(() => create({ ...createBody, subjectCode }), 'subjectCode');
  for (const priority of ['medium', 'URGENT', '']) expectValidation(() => create({ ...createBody, priority }), 'priority');
  for (const subjectCode of ['MOBILE', 'DATABASE', 'GENERAL', 'OTHER']) create({ ...createBody, subjectCode });
  for (const priority of ['LOW', 'MEDIUM', 'HIGH']) create({ ...createBody, priority });
});

test('ngày thực sự tồn tại, gồm năm nhuận thế kỷ và giới hạn YYYY', () => {
  for (const dueDate of ['2028-02-29', '2000-02-29', '2026-04-30', '0000-02-29', '9999-12-31']) {
    assert.equal(create({ ...createBody, dueDate }).task.dueDate, dueDate);
  }
  for (const dueDate of [
    '2026-02-29', '1900-02-29', '2100-02-29', '2026-02-30', '2026-04-31',
    '2026-00-10', '2026-13-10', '2026-01-00', '2026-01-32', '2026-1-01',
    '2026-10-29T00:00:00Z', ' 2026-10-29', '2026-10-29\n', '',
  ]) expectValidation(() => create({ ...createBody, dueDate }), 'dueDate');
});

test('boolean JSON không chấp nhận chuỗi, số, array hoặc object; POST/PUT không xóa', () => {
  assert.equal(create({ ...createBody, completed: true }).task.completed, true);
  for (const field of ['completed', 'isDeleted']) {
    for (const value of ['false', 'true', 0, 1, [], {}]) {
      expectValidation(() => create({ ...createBody, [field]: value }), field);
    }
  }
  expectValidation(() => create({ ...createBody, isDeleted: true }), 'isDeleted');
  expectValidation(() => validateUpdateTaskRequest({ body: { ...updateBody, isDeleted: true }, id: updateBody.id, idempotencyKey: mutationId }), 'isDeleted');
});

test('UUID và Idempotency-Key bắt buộc, giữ nguyên chữ hoa/chữ thường', () => {
  for (const id of ['', 'not-uuid', '612954cb842b4d60a0c848a7cc401814', ` ${createBody.id}`, `${createBody.id}\n`, 42]) {
    expectValidation(() => create({ ...createBody, id }), 'id');
    expectValidation(() => validateTaskId(id), 'id');
  }
  for (const idempotencyKey of [undefined, null, '', 'not-uuid', [mutationId], 42]) {
    expectValidation(() => validateCreateTaskRequest({ body: createBody, idempotencyKey }), 'Idempotency-Key');
  }
  assert.equal(create({ ...createBody, id: createBody.id.toUpperCase() }, mutationId.toUpperCase()).mutationId, mutationId.toUpperCase());
  assert.equal(validateTaskId(createBody.id), createBody.id);
});

test('PUT kiểm tra ID URL/body và baseVersion chỉ ở mức cấu trúc, chưa đối chiếu database', () => {
  expectValidation(() => validateUpdateTaskRequest({ body: updateBody, id: otherId, idempotencyKey: mutationId }), 'id', /match/);
  expectValidation(() => validateUpdateTaskRequest({ body: updateBody, id: 'invalid', idempotencyKey: mutationId }), 'id', /UUID/);
  for (const baseVersion of [-1, 0, 1.5, Number.MAX_SAFE_INTEGER + 1, Infinity, NaN, '1', false]) {
    expectValidation(() => validateUpdateTaskRequest({ body: { ...updateBody, baseVersion }, id: updateBody.id, idempotencyKey: mutationId }), 'baseVersion');
  }
  assert.equal(validateUpdateTaskRequest({ body: { ...updateBody, baseVersion: Number.MAX_SAFE_INTEGER }, id: updateBody.id, idempotencyKey: mutationId }).baseVersion, Number.MAX_SAFE_INTEGER);
  for (const baseVersion of [-1, 1, 0.5, Number.MAX_SAFE_INTEGER + 1, '0']) {
    expectValidation(() => create({ ...createBody, baseVersion }), 'baseVersion');
  }
});

test('DELETE chỉ chuyển chuỗi thập phân hợp lệ thành số nguyên an toàn', () => {
  const remove = (baseVersion) => validateDeleteTaskRequest({ id: createBody.id, baseVersion, idempotencyKey: mutationId });
  assert.equal(remove('1').baseVersion, 1);
  assert.equal(remove('01').baseVersion, 1);
  assert.equal(remove(String(Number.MAX_SAFE_INTEGER)).baseVersion, Number.MAX_SAFE_INTEGER);
  for (const value of [undefined, null, '', '0', '-1', '1.5', '1abc', '1e2', '+1', ' 1', '1 ', '1\n', '0x10', '9007199254740992', '9'.repeat(400), ['1', '2'], {}, 1]) {
    expectValidation(() => remove(value), 'baseVersion');
  }
  expectValidation(() => validateDeleteTaskRequest({ id: 'bad', baseVersion: '1', idempotencyKey: mutationId }), 'id');
  expectValidation(() => validateDeleteTaskRequest({ id: createBody.id, baseVersion: '1' }), 'Idempotency-Key');
});

test('includeDeleted mặc định true theo quyết định Bước 6; chỉ nhận true/false dạng query', () => {
  assert.deepEqual(validateListTasksQuery({}), { includeDeleted: true });
  assert.deepEqual(validateListTasksQuery({ includeDeleted: 'true' }), { includeDeleted: true });
  assert.deepEqual(validateListTasksQuery({ includeDeleted: 'false' }), { includeDeleted: false });
  for (const includeDeleted of ['', '0', '1', 'False', 'true ', undefined, null, true, false, ['true', 'false'], {}]) {
    expectValidation(() => validateListTasksQuery({ includeDeleted }), 'includeDeleted');
  }
  for (const query of [{ 'includeDeleted[]': 'false' }, { 'includeDeleted[value]': 'true' }]) {
    expectValidation(() => validateListTasksQuery(query), 'includeDeleted');
  }
});

test('taskId chỉ có khi xác định được: POST từ body, PUT ưu tiên đường dẫn', () => {
  assert.throws(() => create({ ...createBody, title: '' }), (error) => error.body.taskId === createBody.id);
  assert.throws(() => create({ ...createBody, id: 'bad' }), (error) => !Object.hasOwn(error.body, 'taskId'));
  assert.throws(() => validateUpdateTaskRequest({ body: updateBody, id: otherId, idempotencyKey: mutationId }), (error) => error.body.taskId === otherId);
});

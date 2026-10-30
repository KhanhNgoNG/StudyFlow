import { isDeepStrictEqual, types } from 'node:util';
import { mutationIdReusedError, validationError } from '../http/api-errors.js';
import { assertTaskResponse, taskResponse } from '../http/task-responses.js';
import { createMutationReceiptRepository } from '../repositories/mutation-receipt-repository.js';
import { createTaskRepository } from '../repositories/task-repository.js';
import { isUtcTimestamp, isUuid } from '../validation/task-fields.js';
import {
  validateCreateTaskRequest, validateDeleteTaskRequest, validateIdempotencyKey, validateUpdateTaskRequest,
} from '../validation/task-requests.js';
import { canonicalJson, fingerprintRequest } from './request-fingerprint.js';

const methods = ['POST', 'PUT', 'DELETE'];

function pathId(path) {
  const match = /^\/api\/v1\/tasks\/([^/]+)$/.exec(path);
  if (!match) return undefined;
  try { return decodeURIComponent(match[1]); } catch { return undefined; }
}

function knownTaskId(raw) {
  const id = raw.method === 'POST' ? raw.body?.id : pathId(raw.path);
  return isUuid(id) ? id : undefined;
}

function validateNewMutation(raw, mutationId) {
  const allowedQuery = raw.method === 'DELETE' ? ['baseVersion'] : [];
  if (Object.keys(raw.query).some((key) => !allowedQuery.includes(key))) {
    throw validationError({ query: raw.method === 'DELETE'
      ? 'Only baseVersion is supported for DELETE' : 'Query parameters are not supported for POST or PUT' }, knownTaskId(raw));
  }
  if (raw.method === 'POST') {
    if (raw.path !== '/api/v1/tasks') throw validationError({ path: 'Invalid POST task path' });
    return validateCreateTaskRequest({ body: raw.body, idempotencyKey: mutationId });
  }
  const id = pathId(raw.path);
  if (id === undefined) throw validationError({ path: 'Invalid task path' });
  if (raw.method === 'PUT') {
    return validateUpdateTaskRequest({ body: raw.body, id, idempotencyKey: mutationId });
  }
  if (Object.hasOwn(raw, 'body')) throw validationError({ body: 'DELETE does not accept a JSON body' }, knownTaskId(raw));
  return validateDeleteTaskRequest({ id, baseVersion: raw.query.baseVersion, idempotencyKey: mutationId });
}

function assertSuccess(status, body, method, taskId) {
  assertTaskResponse(body);
  if (status !== (method === 'POST' ? 201 : 200)) throw new TypeError('Invalid mutation response status');
  if (body.task.id !== taskId) throw new TypeError('Mutation response task ID does not match');
  if (body.task.isDeleted !== (method === 'DELETE')) throw new TypeError('Invalid mutation response deletion state');
  if (method === 'POST' && body.task.version !== 1) throw new TypeError('Invalid created task version');
}

function decodeReceipt(row) {
  if (!isUuid(row.mutation_id) || !isUuid(row.task_id) || !methods.includes(row.method)
    || typeof row.path !== 'string' || !row.path.startsWith('/')
    || typeof row.request_hash !== 'string' || row.request_hash.length !== 64 || !/^[0-9a-f]{64}$/.test(row.request_hash)
    || !isUtcTimestamp(row.created_at) || typeof row.response_body !== 'string') {
    throw new TypeError('Invalid mutation receipt metadata');
  }
  if (row.method === 'POST' ? row.path !== '/api/v1/tasks' : pathId(row.path) !== row.task_id) {
    throw new TypeError('Invalid mutation receipt task path');
  }
  let body;
  try { body = JSON.parse(row.response_body); } catch { throw new TypeError('Invalid mutation receipt JSON'); }
  assertSuccess(row.response_status, body, row.method, row.task_id);
  return body;
}

function snapshotResponse(result) {
  if (!result || typeof result !== 'object' || Array.isArray(result)) throw new TypeError('Invalid mutation callback result');
  if (typeof result.then === 'function') {
    // A returned rejected Promise must not become an unhandled rejection as well.
    Promise.resolve(result).catch(() => {});
    throw new TypeError('Mutation callback must be synchronous');
  }
  let text;
  try { text = canonicalJson(result.body); } catch { throw new TypeError('Mutation response must contain only JSON data'); }
  return { status: result.status, body: JSON.parse(text), text };
}

export function createMutationExecutor(database) {
  const receipts = createMutationReceiptRepository(database);
  const tasks = createTaskRepository(database);

  return {
    execute(input, apply) {
      if (!input || typeof input !== 'object' || Array.isArray(input)) {
        throw validationError({ request: 'Mutation request is required' });
      }
      const mutationId = validateIdempotencyKey(input.idempotencyKey);
      const requestHash = fingerprintRequest(input);
      // Capture raw JSON before any business normalization or callback can modify it.
      const raw = JSON.parse(canonicalJson({
        method: input.method, path: input.path, query: input.query === undefined ? {} : input.query,
        ...(input.body === undefined ? {} : { body: input.body }),
      }));
      if (database.inTransaction) throw new TypeError('Mutation executor requires its own transaction');

      return database.transaction(() => {
        const receipt = receipts.findByMutationId(mutationId);
        if (receipt !== undefined) {
          const body = decodeReceipt(receipt);
          if (receipt.request_hash !== requestHash) {
            throw mutationIdReusedError(knownTaskId(raw) ?? receipt.task_id);
          }
          if (receipt.mutation_id !== mutationId || receipt.method !== raw.method || receipt.path !== raw.path
            || receipt.task_id !== knownTaskId(raw)) throw new TypeError('Mutation receipt does not match its request');
          return { status: receipt.response_status, body, replayed: true };
        }

        // Receipt lookup must precede full validation and endpoint/version logic.
        const request = validateNewMutation(raw, mutationId);
        if (typeof apply !== 'function' || types.isAsyncFunction(apply)
          || apply.constructor?.name === 'AsyncFunction' || types.isGeneratorFunction(apply)) {
          throw new TypeError('Mutation callback must be a synchronous function');
        }
        const taskId = raw.method === 'DELETE' ? request.id : request.task.id;
        const result = snapshotResponse(apply({ database, request }));
        assertSuccess(result.status, result.body, raw.method, taskId);
        const persisted = tasks.findById(taskId);
        if (persisted === undefined || !isDeepStrictEqual(taskResponse(persisted), result.body)) {
          throw new TypeError('Mutation response does not match the persisted task');
        }
        receipts.insert({
          mutation_id: mutationId, task_id: taskId, method: raw.method, path: raw.path,
          request_hash: requestHash, response_status: result.status,
          response_body: result.text, created_at: new Date().toISOString(),
        });
        return { status: result.status, body: result.body, replayed: false };
      }).immediate();
    },
  };
}

import { validationError } from '../http/api-errors.js';
import { isUuid, isVersion, validateTaskFields } from './task-fields.js';

function checkUuid(value, field, fieldErrors) {
  if (value === undefined) fieldErrors[field] = `${field} is required`;
  else if (value === null) fieldErrors[field] = `${field} must not be null`;
  else if (typeof value !== 'string') fieldErrors[field] = `${field} must be a string`;
  else if (!isUuid(value)) fieldErrors[field] = 'Must be a UUID';
}

// Minimal identity check, safe to run before receipt lookup/business validation.
export function validateIdempotencyKey(value) {
  const fieldErrors = {};
  checkUuid(value, 'Idempotency-Key', fieldErrors);
  if (Object.keys(fieldErrors).length) throw validationError(fieldErrors);
  return value;
}

function checkBodyVersion(body, creating, fieldErrors) {
  // An invalid root body already has a body error; do not invent missing fields.
  if (!body || typeof body !== 'object' || Array.isArray(body)) return;
  const value = body.baseVersion;
  if (!Object.hasOwn(body, 'baseVersion')) fieldErrors.baseVersion = 'baseVersion is required';
  else if (value === null) fieldErrors.baseVersion = 'baseVersion must not be null';
  else if (typeof value !== 'number') fieldErrors.baseVersion = 'baseVersion must be a number';
  else if (!isVersion(value, 0)) fieldErrors.baseVersion = 'Must be a non-negative safe integer';
  else if (creating && value !== 0) fieldErrors.baseVersion = 'Must be 0 for POST';
  else if (!creating && value < 1) fieldErrors.baseVersion = 'Must be at least 1 for PUT';
}

function validateWrite({ body, id, idempotencyKey }, creating) {
  const { task, fieldErrors } = validateTaskFields(body);
  checkUuid(idempotencyKey, 'Idempotency-Key', fieldErrors);
  checkBodyVersion(body, creating, fieldErrors);
  if (!creating) {
    checkUuid(id, 'id', fieldErrors);
    if (isUuid(id) && isUuid(task?.id) && id !== task.id) fieldErrors.id = 'Body ID must match path ID';
  }
  const taskId = creating ? task?.id : (isUuid(id) ? id : undefined);
  if (Object.keys(fieldErrors).length) throw validationError(fieldErrors, taskId);
  return { task, baseVersion: body.baseVersion, mutationId: idempotencyKey };
}

export function validateCreateTaskRequest(input) {
  return validateWrite(input, true);
}

export function validateUpdateTaskRequest(input) {
  return validateWrite(input, false);
}

export function validateTaskId(id) {
  const fieldErrors = {};
  checkUuid(id, 'id', fieldErrors);
  if (Object.keys(fieldErrors).length) throw validationError(fieldErrors);
  return id;
}

export function validateDeleteTaskRequest({ id, baseVersion, idempotencyKey }) {
  const fieldErrors = {};
  checkUuid(id, 'id', fieldErrors);
  checkUuid(idempotencyKey, 'Idempotency-Key', fieldErrors);
  let version;
  if (baseVersion === undefined) fieldErrors.baseVersion = 'baseVersion is required';
  else if (baseVersion === null) fieldErrors.baseVersion = 'baseVersion must not be null';
  else if (typeof baseVersion !== 'string') fieldErrors.baseVersion = 'Must be a single query string';
  else if (baseVersion === '' || /\D/.test(baseVersion)) fieldErrors.baseVersion = 'Must contain decimal digits only';
  else {
    version = Number(baseVersion);
    if (!isVersion(version)) fieldErrors.baseVersion = 'Must be a safe integer of at least 1';
  }
  if (Object.keys(fieldErrors).length) throw validationError(fieldErrors, id);
  return { id, baseVersion: version, mutationId: idempotencyKey };
}

export function validateListTasksQuery(query) {
  // Express's simple query parser keeps bracket notation as literal keys.
  // Reject it rather than mistaking includeDeleted[]=false for an omitted flag.
  if (Object.keys(query).some((key) => key.startsWith('includeDeleted['))) {
    throw validationError({ includeDeleted: 'Must be a single query string: true or false' });
  }
  if (!Object.hasOwn(query, 'includeDeleted')) return { includeDeleted: true };
  if (query.includeDeleted !== 'true' && query.includeDeleted !== 'false') {
    throw validationError({ includeDeleted: 'Must be a single query string: true or false' });
  }
  return { includeDeleted: query.includeDeleted === 'true' };
}

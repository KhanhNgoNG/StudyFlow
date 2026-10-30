import { ApiError } from '../http/api-errors.js';
import {
  validateCreateTaskRequest,
  validateDeleteTaskRequest,
  validateListTasksQuery,
  validateTaskId,
  validateUpdateTaskRequest,
} from '../validation/task-requests.js';

export function assertJsonContentType(req) {
  const empty = !req.get('Transfer-Encoding') && Number(req.get('Content-Length') ?? 0) === 0;
  const declared = req.get('Content-Type')?.split(';', 1)[0].trim().toLowerCase();
  if (!req.is('application/json') && !(empty && declared === 'application/json')) {
    throw new ApiError(415, { code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Content-Type must be application/json' });
  }
}

// This only checks transport metadata; it never trims or validates task data.
export function requireJsonContentType(req, res, next) {
  try { assertJsonContentType(req); next(); } catch (error) { next(error); }
}

function validateWith(validator, { jsonBody = false } = {}) {
  return (req, res, next) => {
    try {
      if (jsonBody) assertJsonContentType(req);
      res.locals.validatedRequest = validator(req);
      next();
    } catch (error) {
      next(error);
    }
  };
}

export const validateCreateTask = validateWith((req) => validateCreateTaskRequest({
  body: req.body, idempotencyKey: req.get('Idempotency-Key'),
}), { jsonBody: true });

export const validateUpdateTask = validateWith((req) => validateUpdateTaskRequest({
  body: req.body, id: req.params.id, idempotencyKey: req.get('Idempotency-Key'),
}), { jsonBody: true });

export const validateDeleteTask = validateWith((req) => validateDeleteTaskRequest({
  id: req.params.id, baseVersion: req.query.baseVersion, idempotencyKey: req.get('Idempotency-Key'),
}));

export const validateGetTask = validateWith((req) => ({ id: validateTaskId(req.params.id) }));
export const validateListTasks = validateWith((req) => validateListTasksQuery(req.query));

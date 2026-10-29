import { ApiError } from '../http/api-errors.js';
import {
  validateCreateTaskRequest,
  validateDeleteTaskRequest,
  validateListTasksQuery,
  validateTaskId,
  validateUpdateTaskRequest,
} from '../validation/task-requests.js';

function validateWith(validator, { jsonBody = false } = {}) {
  return (req, res, next) => {
    try {
      if (jsonBody && !req.is('application/json')) {
        throw new ApiError(415, { code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Content-Type must be application/json' });
      }
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

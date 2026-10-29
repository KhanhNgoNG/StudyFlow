import { isUuid } from '../validation/task-fields.js';
import { taskFromRow } from './task-responses.js';

export class ApiError extends Error {
  constructor(status, body) {
    super(body.message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

export function validationError(fieldErrors, taskId) {
  return new ApiError(422, {
    code: 'VALIDATION_ERROR',
    message: 'Invalid task data',
    ...(isUuid(taskId) ? { taskId } : {}),
    fieldErrors,
  });
}

export function taskNotFoundError(taskId) {
  if (!isUuid(taskId)) throw new TypeError('TASK_NOT_FOUND requires a valid task ID');
  return new ApiError(404, { code: 'TASK_NOT_FOUND', message: 'Task not found', taskId });
}

export function versionConflictError(currentTaskRow) {
  const currentTask = taskFromRow(currentTaskRow);
  return new ApiError(409, {
    code: 'VERSION_CONFLICT',
    message: 'Task changed since baseVersion',
    taskId: currentTask.id,
    currentTask,
  });
}

export function mutationIdReusedError(taskId) {
  if (taskId !== undefined && !isUuid(taskId)) throw new TypeError('Invalid task ID for MUTATION_ID_REUSED');
  return new ApiError(409, {
    code: 'MUTATION_ID_REUSED',
    message: 'Mutation ID was already used for a different request',
    ...(taskId === undefined ? {} : { taskId }),
  });
}

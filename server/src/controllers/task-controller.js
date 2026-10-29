import { taskNotFoundError } from '../http/api-errors.js';
import { taskListResponse, taskResponse } from '../http/task-responses.js';

export function createTaskController({ taskRepository }) {
  return {
    list(req, res) {
      const { includeDeleted } = res.locals.validatedRequest;
      const rows = includeDeleted ? taskRepository.findAll() : taskRepository.findActive();
      res.status(200).json(taskListResponse(rows));
    },

    detail(req, res) {
      const { id } = res.locals.validatedRequest;
      const row = taskRepository.findById(id);
      if (row === undefined) throw taskNotFoundError(id);
      res.status(200).json(taskResponse(row));
    },
  };
}

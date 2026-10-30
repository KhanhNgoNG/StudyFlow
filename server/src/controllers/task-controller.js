import { ApiError, taskNotFoundError } from '../http/api-errors.js';
import { taskListResponse, taskResponse } from '../http/task-responses.js';
import { assertJsonContentType } from '../middleware/validate-task-request.js';
import { logMutationCompleted, logMutationFailed } from '../logging/request-log.js';

export function createTaskController({ taskRepository, taskMutationService }) {
  function write(req, res) {
    const jsonBytes = res.locals.jsonBodyLength;
    const advertisedBody = req.get('Transfer-Encoding') || Number(req.get('Content-Length') ?? 0) > 0;
    // Non-JSON content must not be fingerprinted as an absent payload.
    if (advertisedBody && jsonBytes === undefined) assertJsonContentType(req);
    let result;
    try {
      result = taskMutationService.execute({
        method: req.method,
        path: req.originalUrl.split('?')[0],
        query: req.query,
        ...(jsonBytes > 0 ? { body: req.body } : {}),
        idempotencyKey: req.get('Idempotency-Key'),
      });
    } catch (error) {
      logMutationFailed(req, error instanceof ApiError ? error.status : 500,
        error instanceof ApiError ? error.body.code : 'INTERNAL_ERROR', 'not_committed');
      throw error;
    }
    // execute returned only after SQLite COMMIT. A later transport failure must
    // not turn this known result into an alleged rollback.
    logMutationCompleted(req, result);
    res.status(result.status).json(result.body);
  }

  return {
    create: write,
    update: write,
    remove: write,
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

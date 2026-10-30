import { taskNotFoundError, versionConflictError } from '../http/api-errors.js';
import { taskFromRow, taskResponse } from '../http/task-responses.js';
import { createMutationExecutor } from '../mutations/mutation-executor.js';
import { createTaskRepository } from '../repositories/task-repository.js';

export function createTaskMutationService(database) {
  const executor = createMutationExecutor(database);
  const tasks = createTaskRepository(database);

  function existing(id, baseVersion) {
    const row = tasks.findById(id);
    if (row === undefined) throw taskNotFoundError(id);
    const current = taskFromRow(row);
    if (current.version !== baseVersion) throw versionConflictError(row);
    return current;
  }

  function requireIncrementable(current) {
    if (current.version >= Number.MAX_SAFE_INTEGER) {
      throw new TypeError('Server task version cannot be incremented safely');
    }
  }

  function requireUpdated(changes, id, baseVersion) {
    if (changes === 1) return;
    // Still inside the executor's IMMEDIATE transaction: diagnose consistently.
    existing(id, baseVersion);
    throw new Error('Conditional task update did not change exactly one row');
  }

  const apply = {
    POST({ request }) {
      const row = tasks.findById(request.task.id);
      if (row !== undefined) throw versionConflictError(row);
      tasks.insert(request.task, new Date().toISOString());
      return { status: 201, body: taskResponse(tasks.findById(request.task.id)) };
    },

    PUT({ request }) {
      const current = existing(request.task.id, request.baseVersion);
      requireIncrementable(current);
      requireUpdated(tasks.updateIfVersion(request.task, request.baseVersion, new Date().toISOString()),
        request.task.id, request.baseVersion);
      return { status: 200, body: taskResponse(tasks.findById(request.task.id)) };
    },

    DELETE({ request }) {
      const current = existing(request.id, request.baseVersion);
      // A new, version-matched DELETE on a tombstone is an accepted no-op.
      // The executor still commits a new receipt for this mutation ID.
      if (current.isDeleted) return { status: 200, body: { task: current } };
      requireIncrementable(current);
      requireUpdated(tasks.tombstoneIfVersion(request.id, request.baseVersion, new Date().toISOString()),
        request.id, request.baseVersion);
      return { status: 200, body: taskResponse(tasks.findById(request.id)) };
    },
  };

  return {
    execute(input) {
      return executor.execute(input, apply[input?.method]);
    },
  };
}

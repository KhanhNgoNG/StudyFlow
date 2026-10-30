import { isUtcTimestamp, isVersion, validateTaskFields } from '../validation/task-fields.js';

const taskKeys = ['id', 'title', 'description', 'subjectCode', 'dueDate', 'priority',
  'completed', 'isDeleted', 'version', 'serverUpdatedAt'];

function assertTask(task, label) {
  const { fieldErrors } = validateTaskFields(task, { allowDeleted: true });
  if (typeof task.title === 'string' && task.title !== task.title.trim()) fieldErrors.title = 'Not trimmed';
  if (!isVersion(task.version)) fieldErrors.version = 'Invalid server version';
  if (!isUtcTimestamp(task.serverUpdatedAt)) fieldErrors.serverUpdatedAt = 'Invalid UTC timestamp';
  if (Object.keys(fieldErrors).length) {
    throw new TypeError(`${label}: ${Object.keys(fieldErrors).join(', ')}`);
  }
}

// Used for callback results and stored receipts. Validate, never repair a snapshot.
export function assertTaskResponse(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)
    || Object.keys(body).length !== 1 || !Object.hasOwn(body, 'task')) {
    throw new TypeError('Invalid task response envelope');
  }
  const { task } = body;
  if (!task || typeof task !== 'object' || Array.isArray(task)
    || Object.keys(task).length !== taskKeys.length || !taskKeys.every((key) => Object.hasOwn(task, key))) {
    throw new TypeError('Invalid task response fields');
  }
  assertTask(task, 'Invalid task response');
}

export function taskFromRow(row) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) throw new TypeError('Invalid database task row');
  // Only SQLite INTEGER 0/1 may become JSON booleans. Boolean("0") would be wrong.
  for (const column of ['completed', 'is_deleted']) {
    if (row[column] !== 0 && row[column] !== 1) throw new TypeError(`Invalid database task: ${column}`);
  }
  const task = {
    id: row.id,
    title: row.title,
    description: row.description,
    subjectCode: row.subject_code,
    dueDate: row.due_date,
    priority: row.priority,
    completed: row.completed === 1,
    isDeleted: row.is_deleted === 1,
    version: row.version,
    serverUpdatedAt: row.server_updated_at,
  };
  // A serializer must not repair missing/invalid database data or trim it away.
  assertTask(task, 'Invalid database task');
  return task;
}

export function taskResponse(row) {
  return { task: taskFromRow(row) };
}

export function taskListResponse(rows) {
  if (!Array.isArray(rows)) throw new TypeError('Task list must be an array of database rows');
  return { tasks: rows.map(taskFromRow) };
}

import { parentPort, workerData } from 'node:worker_threads';
import { openDatabase } from '../src/database/connection.js';
import { taskResponse } from '../src/http/task-responses.js';
import { createMutationExecutor } from '../src/mutations/mutation-executor.js';

const { role, filename, gateBuffer, input, createdAt } = workerData;
const database = openDatabase(filename);
database.pragma('busy_timeout = 50');
const executor = createMutationExecutor(database);
let calls = 0;

function apply({ request }) {
  calls += 1;
  const task = request.task;
  database.prepare(`INSERT INTO tasks (id, title, description, subject_code, due_date,
    priority, completed, is_deleted, version, server_updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, ?)`).run(
    task.id, task.title, task.description, task.subjectCode, task.dueDate,
    task.priority, Number(task.completed), createdAt,
  );
  if (role === 'writer') {
    parentPort.postMessage({ event: 'applied' });
    const gate = new Int32Array(gateBuffer);
    if (Atomics.wait(gate, 0, 0, 15000) === 'timed-out') throw new Error('Writer gate timed out');
  }
  return { status: 201, body: taskResponse(database.prepare('SELECT * FROM tasks WHERE id = ?').get(task.id)) };
}

function finish(result) {
  database.close();
  parentPort.postMessage({ event: 'done', calls, result });
  parentPort.close();
}

function fail(error) {
  if (database.open) database.close();
  parentPort.postMessage({ event: 'failed', message: error.stack ?? String(error) });
  parentPort.close();
}

try {
  const result = executor.execute(input, apply);
  finish(result);
} catch (error) {
  if (role === 'contender' && error.code === 'SQLITE_BUSY') {
    parentPort.once('message', () => {
      try { finish(executor.execute(input, apply)); } catch (retryError) { fail(retryError); }
    });
    parentPort.postMessage({ event: 'busy', code: error.code, calls });
  } else {
    fail(error);
  }
}

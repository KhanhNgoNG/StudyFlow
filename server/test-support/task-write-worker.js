import { parentPort, workerData } from 'node:worker_threads';
import { openDatabase } from '../src/database/connection.js';
import { ApiError } from '../src/http/api-errors.js';
import { createTaskMutationService } from '../src/services/task-mutation-service.js';

const { role, filename, gateBuffer, input } = workerData;
let database;
let triggerCalls = 0;

function changes() {
  return database.prepare('SELECT total_changes() AS count').get().count;
}

function finish(result, error) {
  const summary = { event: 'done', result, error, triggerCalls, changes: changes(), inTransaction: database.inTransaction };
  database.close();
  parentPort.postMessage(summary);
  parentPort.close();
}

function fail(error) {
  if (database?.open) database.close();
  parentPort.postMessage({ event: 'failed', message: error.stack ?? String(error) });
  parentPort.close();
}

function execute(service) {
  try {
    finish(service.execute(input));
  } catch (error) {
    if (error instanceof ApiError) finish(undefined, { status: error.status, body: error.body });
    else fail(error);
  }
}

try {
  database = openDatabase(filename);
  database.pragma('busy_timeout = 50');
  if (role === 'writer') {
    // This connection-local trigger is a test barrier, never production behavior.
    // It holds the actual UPDATE transaction while the other worker attempts it.
    database.function('hold_write', () => {
      triggerCalls += 1;
      parentPort.postMessage({ event: 'locked' });
      if (Atomics.wait(new Int32Array(gateBuffer), 0, 0, 15000) === 'timed-out') {
        throw new Error('Writer transaction barrier timed out');
      }
      return 0;
    });
    database.exec(`CREATE TEMP TRIGGER hold_task_write BEFORE UPDATE ON tasks
      BEGIN SELECT hold_write(); END`);
  }
  const service = createTaskMutationService(database);
  try {
    finish(service.execute(input));
  } catch (error) {
    if (role === 'contender' && error.code === 'SQLITE_BUSY') {
      parentPort.once('message', () => execute(service));
      parentPort.postMessage({ event: 'busy', code: error.code, changes: changes(), inTransaction: database.inTransaction });
    } else if (error instanceof ApiError) {
      finish(undefined, { status: error.status, body: error.body });
    } else {
      fail(error);
    }
  }
} catch (error) {
  fail(error);
}

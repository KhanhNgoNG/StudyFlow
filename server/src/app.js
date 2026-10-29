import express from 'express';
import { parse as parseQuery } from 'node:querystring';
import { createHealthController } from './controllers/health-controller.js';
import { createTaskController } from './controllers/task-controller.js';
import { createErrorHandler, routeNotFound } from './middleware/error-handler.js';
import { createHealthRepository } from './repositories/health-repository.js';
import { createTaskRepository } from './repositories/task-repository.js';
import { createReadRouter } from './routes/read-routes.js';

export function createApp({ database, logger = console } = {}) {
  const app = express();

  app.disable('x-powered-by');
  // Keep simple query values, but do not silently truncate at Node's 1000-key
  // default: a late includeDeleted flag or duplicate must still be validated.
  app.set('query parser', (query) => parseQuery(query, '&', '=', { maxKeys: 0 }));

  // Valid JSON with the wrong root type belongs to semantic validation (422).
  app.use(express.json({ strict: false }));

  app.use('/api/v1', createReadRouter({
    healthController: createHealthController({ healthRepository: createHealthRepository(database), logger }),
    taskController: createTaskController({ taskRepository: createTaskRepository(database) }),
  }));

  app.use(routeNotFound);
  app.use(createErrorHandler({ logger }));

  return app;
}

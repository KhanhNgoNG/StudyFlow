import express from 'express';
import { createErrorHandler, routeNotFound } from './middleware/error-handler.js';

export function createApp({ database, logger = console } = {}) {
  const app = express();

  app.disable('x-powered-by');

  // Valid JSON with the wrong root type belongs to semantic validation (422).
  app.use(express.json({ strict: false }));

  app.get('/api/v1/health', (req, res) => {
    if (database) {
      try {
        database.prepare('SELECT id FROM tasks LIMIT 0').all();
        database.prepare('SELECT mutation_id FROM mutation_receipts LIMIT 0').all();
      } catch {
        res.status(503).end();
        return;
      }
    }
    res.status(200).end();
  });

  app.use(routeNotFound);
  app.use(createErrorHandler({ logger }));

  return app;
}

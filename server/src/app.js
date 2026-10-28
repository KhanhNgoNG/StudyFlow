import express from 'express';

export function createApp({ database } = {}) {
  const app = express();

  app.disable('x-powered-by');

  app.use(express.json());

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

  return app;
}

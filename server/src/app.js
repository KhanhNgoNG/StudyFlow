import express from 'express';

export function createApp() {
  const app = express();

  app.disable('x-powered-by');

  app.use(express.json());

  app.get('/api/v1/health', (req, res) => {
    res.status(200).end();
  });

  return app;
}
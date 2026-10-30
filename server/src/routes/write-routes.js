import { Router } from 'express';
import { requireJsonContentType } from '../middleware/validate-task-request.js';

export function createWriteRouter({ taskController }) {
  const router = Router();
  // Full business validation stays inside the executor, after receipt lookup.
  router.post('/tasks', requireJsonContentType, taskController.create);
  router.put('/tasks/:id', requireJsonContentType, taskController.update);
  router.delete('/tasks/:id', taskController.remove);
  return router;
}

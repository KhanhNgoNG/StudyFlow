import { Router } from 'express';
import { validateGetTask, validateListTasks } from '../middleware/validate-task-request.js';

export function createReadRouter({ healthController, taskController }) {
  const router = Router();
  router.get('/health', healthController);
  router.get('/tasks', validateListTasks, taskController.list);
  router.get('/tasks/:id', validateGetTask, taskController.detail);
  return router;
}

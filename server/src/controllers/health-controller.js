import { errorDiagnostic } from '../middleware/error-handler.js';

export function createHealthController({ healthRepository, logger = console }) {
  return (req, res) => {
    try {
      healthRepository.checkReady();
      res.status(200).end();
    } catch (error) {
      logger.error('Database readiness check failed', errorDiagnostic(error, req));
      res.status(503).end();
    }
  };
}

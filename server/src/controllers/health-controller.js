import { errorDiagnostic } from '../middleware/error-handler.js';
import { emitLog } from '../logging/request-log.js';

export function createHealthController({ healthRepository, logger = console }) {
  return (req, res) => {
    try {
      healthRepository.checkReady();
      res.status(200).end();
    } catch (error) {
      emitLog(logger, 'error', 'health.unavailable', { ...errorDiagnostic(error, req), status: 503, outcome: 'temporary_error' });
      res.status(503).end();
    }
  };
}

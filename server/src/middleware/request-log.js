import { beginRequestLog } from '../logging/request-log.js';

export function createRequestLogger({ logger = console } = {}) {
  return (req, res, next) => {
    beginRequestLog(req, res, logger);
    next();
  };
}

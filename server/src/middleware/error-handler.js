import { ApiError } from '../http/api-errors.js';

// Transport errors below are implementation policies, not additional sync outcomes.
function parserError(error) {
  switch (error?.type) {
    case 'entity.parse.failed':
      return { status: 400, code: 'INVALID_JSON', message: 'Malformed JSON body' };
    case 'entity.too.large':
      return { status: 413, code: 'PAYLOAD_TOO_LARGE', message: 'Request body too large' };
    case 'charset.unsupported':
    case 'encoding.unsupported':
      return { status: 415, code: 'UNSUPPORTED_MEDIA_TYPE', message: 'Unsupported request encoding' };
    case 'request.aborted':
    case 'request.size.invalid':
      return { status: 400, code: 'BAD_REQUEST', message: 'Invalid request body' };
    default:
      return undefined;
  }
}

function diagnostic(error, req) {
  return {
    time: new Date().toISOString(),
    method: req.method,
    route: req.route?.path ?? '<unmatched>',
    errorName: error instanceof Error ? error.name : 'NonErrorThrown',
    errorCode: /^(SQLITE|ERR)_[A-Z0-9_]+$/.test(error?.code) ? error.code : undefined,
    // Error messages may embed SQL/payload. Keep call sites, omit the message/body.
    stackFrames: typeof error?.stack === 'string'
      ? error.stack.split('\n').filter((line) => /^\s+at /.test(line)).join('\n')
      : undefined,
  };
}

export function routeNotFound(req, res) {
  res.status(404).json({ code: 'ROUTE_NOT_FOUND', message: 'Route not found' });
}

export function createErrorHandler({ logger = console } = {}) {
  return (error, req, res, next) => {
    if (!(error instanceof ApiError) && !parserError(error)) {
      logger.error('Unhandled request error', diagnostic(error, req));
    }
    if (res.headersSent) {
      next(error);
      return;
    }
    if (error instanceof ApiError) {
      res.status(error.status).json(error.body);
      return;
    }
    const transportError = parserError(error);
    if (transportError) {
      const { status, ...body } = transportError;
      res.status(status).json(body);
      return;
    }
    res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Internal server error' });
  };
}

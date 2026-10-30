import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { isUuid } from '../validation/task-fields.js';

const requests = new WeakMap();
const outcomes = new Set(['success', 'applied', 'replay', 'accepted_noop', 'version_conflict',
  'mutation_id_reused', 'validation_error', 'temporary_error', 'not_found', 'request_error']);
const transactions = new Set(['not_started', 'not_committed', 'committed']);
const methods = new Set(['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS']);

export function safeMethod(req) {
  return methods.has(req.method) ? req.method : '<other>';
}

export function safeRoute(req) {
  const route = req.route?.path;
  return typeof route === 'string' && route.length <= 128 && /^\/[A-Za-z0-9_/:{}.-]*$/.test(route)
    ? route : '<unmatched>';
}

function pathMetadata(req) {
  const raw = typeof req.originalUrl === 'string' ? req.originalUrl.split('?', 1)[0] : '';
  if (raw === '/api/v1/tasks' || raw === '/api/v1/health') return { path: raw };
  const detail = /^\/api\/v1\/tasks\/([^/]+)$/.exec(raw);
  if (detail) {
    let id;
    try { id = decodeURIComponent(detail[1]); } catch { /* Never log raw malformed URLs. */ }
    return { path: '/api/v1/tasks/{id}', ...(isUuid(id) ? { taskId: id } : {}) };
  }
  return { path: '<unmatched>' };
}

function identity(req) {
  const metadata = pathMetadata(req);
  const mutationId = req.get?.('Idempotency-Key');
  const taskId = metadata.taskId ?? (req.method === 'POST' && isUuid(req.body?.id) ? req.body.id : undefined);
  return {
    method: safeMethod(req), ...metadata,
    ...(isUuid(mutationId) ? { mutationId } : {}),
    ...(isUuid(taskId) ? { taskId } : {}),
  };
}

// Only known primitive fields may reach a sink. Default output is one JSON line;
// a custom sink retains the existing logger.error(event, record) adapter shape.
// Logging is best effort and must never change HTTP or transaction semantics.
export function emitLog(logger, level, event, fields = {}) {
  try {
    const record = { time: new Date().toISOString(), level, event };
    for (const key of ['requestId', 'mutationId', 'taskId']) {
      if (isUuid(fields[key])) record[key] = fields[key];
    }
    if (typeof fields.method === 'string') record.method = methods.has(fields.method) ? fields.method : '<other>';
    if (['/api/v1/tasks', '/api/v1/tasks/{id}', '/api/v1/health', '<unmatched>'].includes(fields.path)) record.path = fields.path;
    if (typeof fields.route === 'string' && (fields.route === '<unmatched>'
      || (fields.route.length <= 128 && /^\/[A-Za-z0-9_/:{}.-]*$/.test(fields.route)))) record.route = fields.route;
    if (typeof fields.errorName === 'string' && /^[A-Za-z]{1,64}$/.test(fields.errorName)) record.errorName = fields.errorName;
    if (typeof fields.errorCode === 'string' && fields.errorCode.length <= 64
      && /^(SQLITE|ERR)_[A-Z0-9_]+$/.test(fields.errorCode)) record.errorCode = fields.errorCode;
    if (typeof fields.stackFrames === 'string') record.stackFrames = fields.stackFrames;
    if (Number.isInteger(fields.status) && fields.status >= 100 && fields.status <= 599) record.status = fields.status;
    if (Number.isFinite(fields.durationMs) && fields.durationMs >= 0) record.durationMs = Math.round(fields.durationMs * 1000) / 1000;
    if (outcomes.has(fields.outcome)) record.outcome = fields.outcome;
    if (transactions.has(fields.transaction)) record.transaction = fields.transaction;
    for (const key of ['replayed', 'taskChanged']) if (typeof fields[key] === 'boolean') record[key] = fields[key];
    if (['server_finished', 'connection_closed'].includes(fields.transport)) record.transport = fields.transport;
    const sink = logger?.[level];
    if (typeof sink !== 'function') return;
    const result = logger === console ? sink.call(logger, JSON.stringify(record)) : sink.call(logger, event, record);
    if (result && typeof result.then === 'function') Promise.resolve(result).catch(() => {});
  } catch { /* A failed log is not a failed mutation or response. */ }
}

function contextFields(req, context) {
  return {
    requestId: context.requestId,
    ...identity(req),
    durationMs: performance.now() - context.started,
    ...(context.mutation ?? {}),
  };
}

function isWrite(req) {
  return ['POST', 'PUT', 'DELETE'].includes(req.method)
    && ['/api/v1/tasks', '/api/v1/tasks/{id}'].includes(pathMetadata(req).path);
}

export function outcomeFor(status, code) {
  if (code === 'VERSION_CONFLICT') return 'version_conflict';
  if (code === 'MUTATION_ID_REUSED') return 'mutation_id_reused';
  if (code === 'VALIDATION_ERROR') return 'validation_error';
  if (code === 'TASK_NOT_FOUND' || status === 404) return 'not_found';
  if (status >= 500) return 'temporary_error';
  return status >= 400 ? 'request_error' : 'success';
}

export function beginRequestLog(req, res, logger) {
  const context = { requestId: randomUUID(), started: performance.now(), logger };
  requests.set(req, context);
  res.setHeader('X-Request-ID', context.requestId);
  let finished = false;
  res.once('finish', () => {
    finished = true;
    emitLog(logger, 'info', 'request.finished', {
      ...contextFields(req, context), status: res.statusCode,
      outcome: context.mutation?.outcome ?? context.outcome ?? outcomeFor(res.statusCode),
      transport: 'server_finished',
    });
  });
  res.once('close', () => {
    if (finished) return;
    emitLog(logger, 'info', 'request.closed', {
      ...contextFields(req, context), status: res.statusCode,
      outcome: context.mutation?.outcome ?? context.outcome ?? outcomeFor(res.statusCode),
      transport: 'connection_closed',
    });
  });
}

export function logMutationCompleted(req, result) {
  const context = requests.get(req);
  if (!context) return;
  const noop = !result.replayed && req.method === 'DELETE'
    && result.body.task.version === Number(req.query.baseVersion);
  context.mutation = {
    outcome: result.replayed ? 'replay' : noop ? 'accepted_noop' : 'applied',
    transaction: 'committed', replayed: result.replayed, taskChanged: !result.replayed && !noop,
    taskId: result.body.task.id,
  };
  emitLog(context.logger, 'info', 'mutation.completed', {
    ...contextFields(req, context), status: result.status,
  });
}

export function logMutationFailed(req, status, code, transaction = 'not_started') {
  const context = requests.get(req);
  if (!context || context.mutation?.transaction === 'committed') return;
  context.outcome = outcomeFor(status, code);
  if (!isWrite(req) || context.mutation) return;
  context.mutation = { outcome: context.outcome, transaction };
  emitLog(context.logger, 'info', 'mutation.failed', {
    ...contextFields(req, context), status,
  });
}

export function requestDiagnosticFields(req) {
  const context = requests.get(req);
  return context ? contextFields(req, context) : { method: safeMethod(req), ...pathMetadata(req) };
}

export function safeStackFrames(error) {
  if (typeof error?.stack !== 'string') return undefined;
  // Retain source basenames/line numbers, not absolute paths or the error's
  // message, which can contain SQL, payload or private filesystem locations.
  return error.stack.split('\n').slice(1).flatMap((line) => {
    if (!/^\s+at /.test(line)) return [];
    const match = /([A-Za-z0-9_.-]+\.(?:[cm]?js)):(\d+):(\d+)\)?$/.exec(line);
    return match ? [`at ${match[1]}:${match[2]}:${match[3]}`] : [];
  }).slice(0, 10).join('\n');
}

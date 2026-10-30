import { createHash } from 'node:crypto';
import { validationError } from '../http/api-errors.js';

function invalid(field, message) {
  throw validationError({ [field]: message });
}

function isPlainObject(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function dataProperty(object, key, field) {
  const descriptor = Object.getOwnPropertyDescriptor(object, key);
  if (!descriptor || !Object.hasOwn(descriptor, 'value') || !descriptor.enumerable) {
    invalid(field, 'Must contain only ordinary JSON data properties');
  }
  return descriptor.value;
}

function serialize(value, field, ancestors = new Set()) {
  if (value === null) return 'null';
  if (typeof value === 'string' || typeof value === 'boolean') return JSON.stringify(value);
  if (typeof value === 'number' && Number.isFinite(value)) {
    // Preserve the sign where the parsed JSON number still distinguishes it.
    return Object.is(value, -0) ? '-0' : JSON.stringify(value);
  }
  if (typeof value !== 'object') invalid(field, 'Must contain only JSON values and finite numbers');
  if (ancestors.has(value)) invalid(field, 'Must not contain circular references');
  if (!Array.isArray(value) && !isPlainObject(value)) invalid(field, 'Must contain only plain JSON objects or arrays');
  const keys = Reflect.ownKeys(value);
  if (keys.some((key) => typeof key === 'symbol')) invalid(field, 'Must not contain symbol properties');

  ancestors.add(value);
  try {
    if (Array.isArray(value)) {
      // JSON has neither sparse arrays nor named properties on arrays.
      if (keys.length !== value.length + 1) invalid(field, 'Must not contain sparse arrays or extra array properties');
      const entries = [];
      for (let index = 0; index < value.length; index += 1) {
        entries.push(serialize(dataProperty(value, String(index), field), field, ancestors));
      }
      return `[${entries.join(',')}]`;
    }
    // Serialize directly: rebuilding an object would reorder integer-like keys
    // and assigning __proto__ could change its prototype. Never call toJSON.
    return `{${keys.sort().map((key) => `${JSON.stringify(key)}:${serialize(dataProperty(value, key, field), field, ancestors)}`).join(',')}}`;
  } finally {
    ancestors.delete(value);
  }
}

export function canonicalJson(value) {
  return serialize(value, 'body');
}

function requestField(input, key) {
  if (!Object.hasOwn(input, key)) return undefined;
  return dataProperty(input, key, key);
}

export function fingerprintRequest(input) {
  if (!isPlainObject(input)) invalid('request', 'Must be a request object');
  const method = requestField(input, 'method');
  const path = requestField(input, 'path');
  const suppliedQuery = requestField(input, 'query');
  const query = suppliedQuery === undefined ? {} : suppliedQuery;
  const body = requestField(input, 'body');
  if (!['POST', 'PUT', 'DELETE'].includes(method)) invalid('method', 'Must be POST, PUT or DELETE');
  if (typeof path !== 'string' || !path.startsWith('/') || /[\u0000-\u0020\u007f?#]/.test(path)) {
    invalid('path', 'Must be an absolute HTTP path without a query or fragment');
  }
  if (!isPlainObject(query)) invalid('query', 'Must be an object of HTTP query strings');
  const serializedQuery = serialize(query, 'query');
  for (const key of Object.keys(query)) {
    const value = dataProperty(query, key, 'query');
    if (typeof value === 'string') continue;
    if (!Array.isArray(value)) {
      invalid('query', 'Query values must be strings or arrays of repeated strings');
    }
    for (let index = 0; index < value.length; index += 1) {
      if (typeof dataProperty(value, String(index), 'query') !== 'string') {
        invalid('query', 'Query values must be strings or arrays of repeated strings');
      }
    }
  }
  const bodyPart = body === undefined ? '' : `"body":${serialize(body, 'body')},`;
  const canonicalRequest = `{${bodyPart}"method":${JSON.stringify(method)},"path":${JSON.stringify(path)},"query":${serializedQuery}}`;
  return createHash('sha256').update(canonicalRequest, 'utf8').digest('hex');
}

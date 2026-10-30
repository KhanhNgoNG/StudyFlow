import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { parseDocument } from 'yaml';

// Test-only, limited checks for the schema vocabulary in StudyFlow's OpenAPI.
// This is not an OpenAPI validator or a complete JSON Schema/RFC validator.
// Unknown schema keywords fail instead of silently weakening these checks.
const schemaKeys = new Set([
  '$ref', 'allOf', 'oneOf', 'type', 'enum', 'required', 'nullable', 'properties',
  'additionalProperties', 'items', 'minLength', 'maxLength', 'minimum', 'maximum',
  'format', 'description', 'default', 'example', 'title', 'deprecated',
]);
const types = new Set(['object', 'array', 'string', 'boolean', 'integer', 'number']);
const methods = new Set(['get', 'post', 'put', 'delete', 'patch', 'head', 'options', 'trace']);
const contractUrl = new URL('../../contracts/openapi.yaml', import.meta.url);

export function parseContractYaml(text) {
  const document = parseDocument(text, { uniqueKeys: true, strict: true });
  assert.equal(document.errors.length, 0, document.errors.map(String).join('\n'));
  assert.equal(document.warnings.length, 0, document.warnings.map(String).join('\n'));
  return document.toJS();
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function calendarDate(value) {
  if (value.length !== 10 || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const lengths = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= lengths[month - 1];
}

function assertFormat(value, format, label) {
  if (format === 'uuid') {
    assert.match(value, /^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$/, `${label}: uuid`);
    assert.equal(value.length, 36, `${label}: uuid length`);
  } else if (format === 'date') {
    assert.ok(calendarDate(value), `${label}: real YYYY-MM-DD date`);
  } else if (format === 'date-time') {
    // The current contract emits UTC only. Leap seconds, offsets and the full
    // RFC 3339 grammar are outside this intentionally limited test helper.
    const parts = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z$/.exec(value);
    assert.ok(parts && parts[0] === value && calendarDate(parts[1])
      && Number(parts[2]) < 24 && Number(parts[3]) < 60 && Number(parts[4]) < 60,
    `${label}: UTC date-time`);
  } else {
    assert.fail(`Unsupported schema format: ${format}`);
  }
}

export function createContract(spec) {
  function resolve(ref) {
    assert.equal(typeof ref, 'string');
    assert.ok(ref.startsWith('#/'), `Only local $refs are supported: ${ref}`);
    let result = spec;
    for (const token of ref.slice(2).split('/')) {
      assert.ok(!/~(?:[^01]|$)/.test(token), `Invalid JSON pointer escape: ${ref}`);
      const key = token.replace(/~1/g, '/').replace(/~0/g, '~');
      assert.ok(isObject(result) && Object.hasOwn(result, key), `Unresolved $ref: ${ref}`);
      result = result[key];
    }
    assert.ok(isObject(result), `$ref must point to an object: ${ref}`);
    return result;
  }

  function dereference(value) {
    const visited = new Set();
    while (value.$ref) {
      assert.ok(!visited.has(value.$ref), `Cyclic $ref: ${value.$ref}`);
      visited.add(value.$ref);
      assert.deepEqual(Object.keys(value), ['$ref'], 'Reference Object siblings are not supported');
      value = resolve(value.$ref);
    }
    return value;
  }

  function assertSupportedSchema(schema, seen = new Set()) {
    assert.ok(isObject(schema), 'Schema must be an object');
    if (seen.has(schema)) return;
    seen.add(schema);
    for (const key of Object.keys(schema)) {
      assert.ok(schemaKeys.has(key), `Unsupported schema keyword: ${key}`);
    }
    if (schema.$ref) {
      assert.deepEqual(Object.keys(schema), ['$ref'], 'Schema $ref siblings are not supported');
      assertSupportedSchema(resolve(schema.$ref), seen);
    }
    if (schema.type !== undefined) assert.ok(types.has(schema.type), `Unsupported schema type: ${schema.type}`);
    if (schema.format !== undefined) assert.ok(['uuid', 'date', 'date-time'].includes(schema.format), `Unsupported format: ${schema.format}`);
    if (schema.nullable !== undefined) assert.equal(typeof schema.nullable, 'boolean');
    if (schema.enum !== undefined) assert.ok(Array.isArray(schema.enum) && schema.enum.length > 0);
    for (const keyword of ['allOf', 'oneOf']) {
      if (schema[keyword] === undefined) continue;
      assert.ok(Array.isArray(schema[keyword]) && schema[keyword].length > 0, keyword);
      for (const item of schema[keyword]) assertSupportedSchema(item, seen);
    }
    if (schema.required !== undefined) {
      assert.ok(Array.isArray(schema.required) && schema.required.every((key) => typeof key === 'string'));
      assert.equal(new Set(schema.required).size, schema.required.length, 'Duplicate required property');
    }
    if (schema.properties !== undefined) {
      assert.ok(isObject(schema.properties));
      for (const item of Object.values(schema.properties)) assertSupportedSchema(item, seen);
    }
    if (schema.additionalProperties !== undefined) {
      assert.ok(typeof schema.additionalProperties === 'boolean' || isObject(schema.additionalProperties));
      if (isObject(schema.additionalProperties)) assertSupportedSchema(schema.additionalProperties, seen);
    }
    if (schema.items !== undefined) assertSupportedSchema(schema.items, seen);
    for (const keyword of ['minLength', 'maxLength']) {
      if (schema[keyword] !== undefined) assert.ok(Number.isSafeInteger(schema[keyword]) && schema[keyword] >= 0, keyword);
    }
    for (const keyword of ['minimum', 'maximum']) {
      if (schema[keyword] !== undefined) assert.ok(Number.isFinite(schema[keyword]), keyword);
    }
  }

  function validate(value, schema, label) {
    if (schema.$ref) return validate(value, resolve(schema.$ref), label);
    if (schema.nullable === true && value === null) return;
    for (const item of schema.allOf ?? []) validate(value, item, label);
    if (schema.oneOf) {
      const matches = schema.oneOf.filter((item) => {
        try { validate(value, item, label); return true; } catch (error) {
          if (!(error instanceof assert.AssertionError)) throw error;
          return false;
        }
      });
      assert.equal(matches.length, 1, `${label}: exactly one oneOf branch`);
    }
    const matchesType = {
      object: isObject, array: Array.isArray, string: (item) => typeof item === 'string',
      boolean: (item) => typeof item === 'boolean',
      integer: (item) => Number.isInteger(item) && Number.isFinite(item),
      number: (item) => typeof item === 'number' && Number.isFinite(item),
    };
    if (schema.type) assert.ok(matchesType[schema.type](value), `${label}: expected ${schema.type}`);
    if (schema.enum) {
      assert.ok(schema.enum.some((candidate) => {
        try { assert.deepEqual(value, candidate); return true; } catch { return false; }
      }), `${label}: enum`);
    }
    if (isObject(value)) {
      for (const key of schema.required ?? []) assert.ok(Object.hasOwn(value, key), `${label}.${key}: required`);
      for (const [key, item] of Object.entries(value)) {
        const property = schema.properties && Object.hasOwn(schema.properties, key) ? schema.properties[key] : undefined;
        if (property) validate(item, property, `${label}.${key}`);
        else if (schema.additionalProperties === false) assert.fail(`${label}.${key}: additional property`);
        else if (isObject(schema.additionalProperties)) validate(item, schema.additionalProperties, `${label}.${key}`);
      }
    }
    if (Array.isArray(value) && schema.items) {
      value.forEach((item, index) => validate(item, schema.items, `${label}[${index}]`));
    }
    if (typeof value === 'string') {
      const length = [...value].length;
      if (schema.minLength !== undefined) assert.ok(length >= schema.minLength, `${label}: minLength`);
      if (schema.maxLength !== undefined) assert.ok(length <= schema.maxLength, `${label}: maxLength`);
      if (schema.format) assertFormat(value, schema.format, label);
    }
    if (typeof value === 'number') {
      if (schema.minimum !== undefined) assert.ok(value >= schema.minimum, `${label}: minimum`);
      if (schema.maximum !== undefined) assert.ok(value <= schema.maximum, `${label}: maximum`);
    }
  }

  function assertSchema(value, schema, label = '$') {
    assertSupportedSchema(schema);
    validate(value, schema, label);
  }

  function operation(method, path) {
    const op = spec.paths[path]?.[method.toLowerCase()];
    assert.ok(op, `Operation not documented: ${method} ${path}`);
    return op;
  }

  function assertRequest(method, path, { headers = {}, pathParameters = {}, query = {}, body } = {}) {
    const op = operation(method, path);
    const normalizedHeaders = Object.fromEntries(Object.entries(headers).map(([key, value]) => [key.toLowerCase(), value]));
    for (const raw of [...(spec.paths[path].parameters ?? []), ...(op.parameters ?? [])]) {
      const parameter = dereference(raw);
      let value = parameter.in === 'header' ? normalizedHeaders[parameter.name.toLowerCase()]
        : parameter.in === 'path' ? pathParameters[parameter.name] : query[parameter.name];
      if (value === undefined) {
        assert.ok(!parameter.required, `${parameter.in} ${parameter.name}: required`);
        continue;
      }
      // OpenAPI declares the logical type; wire query/header values are strings.
      if (parameter.schema.type === 'integer' && typeof value === 'string' && /^\d+$/.test(value)) value = Number(value);
      if (parameter.schema.type === 'boolean' && ['true', 'false'].includes(value)) value = value === 'true';
      assertSchema(value, parameter.schema, `${parameter.in} ${parameter.name}`);
    }
    if (op.requestBody) {
      const request = dereference(op.requestBody);
      assert.ok(body !== undefined || !request.required, 'Request body required');
      if (body !== undefined) {
        assert.match(normalizedHeaders['content-type'] ?? '', /^application\/json(?:;|$)/i);
        assertSchema(body, request.content['application/json'].schema, 'request body');
      }
    } else assert.equal(body, undefined, 'Operation has no request body');
  }

  function assertResponse(method, path, response, body) {
    const op = operation(method, path);
    const described = op.responses[String(response.status)] ?? op.responses[`${Math.floor(response.status / 100)}XX`];
    assert.ok(described, `Undocumented response: ${method} ${path} ${response.status}`);
    const definition = dereference(described);
    const json = definition.content?.['application/json'];
    if (json) {
      assert.match(response.headers.get('content-type') ?? '', /^application\/json; charset=utf-8$/i);
      assertSchema(body, json.schema, 'response body');
    }
    // No content schema (health/5XX/429) makes no promise about a body shape.
    return definition;
  }

  return { spec, resolve, dereference, assertSupportedSchema, assertSchema, operation, assertRequest, assertResponse };
}

export const contract = createContract(parseContractYaml(readFileSync(contractUrl, 'utf8')));

export function readExample(name) {
  const source = readFileSync(new URL(`./examples/${name}.json`, contractUrl), 'utf8');
  const value = JSON.parse(source); // Require JSON, not merely YAML-compatible text.
  parseContractYaml(source); // Also reject duplicate mapping keys in JSON objects.
  return value;
}

export function inspectContract() {
  const { spec, resolve, assertSupportedSchema } = contract;
  let references = 0;
  let externalExamples = 0;
  const schemaObjects = new Set();
  function walk(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref) { resolve(value.$ref); references += 1; }
    if (value.schema) schemaObjects.add(value.schema);
    if (value.externalValue) {
      const url = new URL(value.externalValue, contractUrl);
      assert.equal(url.protocol, 'file:', 'External examples must be repository files');
      assert.ok(fileURLToPath(url).startsWith(fileURLToPath(new URL('./examples/', contractUrl))), 'Example must stay in contracts/examples');
      JSON.parse(readFileSync(url, 'utf8'));
      externalExamples += 1;
    }
    for (const item of Object.values(value)) walk(item);
  }
  walk(spec);
  for (const schema of Object.values(spec.components.schemas)) schemaObjects.add(schema);
  for (const schema of schemaObjects) assertSupportedSchema(schema);
  return { references, externalExamples, methods };
}

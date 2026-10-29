const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const subjectCodes = ['MOBILE', 'DATABASE', 'GENERAL', 'OTHER'];
const priorities = ['LOW', 'MEDIUM', 'HIGH'];
const fieldTypes = {
  id: 'string',
  title: 'string',
  description: 'string',
  subjectCode: 'string',
  dueDate: 'string',
  priority: 'string',
  completed: 'boolean',
  isDeleted: 'boolean',
};

export function isUuid(value) {
  return typeof value === 'string' && value.length === 36 && uuidPattern.test(value);
}

export function isCalendarDate(value) {
  if (typeof value !== 'string' || value.length !== 10 || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}

export function isUtcTimestamp(value) {
  if (typeof value !== 'string') return false;
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?Z$/.exec(value);
  return Boolean(match && match[0] === value && isCalendarDate(match[1])
    && Number(match[2]) <= 23 && Number(match[3]) <= 59 && Number(match[4]) <= 59);
}

export function isVersion(value, minimum = 1) {
  return Number.isSafeInteger(value) && value >= minimum;
}

// Count Unicode code points, as SQLite length(TEXT) does (except at NUL).
// Reject NUL and unpaired surrogates instead of silently changing stored text.
function textError(value, maximum, minimum = 0) {
  if (!value.isWellFormed()) return 'Must contain well-formed Unicode';
  if (value.includes('\u0000')) return 'Must not contain U+0000';
  const length = [...value].length;
  if (length < minimum || length > maximum) {
    return `Must contain ${minimum} to ${maximum} Unicode code points`;
  }
  return undefined;
}

export function validateTaskFields(body, { allowDeleted = false } = {}) {
  const fieldErrors = {};
  if (body === undefined) return { fieldErrors: { body: 'Body is required' } };
  if (body === null) return { fieldErrors: { body: 'Body must not be null' } };
  if (typeof body !== 'object' || Array.isArray(body)) {
    return { fieldErrors: { body: 'Body must be a JSON object' } };
  }

  // Build a new whitelist; never mutate the original request snapshot.
  const task = {};
  for (const [field, type] of Object.entries(fieldTypes)) {
    if (!Object.hasOwn(body, field)) {
      fieldErrors[field] = `${field} is required`;
    } else if (body[field] === null) {
      if (field === 'dueDate') task[field] = null;
      else fieldErrors[field] = `${field} must not be null`;
    } else if (typeof body[field] !== type) {
      fieldErrors[field] = `${field} must be a ${type}`;
    } else {
      task[field] = body[field];
    }
  }

  if (typeof task.id === 'string' && !isUuid(task.id)) fieldErrors.id = 'Must be a UUID';
  for (const [field, maximum, minimum] of [['title', 120, 1], ['description', 2000, 0]]) {
    if (typeof task[field] !== 'string') continue;
    if (field === 'title') task.title = task.title.trim();
    const error = textError(task[field], maximum, minimum);
    if (error) fieldErrors[field] = error;
  }
  if (typeof task.subjectCode === 'string' && !subjectCodes.includes(task.subjectCode)) {
    fieldErrors.subjectCode = 'Must be MOBILE, DATABASE, GENERAL or OTHER';
  }
  if (typeof task.priority === 'string' && !priorities.includes(task.priority)) {
    fieldErrors.priority = 'Must be LOW, MEDIUM or HIGH';
  }
  if (typeof task.dueDate === 'string' && !isCalendarDate(task.dueDate)) {
    fieldErrors.dueDate = 'Must be a real date in YYYY-MM-DD format or null';
  }
  if (!allowDeleted && task.isDeleted === true) fieldErrors.isDeleted = 'Must be false; use DELETE to delete';
  return { task, fieldErrors };
}

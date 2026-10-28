CREATE TABLE tasks (
  id TEXT PRIMARY KEY NOT NULL CHECK (
    length(id) = 36
    AND substr(id, 9, 1) = '-' AND substr(id, 14, 1) = '-'
    AND substr(id, 19, 1) = '-' AND substr(id, 24, 1) = '-'
    AND length(replace(id, '-', '')) = 32
    AND lower(replace(id, '-', '')) NOT GLOB '*[^0-9a-f]*'
  ),
  title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 120),
  description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
  subject_code TEXT NOT NULL CHECK (subject_code IN ('MOBILE', 'DATABASE', 'GENERAL', 'OTHER')),
  due_date TEXT CHECK (due_date IS NULL OR (
    length(due_date) = 10
    AND due_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
    AND date(due_date, '+0 days') IS NOT NULL
    AND date(due_date, '+0 days') = due_date
  )),
  priority TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
  completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
  is_deleted INTEGER NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),
  version INTEGER NOT NULL CHECK (version >= 1),
  server_updated_at TEXT NOT NULL CHECK (
    substr(server_updated_at, 11, 1) = 'T'
    AND substr(server_updated_at, -1) = 'Z'
    AND julianday(server_updated_at) IS NOT NULL
  )
) STRICT;

CREATE TABLE mutation_receipts (
  mutation_id TEXT PRIMARY KEY NOT NULL CHECK (
    length(mutation_id) = 36
    AND substr(mutation_id, 9, 1) = '-' AND substr(mutation_id, 14, 1) = '-'
    AND substr(mutation_id, 19, 1) = '-' AND substr(mutation_id, 24, 1) = '-'
    AND length(replace(mutation_id, '-', '')) = 32
    AND lower(replace(mutation_id, '-', '')) NOT GLOB '*[^0-9a-f]*'
  ),
  task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE RESTRICT,
  method TEXT NOT NULL CHECK (method IN ('POST', 'PUT', 'DELETE')),
  path TEXT NOT NULL CHECK (substr(path, 1, 1) = '/'),
  request_hash TEXT NOT NULL CHECK (
    length(request_hash) = 64 AND request_hash NOT GLOB '*[^0-9a-f]*'
  ),
  response_status INTEGER NOT NULL CHECK (
    (method = 'POST' AND response_status = 201)
    OR (method IN ('PUT', 'DELETE') AND response_status = 200)
  ),
  response_body TEXT NOT NULL CHECK (
    json_valid(response_body) AND json_type(response_body) = 'object'
  ),
  created_at TEXT NOT NULL CHECK (
    substr(created_at, 11, 1) = 'T' AND substr(created_at, -1) = 'Z'
    AND julianday(created_at) IS NOT NULL
  )
) STRICT;

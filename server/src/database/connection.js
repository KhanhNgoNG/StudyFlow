import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const serverRoot = fileURLToPath(new URL('../../', import.meta.url));

export function resolveDatabasePath(filename) {
  if (typeof filename !== 'string' || filename.trim() === '') {
    throw new Error('DATABASE_PATH không được rỗng.');
  }
  if (filename === ':memory:') return filename;
  return isAbsolute(filename) ? filename : resolve(serverRoot, filename);
}

export function openDatabase(filename, { readonly = false, fileMustExist = false } = {}) {
  const path = resolveDatabasePath(filename);
  if (!readonly && !fileMustExist && path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new Database(path, { readonly, fileMustExist: readonly || fileMustExist, timeout: 5000 });
  try {
    db.pragma('foreign_keys = ON');
    if (!readonly) {
      db.pragma('journal_mode = WAL');
      db.pragma('synchronous = FULL');
    }
    return db;
  } catch (error) {
    db.close();
    throw error;
  }
}

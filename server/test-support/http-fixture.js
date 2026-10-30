import { once } from 'node:events';
import { readFileSync } from 'node:fs';
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/database/connection.js';
import { migrateDatabase } from '../src/database/migrate.js';

export function example(name) {
  return JSON.parse(readFileSync(new URL(`../../contracts/examples/${name}.json`, import.meta.url), 'utf8'));
}

export function snapshot(database) {
  return {
    tasks: database.prepare('SELECT * FROM tasks ORDER BY id').all(),
    receipts: database.prepare('SELECT * FROM mutation_receipts ORDER BY mutation_id').all(),
    migrations: database.prepare('SELECT * FROM schema_migrations ORDER BY name').all(),
  };
}

// A fresh schema and the production app per test; never reads .env or the demo DB.
export async function createHttpFixture(t) {
  let database;
  let server;
  const logs = [];
  t.after(async () => {
    try {
      if (server?.listening) {
        await new Promise((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
          server.closeAllConnections();
        });
      }
    } finally {
      if (database?.open) database.close();
    }
  });
  database = openDatabase(':memory:');
  migrateDatabase(database);
  const app = createApp({ database, logger: { error: (...args) => logs.push(args) } });
  server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    database,
    logs,
    request: (path, options = {}) => fetch(base + path, { signal: AbortSignal.timeout(5000), ...options }),
  };
}

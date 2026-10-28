import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const migrationDirectory = fileURLToPath(new URL('./migrations/', import.meta.url));

export function migrateDatabase(db, directory = migrationDirectory) {
  const files = readdirSync(directory).filter((name) => /^\d+_.+\.sql$/.test(name)).sort();
  const migrations = files.map((name) => {
    const sql = readFileSync(`${directory}/${name}`, 'utf8');
    return { name, sql, checksum: createHash('sha256').update(sql).digest('hex') };
  });

  return db.transaction(() => {
    db.exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
      name TEXT PRIMARY KEY NOT NULL,
      checksum TEXT NOT NULL,
      applied_at TEXT NOT NULL
    ) STRICT`);
    const applied = db.prepare('SELECT name, checksum FROM schema_migrations ORDER BY name').all();
    // Applied migrations must remain an unchanged prefix of the migration files.
    for (const [index, row] of applied.entries()) {
      const migration = migrations[index];
      if (!migration || row.name !== migration.name || row.checksum !== migration.checksum) {
        throw new Error(`Migration đã chạy bị sửa, thiếu hoặc sai thứ tự: ${row.name}`);
      }
    }
    const pending = migrations.slice(applied.length);
    const record = db.prepare('INSERT INTO schema_migrations (name, checksum, applied_at) VALUES (?, ?, ?)');
    for (const migration of pending) {
      db.exec(migration.sql);
      record.run(migration.name, migration.checksum, new Date().toISOString());
    }
    return pending.map(({ name }) => name);
  }).immediate();
}

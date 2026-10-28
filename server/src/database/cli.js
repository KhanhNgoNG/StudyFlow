import { config } from '../config/env.js';
import { openDatabase, resolveDatabasePath } from './connection.js';
import { migrateDatabase } from './migrate.js';
import { resetDatabase, seedDatabase } from './seed.js';

const command = process.argv[2];
let db;
try {
  if (!['migrate', 'seed', 'inspect', 'reset'].includes(command)) {
    throw new Error('Lệnh database không hợp lệ.');
  }
  if (command === 'reset' && (!process.argv.includes('--confirm-reset')
    || !['development', 'test'].includes(config.nodeEnv))) {
    throw new Error('Reset chỉ được phép ở development/test với --confirm-reset.');
  }
  console.log(`Database: ${resolveDatabasePath(config.databasePath)}`);
  db = openDatabase(config.databasePath, {
    readonly: command === 'inspect',
    fileMustExist: command !== 'migrate',
  });
  if (command === 'migrate') {
    const applied = migrateDatabase(db);
    console.log(applied.length ? `Đã áp dụng: ${applied.join(', ')}` : 'Schema đã cập nhật, không có migration mới.');
  } else if (command === 'inspect') {
    console.table(db.prepare('SELECT name, applied_at FROM schema_migrations ORDER BY name').all());
    console.table(db.prepare(`SELECT id, title, subject_code, due_date,
      completed, is_deleted, version FROM tasks ORDER BY id`).all());
    console.log(`Receipt: ${db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count}`);
    console.log(`Integrity: ${db.pragma('integrity_check', { simple: true })}`);
  } else {
    db.prepare('SELECT id FROM tasks LIMIT 0').all();
    db.prepare('SELECT mutation_id FROM mutation_receipts LIMIT 0').all();
    if (command === 'seed') {
      const result = seedDatabase(db);
      console.log(result.skipped ? 'Bộ seed đã có; không thay đổi dữ liệu.' : `Đã thêm ${result.inserted} task mẫu.`);
    } else {
      resetDatabase(db, { environment: config.nodeEnv, confirmed: true });
      console.log('Đã xóa task và receipt; giữ nguyên schema. Chạy db:seed nếu cần dữ liệu mẫu.');
    }
  }
} catch (error) {
  console.error(`Database thất bại: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (db?.open) db.close();
}

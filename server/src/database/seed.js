import { readFileSync } from 'node:fs';

export const demoTasks = JSON.parse(readFileSync(new URL('./seeds/demo-tasks.json', import.meta.url), 'utf8'));

export function seedDatabase(db) {
  return db.transaction(() => {
    const taskCount = db.prepare('SELECT count(*) AS count FROM tasks').get().count;
    const receiptCount = db.prepare('SELECT count(*) AS count FROM mutation_receipts').get().count;
    if (taskCount !== 0 || receiptCount !== 0) {
      const exists = db.prepare('SELECT 1 FROM tasks WHERE id = ?');
      if (demoTasks.every((task) => exists.get(task.id))) return { inserted: 0, skipped: true };
      throw new Error('Database đã có dữ liệu; từ chối seed để không trộn hoặc ghi đè dữ liệu hiện tại.');
    }
    const insert = db.prepare(`INSERT INTO tasks (
      id, title, description, subject_code, due_date, priority,
      completed, is_deleted, version, server_updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
    for (const task of demoTasks) {
      insert.run(task.id, task.title, task.description, task.subjectCode,
        task.dueDate, task.priority, Number(task.completed), Number(task.isDeleted),
        task.version, task.serverUpdatedAt);
    }
    return { inserted: demoTasks.length, skipped: false };
  }).immediate();
}

export function resetDatabase(db, { environment, confirmed }) {
  if (!confirmed || !['development', 'test'].includes(environment)) {
    throw new Error('Reset chỉ được phép ở development/test với --confirm-reset.');
  }
  db.transaction(() => {
    db.prepare('DELETE FROM mutation_receipts').run();
    db.prepare('DELETE FROM tasks').run();
  }).immediate();
}

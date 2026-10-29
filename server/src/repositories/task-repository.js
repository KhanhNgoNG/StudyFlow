const taskColumns = `id, title, description, subject_code, due_date, priority,
  completed, is_deleted, version, server_updated_at`;

export function createTaskRepository(database) {
  return {
    findAll() {
      // One statement materializes the whole result from one SQLite read snapshot.
      // ID order is only a stable transport/test order, not a business ranking.
      return database.prepare(`SELECT ${taskColumns} FROM tasks ORDER BY id`).all();
    },

    findActive() {
      return database.prepare(`SELECT ${taskColumns} FROM tasks WHERE is_deleted = 0 ORDER BY id`).all();
    },

    findById(id) {
      return database.prepare(`SELECT ${taskColumns} FROM tasks WHERE id = ?`).get(id);
    },
  };
}

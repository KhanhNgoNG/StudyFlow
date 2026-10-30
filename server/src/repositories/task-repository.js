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

    insert(task, serverUpdatedAt) {
      database.prepare(`INSERT INTO tasks (${taskColumns})
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, 1, ?)`).run(
        task.id, task.title, task.description, task.subjectCode, task.dueDate,
        task.priority, Number(task.completed), serverUpdatedAt,
      );
    },

    updateIfVersion(task, baseVersion, serverUpdatedAt) {
      return database.prepare(`UPDATE tasks SET title = ?, description = ?, subject_code = ?,
        due_date = ?, priority = ?, completed = ?, is_deleted = 0,
        version = version + 1, server_updated_at = ? WHERE id = ? AND version = ?`).run(
        task.title, task.description, task.subjectCode, task.dueDate, task.priority,
        Number(task.completed), serverUpdatedAt, task.id, baseVersion,
      ).changes;
    },

    tombstoneIfVersion(id, baseVersion, serverUpdatedAt) {
      return database.prepare(`UPDATE tasks SET is_deleted = 1, version = version + 1,
        server_updated_at = ? WHERE id = ? AND version = ?`).run(serverUpdatedAt, id, baseVersion).changes;
    },
  };
}

export function createHealthRepository(database) {
  return {
    checkReady() {
      if (!database?.open) throw new Error('Database connection is not available');
      database.prepare('SELECT id FROM tasks LIMIT 0').all();
      database.prepare('SELECT mutation_id FROM mutation_receipts LIMIT 0').all();
    },
  };
}

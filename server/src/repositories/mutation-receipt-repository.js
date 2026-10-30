export function createMutationReceiptRepository(database) {
  return {
    findByMutationId(mutationId) {
      return database.prepare(`SELECT mutation_id, task_id, method, path, request_hash,
        response_status, response_body, created_at
        FROM mutation_receipts WHERE mutation_id = ?`).get(mutationId);
    },

    insert(receipt) {
      database.prepare(`INSERT INTO mutation_receipts (
        mutation_id, task_id, method, path, request_hash,
        response_status, response_body, created_at
      ) VALUES (@mutation_id, @task_id, @method, @path, @request_hash,
        @response_status, @response_body, @created_at)`).run(receipt);
    },
  };
}

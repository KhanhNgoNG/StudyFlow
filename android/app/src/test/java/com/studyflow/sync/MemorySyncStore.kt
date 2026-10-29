package com.studyflow.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transactional test storage; reconciliation remains the real engine's implementation. */
internal class MemorySyncStore : SyncStore {
    override val coordinator = SyncCoordinator()
    private val mutex = Mutex()
    private var rows = linkedMapOf<String, LocalSyncRecord>()
    private var outbox = linkedMapOf<String, OutboxMutation>()
    private var snapshots = linkedMapOf<String, StoredConflict>()
    var failTaskWrite = false
    override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T = mutex.withLock {
        val nextRows = LinkedHashMap(rows)
        val nextOutbox = LinkedHashMap(outbox)
        val nextConflicts = LinkedHashMap(snapshots)
        val session = object : SyncTransaction {
            override suspend fun tasks() = nextRows.values.toList()
            override suspend fun task(id: String) = nextRows[id]
            override suspend fun putTask(task: LocalSyncRecord) {
                if (failTaskWrite) throw SyncStorageException(IllegalStateException("Injected test write failure"))
                nextRows[task.snapshot.id] = task
            }
            override suspend fun removeTask(id: String) { nextRows.remove(id) }
            override suspend fun mutations() = nextOutbox.values.toList()
            override suspend fun mutation(taskId: String) = nextOutbox[taskId]
            override suspend fun putMutation(mutation: OutboxMutation) { nextOutbox[mutation.taskId] = mutation }
            override suspend fun removeMutation(taskId: String) { nextOutbox.remove(taskId) }
            override suspend fun conflicts() = nextConflicts.values.toList()
            override suspend fun conflict(taskId: String) = nextConflicts[taskId]
            override suspend fun putConflict(conflict: StoredConflict) { nextConflicts[conflict.taskId] = conflict }
        }
        val result = block(session)
        rows = nextRows
        outbox = nextOutbox
        snapshots = nextConflicts
        result
    }
}

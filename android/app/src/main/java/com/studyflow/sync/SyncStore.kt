package com.studyflow.sync

import com.studyflow.sync.api.*
import kotlinx.coroutines.sync.Mutex

/** One per client database, shared even by separately constructed engines. */
class SyncCoordinator { internal val mutex = Mutex() }
data class LocalSyncRecord(
    val snapshot: TaskSnapshot, val baseVersion: Long, val localRevision: Long,
    val localUpdatedAt: Long, val pending: Boolean,
)
enum class OutboxState { READY, RETRY, VALIDATION, CONTRACT, CONFLICT }
data class OutboxMutation(
    val mutationId: String, val taskId: String, val operation: MutationOperation,
    val baseVersion: Long, val localRevision: Long, val snapshot: TaskSnapshot,
    val state: OutboxState = OutboxState.READY, val attempts: Int = 0, val lastError: String? = null,
    val retryNotBefore: Long? = null,
) {
    fun request() = MutationRequest(mutationId, taskId, operation, baseVersion,
        if (operation == MutationOperation.DELETE) null else TaskWriteBody.from(snapshot, baseVersion))
}
data class StoredConflict(
    val taskId: String, val local: LocalSyncRecord, val server: ApiTask, val mutationId: String?,
)
/** Atomic local operations only: never call the API inside this block. */
interface SyncStore {
    val coordinator: SyncCoordinator
    suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T
}
interface SyncTransaction {
    suspend fun tasks(): List<LocalSyncRecord>
    suspend fun task(id: String): LocalSyncRecord?
    suspend fun putTask(task: LocalSyncRecord)
    suspend fun removeTask(id: String)
    suspend fun mutations(): List<OutboxMutation>
    suspend fun mutation(taskId: String): OutboxMutation?
    suspend fun putMutation(mutation: OutboxMutation)
    suspend fun removeMutation(taskId: String)
    suspend fun conflicts(): List<StoredConflict>
    suspend fun conflict(taskId: String): StoredConflict?
    suspend fun putConflict(conflict: StoredConflict)
}
class SyncStorageException(cause: Throwable) : RuntimeException("Sync storage failed", cause)
enum class SyncStage { PREPARE, PUSH, ACKNOWLEDGE, PULL, RECONCILE, SUMMARY }
data class SyncIssue(val stage: SyncStage, val code: String, val taskId: String? = null)
data class SyncRunResult(
    val runId: String, val acknowledged: Int, val pending: Int?, val conflicts: Int?, val issues: List<SyncIssue>,
) {
    val complete: Boolean get() = pending == 0 && conflicts == 0 && issues.isEmpty()
}

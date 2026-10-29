package com.studyflow.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.studyflow.data.local.database.StudyFlowDatabase
import com.studyflow.data.local.entity.*
import com.studyflow.domain.model.*
import com.studyflow.sync.*
import com.studyflow.sync.api.MutationOperation
import com.studyflow.sync.api.TaskSnapshot
import java.time.DateTimeException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

class RoomSyncStore(private val database: StudyFlowDatabase) : SyncStore {
    override val coordinator get() = database.syncCoordinator
    private val tasks = database.taskDao()
    private val sync = database.syncDao()
    override suspend fun <T> transaction(block: suspend SyncTransaction.() -> T): T = try {
        database.withTransaction { block(session) }
    } catch (failure: SQLiteException) {
        throw SyncStorageException(failure)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: IllegalStateException) {
        throw SyncStorageException(failure)
    } catch (failure: IllegalArgumentException) {
        throw SyncStorageException(failure)
    } catch (failure: DateTimeException) {
        throw SyncStorageException(failure)
    }

    private val session = object : SyncTransaction {
        override suspend fun tasks() = tasks.getStored().map { it.toRecord() }
        override suspend fun task(id: String) = tasks.getStoredById(id)?.toRecord()
        override suspend fun putTask(task: LocalSyncRecord) = tasks.putFromSync(task.toEntity())
        override suspend fun removeTask(id: String) = tasks.removeUnsentFromSync(id)
        override suspend fun mutations() = sync.outbox().map { it.toMutation() }
        override suspend fun mutation(taskId: String) = sync.mutation(taskId)?.toMutation()
        override suspend fun putMutation(mutation: OutboxMutation) {
            val old = sync.mutation(mutation.taskId)?.toMutation()
            // Bookkeeping may change, the persisted request never may.
            check(old == null || (old.request() == mutation.request() && old.localRevision == mutation.localRevision &&
                old.snapshot == mutation.snapshot))
            sync.putMutation(OutboxEntity(mutation.taskId, mutation.mutationId, mutation.operation.name,
                mutation.baseVersion, mutation.localRevision, mutation.snapshot, mutation.state.name,
                mutation.attempts, mutation.lastError, mutation.retryNotBefore))
        }
        override suspend fun removeMutation(taskId: String) = sync.removeMutation(taskId)
        override suspend fun conflicts() = sync.conflicts().map { it.toConflict() }
        override suspend fun conflict(taskId: String) = sync.conflict(taskId)?.toConflict()
        override suspend fun putConflict(conflict: StoredConflict) = sync.putConflict(ConflictEntity(
            conflict.taskId, conflict.local.snapshot, conflict.local.baseVersion, conflict.local.localRevision,
            conflict.local.localUpdatedAt, conflict.local.pending, conflict.server, conflict.mutationId))
    }
}

private fun OutboxEntity.toMutation() = OutboxMutation(mutationId, taskId, MutationOperation.valueOf(operation),
    baseVersion, localRevision, snapshot, OutboxState.valueOf(state), attempts, lastError, retryNotBefore)
private fun ConflictEntity.toConflict() = StoredConflict(taskId,
    LocalSyncRecord(local, localBaseVersion, localRevision, localUpdatedAt, localPending), server, mutationId)
private fun TaskEntity.toRecord() = LocalSyncRecord(
    TaskSnapshot(id, title, description, subjectCode.name, dueDate?.toString(), priority.name, isCompleted, isDeleted),
    baseVersion, localRevision, localUpdatedAt, syncState == LocalSyncState.PENDING)
private fun LocalSyncRecord.toEntity() = snapshot.let {
    TaskEntity(it.id, it.title, it.description, SubjectCode.valueOf(it.subjectCode), it.dueDate?.let(LocalDate::parse),
        TaskPriority.valueOf(it.priority), it.completed, it.isDeleted, baseVersion, localRevision, localUpdatedAt,
        if (pending) LocalSyncState.PENDING else LocalSyncState.SYNCED)
}

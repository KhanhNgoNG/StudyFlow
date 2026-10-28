package com.studyflow.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.studyflow.data.local.database.StudyFlowDatabase
import com.studyflow.data.local.entity.TaskEntity
import com.studyflow.data.mapper.toLocalTask
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.domain.model.TaskContent
import java.time.DateTimeException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomTaskRepository(
    private val database: StudyFlowDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : TaskRepository {
    private val dao = database.taskDao()

    override fun observeActiveTasks() = dao.observeActive()
        .map { rows -> rows.map { it.toLocalTask() } }.distinctUntilChanged().storageErrors()

    override fun observeTask(id: String) = dao.observeActiveById(id)
        .map { it?.toLocalTask() }.distinctUntilChanged().storageErrors()

    override suspend fun getStoredTask(id: String) = storageCall { dao.getStoredById(id)?.toLocalTask() }

    override suspend fun create(content: TaskContent): TaskWriteResult.Success {
        val values = content.normalized()
        val id = UUID.randomUUID().toString()
        return storageCall {
            dao.insert(TaskEntity(
                id, values.title, values.description, values.subjectCode, values.dueDate, values.priority,
                isCompleted = false, isDeleted = false, baseVersion = 0, localRevision = 1,
                localUpdatedAt = now(), syncState = LocalSyncState.PENDING,
            ))
            TaskWriteResult.Success(id, changed = true)
        }
    }

    override suspend fun updateContent(id: String, content: TaskContent): TaskWriteResult {
        val values = content.normalized()
        return mutate(id) {
            dao.updateContent(id, values.title, values.description, values.subjectCode,
                values.dueDate, values.priority, now())
        }
    }

    override suspend fun setCompleted(id: String, completed: Boolean) = mutate(id) {
        dao.setCompleted(id, completed, now())
    }

    override suspend fun toggleCompleted(id: String) = mutate(id) { dao.toggleCompleted(id, now()) }
    override suspend fun softDelete(id: String) = mutate(id) { dao.softDelete(id, now()) }

    private suspend fun mutate(id: String, update: suspend () -> Int): TaskWriteResult = storageCall {
        database.withTransaction {
            if (update() > 0) TaskWriteResult.Success(id, changed = true)
            else if (dao.getStoredById(id)?.isDeleted == false) TaskWriteResult.Success(id, changed = false)
            else TaskWriteResult.Unavailable
        }
    }
}

private fun storageFailure(failure: Throwable): Nothing = when (failure) {
    is CancellationException -> throw failure
    is SQLiteException, is IllegalStateException, is IllegalArgumentException, is DateTimeException ->
        throw TaskStorageException(failure)
    else -> throw failure
}

private suspend fun <T> storageCall(block: suspend () -> T): T = try {
    block()
} catch (failure: SQLiteException) {
    throw TaskStorageException(failure)
} catch (failure: CancellationException) {
    throw failure
} catch (failure: IllegalStateException) {
    throw TaskStorageException(failure)
} catch (failure: IllegalArgumentException) {
    throw TaskStorageException(failure)
} catch (failure: DateTimeException) {
    throw TaskStorageException(failure)
}

private fun <T> Flow<T>.storageErrors(): Flow<T> = catch { storageFailure(it) }

package com.studyflow.testing

import com.studyflow.data.repository.TaskRepository
import com.studyflow.data.repository.TaskStorageException
import com.studyflow.data.repository.TaskWriteResult
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.domain.model.LocalTask
import com.studyflow.domain.model.Task
import com.studyflow.domain.model.TaskContent
import com.studyflow.ui.tasks.TaskItem
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

/** Test-only source. Database semantics are independently tested against real Room on device. */
class FakeTaskRepository(initial: List<TaskItem> = emptyList()) : TaskRepository {
    private val rows = MutableStateFlow(initial.map { LocalTask(it.task, 0, 1, 100, it.syncStatus) })
    var failRead = false
    var failWrite = false
    var readGate: CompletableDeferred<Unit>? = null
    var writeGate: CompletableDeferred<Unit>? = null

    override fun observeActiveTasks() = observe { rows.map { list -> list.filter { !it.task.isDeleted } } }
    override fun observeTask(id: String) = observe { rows.map { list -> list.firstOrNull { it.task.id == id && !it.task.isDeleted } } }
    private fun <T> observe(source: () -> Flow<T>): Flow<T> = flow {
        readGate?.await()
        if (failRead) throw TaskStorageException(IOException("Test read failure"))
        emitAll(source())
    }
    override suspend fun getStoredTask(id: String) = rows.value.firstOrNull { it.task.id == id }

    override suspend fun create(content: TaskContent): TaskWriteResult.Success {
        beforeWrite()
        val values = content.normalized()
        val id = UUID.randomUUID().toString()
        rows.value += LocalTask(Task(id, values.title, values.description, values.subjectCode,
            values.priority, values.dueDate), 0, 1, 100, LocalSyncState.PENDING)
        return TaskWriteResult.Success(id, true)
    }
    override suspend fun updateContent(id: String, content: TaskContent): TaskWriteResult {
        val values = content.normalized()
        return update(id) { it.copy(title = values.title, description = values.description,
            subjectCode = values.subjectCode, priority = values.priority, dueDate = values.dueDate) }
    }
    override suspend fun setCompleted(id: String, completed: Boolean) = update(id) { it.copy(isCompleted = completed) }
    override suspend fun toggleCompleted(id: String) = update(id) { it.copy(isCompleted = !it.isCompleted) }
    override suspend fun softDelete(id: String) = update(id) { it.copy(isDeleted = true) }

    private suspend fun beforeWrite() {
        writeGate?.await()
        if (failWrite) throw TaskStorageException(IOException("Test write failure"))
    }
    private suspend fun update(id: String, transform: (Task) -> Task): TaskWriteResult {
        beforeWrite()
        val row = getStoredTask(id)?.takeUnless { it.task.isDeleted } ?: return TaskWriteResult.Unavailable
        val task = transform(row.task)
        if (task == row.task) return TaskWriteResult.Success(id, false)
        rows.value = rows.value.map { if (it.task.id == id) row.copy(task = task,
            localRevision = row.localRevision + 1, localUpdatedAt = row.localUpdatedAt + 1,
            syncState = LocalSyncState.PENDING) else it }
        return TaskWriteResult.Success(id, true)
    }
}

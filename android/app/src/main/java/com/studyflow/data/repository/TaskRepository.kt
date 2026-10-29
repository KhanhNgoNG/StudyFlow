package com.studyflow.data.repository

import com.studyflow.domain.model.LocalTask
import com.studyflow.domain.model.TaskContent
import kotlinx.coroutines.flow.Flow

sealed interface TaskWriteResult {
    data class Success(val id: String, val changed: Boolean) : TaskWriteResult
    data object Unavailable : TaskWriteResult
}

class TaskStorageException(cause: Throwable) : RuntimeException("Local task storage failed", cause)

interface TaskRepository {
    /** All local records, including deleted records waiting to be sent. */
    fun observeStoredTasks(): Flow<List<LocalTask>>
    fun observeActiveTasks(): Flow<List<LocalTask>>
    fun observeTask(id: String): Flow<LocalTask?>
    suspend fun getStoredTask(id: String): LocalTask?
    suspend fun create(content: TaskContent): TaskWriteResult.Success
    suspend fun updateContent(id: String, content: TaskContent): TaskWriteResult
    suspend fun setCompleted(id: String, completed: Boolean): TaskWriteResult
    suspend fun toggleCompleted(id: String): TaskWriteResult
    suspend fun softDelete(id: String): TaskWriteResult
}

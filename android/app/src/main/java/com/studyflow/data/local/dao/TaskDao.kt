package com.studyflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.studyflow.data.local.entity.TaskEntity
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun getStored(): List<TaskEntity>

    @Upsert
    suspend fun putFromSync(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun removeUnsentFromSync(id: String)

    // Includes tombstones for local pending counts. Active UI lists must exclude them.
    @Query("SELECT * FROM tasks")
    fun observeStored(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isDeleted = 0")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id AND isDeleted = 0")
    fun observeActiveById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getStoredById(id: String): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity)

    // The business values, revision and pending state change in one SQLite statement.
    // The nullable date comparison uses IS NOT so null->date and date->null count as changes.
    @Query("""
        UPDATE tasks SET title = :title, description = :description, subjectCode = :subject,
            dueDate = :dueDate, priority = :priority, localRevision = localRevision + 1,
            localUpdatedAt = :updatedAt, syncState = 'PENDING'
        WHERE id = :id AND isDeleted = 0 AND
            (title != :title OR description != :description OR subjectCode != :subject
             OR dueDate IS NOT :dueDate OR priority != :priority)
    """)
    suspend fun updateContent(
        id: String, title: String, description: String, subject: SubjectCode,
        dueDate: LocalDate?, priority: TaskPriority, updatedAt: Long,
    ): Int

    @Query("""
        UPDATE tasks SET isCompleted = :completed, localRevision = localRevision + 1,
            localUpdatedAt = :updatedAt, syncState = 'PENDING'
        WHERE id = :id AND isDeleted = 0 AND isCompleted != :completed
    """)
    suspend fun setCompleted(id: String, completed: Boolean, updatedAt: Long): Int

    @Query("""
        UPDATE tasks SET isCompleted = NOT isCompleted, localRevision = localRevision + 1,
            localUpdatedAt = :updatedAt, syncState = 'PENDING'
        WHERE id = :id AND isDeleted = 0
    """)
    suspend fun toggleCompleted(id: String, updatedAt: Long): Int

    @Query("""
        UPDATE tasks SET isDeleted = 1, localRevision = localRevision + 1,
            localUpdatedAt = :updatedAt, syncState = 'PENDING'
        WHERE id = :id AND isDeleted = 0
    """)
    suspend fun softDelete(id: String, updatedAt: Long): Int
}

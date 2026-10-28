package com.studyflow.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val subjectCode: SubjectCode,
    val dueDate: LocalDate?,
    val priority: TaskPriority,
    val isCompleted: Boolean,
    val isDeleted: Boolean,
    val baseVersion: Long,
    val localRevision: Long,
    val localUpdatedAt: Long,
    val syncState: LocalSyncState,
)

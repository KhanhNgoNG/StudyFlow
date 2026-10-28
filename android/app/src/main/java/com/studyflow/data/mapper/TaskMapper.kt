package com.studyflow.data.mapper

import com.studyflow.data.local.entity.TaskEntity
import com.studyflow.domain.model.LocalTask
import com.studyflow.domain.model.Task

fun TaskEntity.toLocalTask() = LocalTask(
    task = Task(id, title, description, subjectCode, priority, dueDate, isCompleted, isDeleted),
    baseVersion = baseVersion, localRevision = localRevision,
    localUpdatedAt = localUpdatedAt, syncState = syncState,
)

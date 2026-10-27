package com.studyflow.ui.tasks

import com.studyflow.domain.model.Task

enum class TaskFilter { ALL, INCOMPLETE, COMPLETED }

/** Illustrative UI labels only; this is not a server field or an active sync engine. */
enum class DemoSyncStatus { PENDING, SYNCED }

data class TaskItem(
    val task: Task,
    val syncStatus: DemoSyncStatus = DemoSyncStatus.PENDING,
    val isOverdue: Boolean = false,
)

data class TasksUiState(
    val query: String = "",
    val filter: TaskFilter = TaskFilter.ALL,
    val tasks: List<TaskItem> = emptyList(),
    val totalCount: Int = 0,
)

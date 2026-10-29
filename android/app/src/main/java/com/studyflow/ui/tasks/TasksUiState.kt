package com.studyflow.ui.tasks

import com.studyflow.domain.model.Task
import com.studyflow.domain.model.LocalSyncState

enum class TaskFilter { ALL, INCOMPLETE, COMPLETED }
enum class TaskDateFilter { ANY, BEFORE_TODAY, FROM_TODAY }
data class TaskConditions(
    val query: String = "",
    val filter: TaskFilter = TaskFilter.ALL,
    val dateFilter: TaskDateFilter = TaskDateFilter.ANY,
)

data class TaskItem(
    val task: Task,
    val syncStatus: LocalSyncState = LocalSyncState.PENDING,
    val isOverdue: Boolean = false,
)

data class TasksUiState(
    val query: String = "",
    val filter: TaskFilter = TaskFilter.ALL,
    val dateFilter: TaskDateFilter = TaskDateFilter.ANY,
    val tasks: List<TaskItem> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val hasReadError: Boolean = false,
    val hasWriteError: Boolean = false,
    val mutatingIds: Set<String> = emptySet(),
)

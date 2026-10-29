package com.studyflow.ui.taskdetail

import com.studyflow.ui.tasks.TaskItem

/** Re-derived from the shared source by ID whenever stored data or today's date changes. */
data class TaskDetailUiState(
    val item: TaskItem? = null,
    val localUpdatedAt: Long? = null,
    val showDeleteDialog: Boolean = false,
    val isLoading: Boolean = false,
    val hasReadError: Boolean = false,
    val hasWriteError: Boolean = false,
    val isMutating: Boolean = false,
)

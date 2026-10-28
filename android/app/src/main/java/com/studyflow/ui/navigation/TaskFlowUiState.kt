package com.studyflow.ui.navigation

import com.studyflow.ui.taskdetail.TaskDetailUiState
import com.studyflow.ui.taskeditor.TaskEditorUiState

enum class TaskPage { LIST, DETAIL, EDITOR }

/** A small, bounded route stack: list -> detail -> editor, or list -> editor. */
data class TaskFlowUiState(
    val page: TaskPage = TaskPage.LIST,
    val taskId: String? = null,
    val detail: TaskDetailUiState = TaskDetailUiState(),
    val editor: TaskEditorUiState? = null,
)

enum class TaskMutationResult { UPDATED, UNCHANGED, UNAVAILABLE, NOT_REQUESTED, BUSY, FAILED }

enum class TaskNoticeKind { CREATED, DELETED }
data class TaskNotice(val id: Long, val kind: TaskNoticeKind)

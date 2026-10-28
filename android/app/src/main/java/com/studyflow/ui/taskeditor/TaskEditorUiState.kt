package com.studyflow.ui.taskeditor

import com.studyflow.domain.model.TaskContent

/** Only editable values; never a second source of stored tasks. */
typealias TaskDraft = TaskContent

enum class TitleError { REQUIRED, TOO_LONG }

data class TaskValidation(
    val title: TitleError? = null,
    val descriptionTooLong: Boolean = false,
) {
    val isValid: Boolean get() = title == null && !descriptionTooLong
}

fun validateTask(draft: TaskDraft): TaskValidation = TaskValidation(
    title = when {
        draft.title.trim().isEmpty() -> TitleError.REQUIRED
        draft.title.trim().length > 120 -> TitleError.TOO_LONG
        else -> null
    },
    descriptionTooLong = draft.description.length > 2_000,
)

data class TaskEditorUiState(
    val taskId: String? = null,
    val draft: TaskDraft = TaskDraft(),
    val initialDraft: TaskDraft = draft,
    val validation: TaskValidation = TaskValidation(),
    val validationAttempt: Int = 0,
    val unavailable: Boolean = false,
    val showDiscardDialog: Boolean = false,
    val isSaving: Boolean = false,
    val hasStorageError: Boolean = false,
) {
    val hasChanges: Boolean get() = draft != initialDraft
}

sealed interface TaskSaveResult {
    data class Success(val id: String, val created: Boolean, val changed: Boolean) : TaskSaveResult
    data object Invalid : TaskSaveResult
    data object Unavailable : TaskSaveResult
    data object NoEditor : TaskSaveResult
    data object Busy : TaskSaveResult
    data object Failed : TaskSaveResult
}

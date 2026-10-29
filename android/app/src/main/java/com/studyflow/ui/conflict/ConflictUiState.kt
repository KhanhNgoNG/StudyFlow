package com.studyflow.ui.conflict

import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate

/** Presentation snapshots only; neither an Entity nor an API payload. */
data class ConflictTaskSnapshot(
    val title: String,
    val description: String,
    val subject: SubjectCode,
    val dueDate: LocalDate?,
    val priority: TaskPriority,
    val completed: Boolean,
)

sealed interface ConflictVersion {
    data class Present(val content: ConflictTaskSnapshot) : ConflictVersion
    data object Deleted : ConflictVersion
}

enum class ConflictSide { DEVICE, SERVER }
enum class ConflictField { TITLE, DESCRIPTION, SUBJECT, DEADLINE, PRIORITY, COMPLETED }

data class ConflictUiState(
    val device: ConflictVersion,
    val server: ConflictVersion,
    val selected: ConflictSide? = null,
) {
    val isDeleteEdit: Boolean get() = (device == ConflictVersion.Deleted) != (server == ConflictVersion.Deleted)
    val differentFields: Set<ConflictField> get() {
        val local = (device as? ConflictVersion.Present)?.content ?: return emptySet()
        val remote = (server as? ConflictVersion.Present)?.content ?: return emptySet()
        return buildSet {
            if (local.title != remote.title) add(ConflictField.TITLE)
            if (local.description != remote.description) add(ConflictField.DESCRIPTION)
            if (local.subject != remote.subject) add(ConflictField.SUBJECT)
            if (local.dueDate != remote.dueDate) add(ConflictField.DEADLINE)
            if (local.priority != remote.priority) add(ConflictField.PRIORITY)
            if (local.completed != remote.completed) add(ConflictField.COMPLETED)
        }
    }
}

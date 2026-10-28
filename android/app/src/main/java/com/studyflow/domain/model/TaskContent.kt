package com.studyflow.domain.model

import java.time.LocalDate

/** Values owned by the form. Completion, tombstones and sync metadata are deliberately absent. */
data class TaskContent(
    val title: String = "",
    val description: String = "",
    val subjectCode: SubjectCode = SubjectCode.OTHER,
    val dueDate: LocalDate? = null,
    val priority: TaskPriority = TaskPriority.MEDIUM,
) {
    fun normalized(): TaskContent {
        val result = copy(title = title.trim())
        require(result.title.length in 1..120)
        require(result.description.length <= 2_000)
        return result
    }

    companion object {
        fun from(task: Task) = TaskContent(
            task.title, task.description, task.subjectCode, task.dueDate, task.priority,
        )
    }
}

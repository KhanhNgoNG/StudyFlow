package com.studyflow.domain.model

import java.time.LocalDate

/** UI-phase business data. API revision and wire formats await the sync contract. */
data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val subjectCode: SubjectCode,
    val priority: TaskPriority,
    val dueDate: LocalDate?,
    val isCompleted: Boolean = false,
    val isDeleted: Boolean = false,
)

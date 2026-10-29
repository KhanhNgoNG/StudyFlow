package com.studyflow.ui.tasks

import com.studyflow.domain.model.LocalTask
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate

internal val taskDeadlineOrder = compareBy<LocalTask, LocalDate?>(nullsLast()) { it.task.dueDate }
    .thenBy { when (it.task.priority) {
        TaskPriority.HIGH -> 0
        TaskPriority.MEDIUM -> 1
        TaskPriority.LOW -> 2
    } }.thenBy { it.task.id }

internal fun List<LocalTask>.selectTasks(conditions: TaskConditions, today: LocalDate): List<TaskItem> =
    asSequence().filter { !it.task.isDeleted }
        .filter { it.task.title.contains(conditions.query.trim(), ignoreCase = true) }
        .filter { when (conditions.filter) {
            TaskFilter.ALL -> true
            TaskFilter.INCOMPLETE -> !it.task.isCompleted
            TaskFilter.COMPLETED -> it.task.isCompleted
        } }
        .filter { when (conditions.dateFilter) {
            TaskDateFilter.ANY -> true
            TaskDateFilter.BEFORE_TODAY -> it.task.dueDate?.isBefore(today) == true
            TaskDateFilter.FROM_TODAY -> it.task.dueDate?.let { date -> !date.isBefore(today) } == true
        } }
        .sortedWith(taskDeadlineOrder).map { it.toItem(today) }.toList()

internal fun LocalTask.toItem(today: LocalDate) = TaskItem(
    task = task, syncStatus = syncState,
    isOverdue = !task.isCompleted && task.dueDate?.isBefore(today) == true,
)

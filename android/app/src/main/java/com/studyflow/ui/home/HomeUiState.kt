package com.studyflow.ui.home

import com.studyflow.domain.model.LocalSyncState
import com.studyflow.domain.model.LocalTask
import com.studyflow.ui.tasks.TaskConditions
import com.studyflow.ui.tasks.TaskDateFilter
import com.studyflow.ui.tasks.TaskFilter
import com.studyflow.ui.tasks.TaskItem
import com.studyflow.ui.tasks.selectTasks
import java.time.LocalDate

data class HomeUiState(
    val today: LocalDate = LocalDate.now(),
    val activeCount: Int = 0,
    val todayTotal: Int = 0,
    val todayCompleted: Int = 0,
    val overdue: List<TaskItem> = emptyList(),
    val upcoming: List<TaskItem> = emptyList(),
    val pendingCount: Int = 0,
    val isLoading: Boolean = true,
    val hasReadError: Boolean = false,
    val hasWriteError: Boolean = false,
    val mutatingIds: Set<String> = emptySet(),
) {
    val todayProgress: Float get() = if (todayTotal == 0) 0f else todayCompleted.toFloat() / todayTotal
}

/** One Room snapshot; no separately maintained home task collection. */
internal fun summarizeHome(rows: List<LocalTask>, today: LocalDate): HomeUiState {
    val active = rows.filter { !it.task.isDeleted }
    val dueToday = active.filter { it.task.dueDate == today }
    return HomeUiState(
        today = today, activeCount = active.size,
        todayTotal = dueToday.size, todayCompleted = dueToday.count { it.task.isCompleted },
        overdue = rows.selectTasks(homeConditions(TaskDateFilter.BEFORE_TODAY), today).take(3),
        upcoming = rows.selectTasks(homeConditions(TaskDateFilter.FROM_TODAY), today).take(3),
        pendingCount = rows.count { it.syncState == LocalSyncState.PENDING },
        isLoading = false,
    )
}

internal fun homeConditions(dateFilter: TaskDateFilter) = TaskConditions(
    filter = TaskFilter.INCOMPLETE, dateFilter = dateFilter,
)

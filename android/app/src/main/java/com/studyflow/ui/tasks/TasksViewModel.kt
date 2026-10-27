package com.studyflow.ui.tasks

import androidx.lifecycle.ViewModel
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TasksViewModel(
    initialTasks: List<TaskItem>,
    private val todayProvider: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    // The source stays independent from the visible result, including during a filtered toggle.
    private var sourceTasks = initialTasks.toList()
    private var query = ""
    private var filter = TaskFilter.ALL

    private val mutableUiState = MutableStateFlow(deriveState())
    val uiState: StateFlow<TasksUiState> = mutableUiState.asStateFlow()

    fun onQueryChange(value: String) {
        query = value
        publishState()
    }

    fun onFilterChange(value: TaskFilter) {
        filter = value
        publishState()
    }

    fun toggleCompleted(id: String) {
        sourceTasks = sourceTasks.map { item ->
            if (item.task.id == id) {
                item.copy(
                    task = item.task.copy(isCompleted = !item.task.isCompleted),
                    syncStatus = DemoSyncStatus.PENDING,
                )
            } else {
                item
            }
        }
        publishState()
    }

    fun clearConditions() {
        query = ""
        filter = TaskFilter.ALL
        publishState()
    }

    /** Refresh date-derived labels on resume without regenerating the sample tasks. */
    fun refreshToday() = publishState()

    private fun publishState() {
        // Six in-memory items need no dispatcher, debounce, or background coroutine.
        mutableUiState.value = deriveState()
    }

    private fun deriveState(): TasksUiState {
        val searchTerm = query.trim()
        val today = todayProvider()
        val visibleTasks = sourceTasks
            .asSequence()
            .filter { item -> item.task.title.contains(searchTerm, ignoreCase = true) }
            .filter { item ->
                when (filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.INCOMPLETE -> !item.task.isCompleted
                    TaskFilter.COMPLETED -> item.task.isCompleted
                }
            }
            .sortedWith(
                compareBy<TaskItem, LocalDate?>(nullsLast()) { it.task.dueDate }
                    .thenBy { priorityOrder(it.task.priority) }
                    .thenBy { it.task.id },
            )
            .map { item ->
                item.copy(
                    isOverdue = !item.task.isCompleted &&
                        item.task.dueDate?.isBefore(today) == true,
                )
            }
            .toList()
        return TasksUiState(
            query = query,
            filter = filter,
            tasks = visibleTasks,
            totalCount = sourceTasks.size,
        )
    }

    private fun priorityOrder(priority: TaskPriority): Int = when (priority) {
        TaskPriority.HIGH -> 0
        TaskPriority.MEDIUM -> 1
        TaskPriority.LOW -> 2
    }
}

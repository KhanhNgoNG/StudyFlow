package com.studyflow.ui.tasks

import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.Task
import com.studyflow.domain.model.TaskPriority
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksViewModelTest {
    private val today = LocalDate.of(2026, 10, 27)

    @Test
    fun `search trims edges ignores case and combines with completion filter`() {
        val viewModel = model(
            item("open", title = "Bài tập SQL"),
            item("done", title = "Ôn tập sql", completed = true),
            item("other", title = "Bài tập Mobile", completed = true),
        )

        viewModel.onQueryChange("  sQl  ")
        assertEquals(listOf("done", "open"), viewModel.visibleIds())
        viewModel.onFilterChange(TaskFilter.COMPLETED)
        assertEquals(listOf("done"), viewModel.visibleIds())
        assertEquals(3, viewModel.uiState.value.totalCount)

        viewModel.clearConditions()
        assertEquals("", viewModel.uiState.value.query)
        assertEquals(TaskFilter.ALL, viewModel.uiState.value.filter)
        assertEquals(listOf("done", "open", "other"), viewModel.visibleIds())
    }

    @Test
    fun `completing a filtered task removes it from result but preserves source`() {
        val viewModel = model(
            item("one", title = "SQL hôm nay", syncStatus = DemoSyncStatus.SYNCED),
            item("two", title = "Mobile hôm nay"),
        )
        viewModel.onQueryChange("SQL")
        viewModel.onFilterChange(TaskFilter.INCOMPLETE)
        val beforeToggle = viewModel.uiState.value

        viewModel.toggleCompleted("one")

        assertTrue(viewModel.uiState.value.tasks.isEmpty())
        assertEquals(2, viewModel.uiState.value.totalCount)
        assertEquals("SQL", viewModel.uiState.value.query)
        assertEquals(TaskFilter.INCOMPLETE, viewModel.uiState.value.filter)
        assertFalse(beforeToggle.tasks.single().task.isCompleted)

        viewModel.onFilterChange(TaskFilter.COMPLETED)
        val completed = viewModel.uiState.value.tasks.single()
        assertEquals("one", completed.task.id)
        assertTrue(completed.task.isCompleted)
        assertEquals(DemoSyncStatus.PENDING, completed.syncStatus)

        viewModel.clearConditions()
        assertEquals(listOf("one", "two"), viewModel.visibleIds())
    }

    @Test
    fun `uncompleting a task while completed filter is active updates immediately`() {
        val viewModel = model(item("done", completed = true), item("open"))
        viewModel.onFilterChange(TaskFilter.COMPLETED)

        viewModel.toggleCompleted("done")

        assertTrue(viewModel.uiState.value.tasks.isEmpty())
        viewModel.onFilterChange(TaskFilter.INCOMPLETE)
        assertEquals(listOf("done", "open"), viewModel.visibleIds())
        assertTrue(viewModel.uiState.value.tasks.none { it.task.isCompleted })
    }

    @Test
    fun `ordering uses deadline then priority then id with no deadline last`() {
        val viewModel = model(
            item("no-date-high", dueDate = null, priority = TaskPriority.HIGH),
            item("today-low", priority = TaskPriority.LOW),
            item("today-high-b", priority = TaskPriority.HIGH),
            item("earlier-low", dueDate = today.minusDays(1), priority = TaskPriority.LOW),
            item("later-high", dueDate = today.plusDays(1), priority = TaskPriority.HIGH),
            item("today-medium", priority = TaskPriority.MEDIUM),
            item("today-high-a", priority = TaskPriority.HIGH),
            item("no-date-low", dueDate = null, priority = TaskPriority.LOW),
        )

        assertEquals(
            listOf(
                "earlier-low", "today-high-a", "today-high-b", "today-medium",
                "today-low", "later-high", "no-date-high", "no-date-low",
            ),
            viewModel.visibleIds(),
        )
        viewModel.toggleCompleted("today-high-b")
        assertEquals("today-high-b", viewModel.visibleIds()[2])
    }

    @Test
    fun `overdue excludes completed today future and undated tasks`() {
        val viewModel = model(
            item("overdue", dueDate = today.minusDays(1)),
            item("done", dueDate = today.minusDays(1), completed = true),
            item("today"),
            item("future", dueDate = today.plusDays(1)),
            item("undated", dueDate = null),
        )

        assertEquals(listOf("overdue"), viewModel.uiState.value.tasks.filter { it.isOverdue }.map { it.task.id })
        viewModel.toggleCompleted("overdue")
        assertTrue(viewModel.uiState.value.tasks.none { it.isOverdue })
    }

    @Test
    fun `date refresh updates overdue without moving sample deadlines or resetting conditions`() {
        var currentDate = today
        val viewModel = TasksViewModel(listOf(item("one", title = "SQL"))) { currentDate }
        viewModel.onQueryChange("SQL")
        viewModel.onFilterChange(TaskFilter.INCOMPLETE)
        assertFalse(viewModel.uiState.value.tasks.single().isOverdue)

        currentDate = today.plusDays(1)
        viewModel.refreshToday()

        val state = viewModel.uiState.value
        assertTrue(state.tasks.single().isOverdue)
        assertEquals(today, state.tasks.single().task.dueDate)
        assertEquals("SQL", state.query)
        assertEquals(TaskFilter.INCOMPLETE, state.filter)
    }

    @Test
    fun `empty source and no matching results remain distinguishable`() {
        val empty = model()
        assertTrue(empty.uiState.value.tasks.isEmpty())
        assertEquals(0, empty.uiState.value.totalCount)

        val populated = model(item("one"))
        populated.onQueryChange("không tồn tại")
        assertTrue(populated.uiState.value.tasks.isEmpty())
        assertEquals(1, populated.uiState.value.totalCount)
        populated.clearConditions()
        assertEquals(listOf("one"), populated.visibleIds())
    }

    @Test
    fun `query searches only title and blank spaces show all eligible items`() {
        val entry = item("one", title = "Mobile").let {
            it.copy(task = it.task.copy(description = "SQL"))
        }
        val viewModel = model(entry)
        viewModel.onQueryChange("SQL")
        assertTrue(viewModel.uiState.value.tasks.isEmpty())

        viewModel.onQueryChange("   ")
        assertEquals(listOf("one"), viewModel.visibleIds())
    }

    @Test
    fun `unknown toggle id is harmless and initial mutable list is defensively copied`() {
        val original = mutableListOf(item("one"))
        val viewModel = TasksViewModel(original) { today }
        original.clear()

        viewModel.toggleCompleted("missing")

        assertEquals(listOf("one"), viewModel.visibleIds())
        assertFalse(viewModel.uiState.value.tasks.single().task.isCompleted)
    }

    private fun model(vararg items: TaskItem) = TasksViewModel(items.toList()) { today }

    private fun TasksViewModel.visibleIds() = uiState.value.tasks.map { it.task.id }

    private fun item(
        id: String,
        title: String = id,
        dueDate: LocalDate? = today,
        priority: TaskPriority = TaskPriority.MEDIUM,
        completed: Boolean = false,
        syncStatus: DemoSyncStatus = DemoSyncStatus.PENDING,
    ) = TaskItem(
        task = Task(
            id = id,
            title = title,
            subjectCode = SubjectCode.GENERAL,
            priority = priority,
            dueDate = dueDate,
            isCompleted = completed,
        ),
        syncStatus = syncStatus,
    )
}

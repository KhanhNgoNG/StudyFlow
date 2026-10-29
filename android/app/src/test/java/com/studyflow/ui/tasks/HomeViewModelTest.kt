package com.studyflow.ui.tasks

import com.studyflow.domain.model.*
import com.studyflow.testing.FakeTaskRepository
import com.studyflow.testing.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val today = LocalDate.of(2026, 10, 28)

    @Test fun progressCountsOnlyActiveTasksDueTodayIncludingCompletedOnes() = runTest {
        val vm = model(FakeTaskRepository(listOf(
            item("today-open"), item("today-done", completed = true),
            item("deleted", completed = true, deleted = true),
            item("yesterday", due = today.minusDays(1), completed = true),
            item("tomorrow", due = today.plusDays(1)), item("no-date", due = null),
        )))
        assertEquals(2, vm.homeState.value.todayTotal)
        assertEquals(1, vm.homeState.value.todayCompleted)
        assertEquals(0.5f, vm.homeState.value.todayProgress)
        assertEquals(5, vm.homeState.value.activeCount)
    }

    @Test fun completeAndReopenUpdateProgressAndRemoveAndRestoreUpcoming() = runTest {
        val vm = model(FakeTaskRepository(listOf(item("today"))))
        vm.toggleCompleted("today")
        runCurrent()
        assertEquals(1, vm.homeState.value.todayCompleted)
        assertEquals(1f, vm.homeState.value.todayProgress)
        assertTrue(vm.homeState.value.upcoming.isEmpty())
        vm.toggleCompleted("today")
        runCurrent()
        assertEquals(0, vm.homeState.value.todayCompleted)
        assertEquals(listOf("today"), vm.homeState.value.upcoming.ids())
    }

    @Test fun noTasksDueTodayHasZeroProgressRatherThanNaNOrFullCompletion() = runTest {
        val vm = model(FakeTaskRepository(listOf(item("other", due = null, completed = true))))
        assertEquals(0, vm.homeState.value.todayTotal)
        assertEquals(0f, vm.homeState.value.todayProgress)
        assertTrue(vm.homeState.value.todayProgress.isFinite())
        assertEquals(1, vm.homeState.value.activeCount)
    }

    @Test fun groupsRespectYesterdayTodayTomorrowAndExcludeCompletedDeletedAndUndated() = runTest {
        val vm = model(FakeTaskRepository(listOf(
            item("yesterday", due = today.minusDays(1)), item("today"), item("tomorrow", due = today.plusDays(1)),
            item("past-done", due = today.minusDays(1), completed = true), item("today-done", completed = true),
            item("past-deleted", due = today.minusDays(1), deleted = true), item("future-deleted", deleted = true),
            item("undated", due = null),
        )))
        assertEquals(listOf("yesterday"), vm.homeState.value.overdue.ids())
        assertEquals(listOf("today", "tomorrow"), vm.homeState.value.upcoming.ids())
    }

    @Test fun eachGroupUsesDeadlinePriorityIdAndShowsOnlyFirstThree() = runTest {
        val vm = model(FakeTaskRepository(listOf(
            item("past-low", due = today.minusDays(1), priority = TaskPriority.LOW),
            item("past-medium", due = today.minusDays(1)),
            item("past-high-b", due = today.minusDays(1), priority = TaskPriority.HIGH),
            item("past-high-a", due = today.minusDays(1), priority = TaskPriority.HIGH),
            item("oldest", due = today.minusDays(2), priority = TaskPriority.LOW),
            item("future", due = today.plusDays(1), priority = TaskPriority.HIGH),
            item("low", priority = TaskPriority.LOW), item("medium"),
            item("high-b", priority = TaskPriority.HIGH), item("high-a", priority = TaskPriority.HIGH),
        )))
        assertEquals(listOf("oldest", "past-high-a", "past-high-b"), vm.homeState.value.overdue.ids())
        assertEquals(listOf("high-a", "high-b", "medium"), vm.homeState.value.upcoming.ids())
    }

    @Test fun repositoryCreateEditDeadlineAndSoftDeleteRefreshHomeWithoutResettingToLoading() = runTest {
        val repo = FakeTaskRepository()
        val vm = model(repo)
        val id = repo.create(TaskContent("New", dueDate = today)).id
        runCurrent()
        assertEquals(1, vm.homeState.value.todayTotal)
        assertFalse(vm.homeState.value.isLoading)
        repo.updateContent(id, TaskContent("Edited", dueDate = today.minusDays(1)))
        runCurrent()
        assertEquals(0, vm.homeState.value.todayTotal)
        assertEquals("Edited", vm.homeState.value.overdue.single().task.title)
        repo.softDelete(id)
        runCurrent()
        assertEquals(0, vm.homeState.value.activeCount)
        assertTrue(vm.homeState.value.overdue.isEmpty())
        assertEquals(1, vm.homeState.value.pendingCount)
        assertFalse(vm.homeState.value.isLoading)
    }

    @Test fun pendingCountsTombstonesOnceAndExcludesSyncedRecordsFromCountOnly() = runTest {
        val vm = model(FakeTaskRepository(listOf(
            item("pending"), item("pending-deleted", deleted = true),
            item("synced", sync = LocalSyncState.SYNCED), item("synced-deleted", deleted = true, sync = LocalSyncState.SYNCED),
        )))
        assertEquals(2, vm.syncState.value.pendingCount)
        assertEquals(1, vm.syncState.value.pendingDeletedCount)
        assertEquals(2, vm.homeState.value.pendingCount)
        assertEquals(setOf("pending", "synced"), vm.uiState.value.tasks.ids().toSet())
        assertEquals(setOf("pending", "synced"), vm.homeState.value.upcoming.ids().toSet())
        vm.toggleCompleted("pending")
        vm.toggleCompleted("pending")
        runCurrent()
        assertEquals(2, vm.syncState.value.pendingCount) // records, never operation count
    }

    @Test fun homeSummaryDoesNotInheritTaskSearchOrCompletionFilter() = runTest {
        val vm = model(FakeTaskRepository(listOf(item("today"), item("done", completed = true))))
        vm.onQueryChange("missing")
        vm.onFilterChange(TaskFilter.COMPLETED)
        runCurrent()
        assertTrue(vm.uiState.value.tasks.isEmpty())
        assertEquals(2, vm.homeState.value.todayTotal)
        assertEquals(1, vm.homeState.value.todayCompleted)
        assertEquals(listOf("today"), vm.homeState.value.upcoming.ids())
    }

    @Test fun viewAllReplacesOldConditionsShowsWholeGroupAndCanBeCleared() = runTest {
        val vm = model(FakeTaskRepository(listOf(
            item("a", due = today.minusDays(1)), item("b", due = today.minusDays(1)),
            item("c", due = today.minusDays(1)), item("d", due = today.minusDays(1)),
            item("today"), item("tomorrow", due = today.plusDays(1)),
            item("no-date", due = null), item("done", completed = true),
        )))
        vm.onQueryChange("missing")
        vm.onFilterChange(TaskFilter.COMPLETED)
        vm.showHomeGroup(TaskDateFilter.BEFORE_TODAY)
        runCurrent()
        assertEquals("", vm.conditionsState.value.query)
        assertEquals(TaskFilter.INCOMPLETE, vm.conditionsState.value.filter)
        assertEquals(TaskDateFilter.BEFORE_TODAY, vm.uiState.value.dateFilter)
        assertEquals(listOf("a", "b", "c", "d"), vm.uiState.value.tasks.ids())
        vm.showHomeGroup(TaskDateFilter.FROM_TODAY)
        runCurrent()
        assertEquals(listOf("today", "tomorrow"), vm.uiState.value.tasks.ids())
        vm.clearConditions()
        runCurrent()
        assertEquals(TaskConditions(), vm.conditionsState.value)
        assertEquals(8, vm.uiState.value.tasks.size)
    }

    @Test fun resumeAfterDateChangesRefreshesProgressGroupsAndCurrentDateFilter() = runTest {
        var date = today
        val vm = model(FakeTaskRepository(listOf(item("today"), item("tomorrow", due = today.plusDays(1))))) { date }
        vm.showHomeGroup(TaskDateFilter.BEFORE_TODAY)
        runCurrent()
        assertTrue(vm.uiState.value.tasks.isEmpty())
        date = today.plusDays(1)
        vm.refreshToday()
        runCurrent()
        assertEquals(date, vm.homeState.value.today)
        assertEquals(1, vm.homeState.value.todayTotal)
        assertEquals(listOf("today"), vm.homeState.value.overdue.ids())
        assertEquals(listOf("tomorrow"), vm.homeState.value.upcoming.ids())
        assertEquals(listOf("today"), vm.uiState.value.tasks.ids())
    }

    @Test fun homeAndSyncDistinguishLoadingReadErrorRetryAndRealEmpty() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeTaskRepository().apply { readGate = gate; failRead = true }
        val vm = model(repo)
        assertTrue(vm.homeState.value.isLoading)
        assertTrue(vm.syncState.value.isLoading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.homeState.value.isLoading)
        assertTrue(vm.homeState.value.hasReadError)
        assertTrue(vm.syncState.value.hasReadError)
        repo.failRead = false
        vm.retryRead()
        runCurrent()
        assertFalse(vm.homeState.value.hasReadError)
        assertFalse(vm.syncState.value.isLoading)
        assertFalse(vm.syncState.value.hasReadError)
        assertEquals(0, vm.homeState.value.activeCount)
        assertEquals(0, vm.syncState.value.pendingCount)
    }

    private fun TestScope.model(repo: FakeTaskRepository, date: () -> LocalDate = { today }): TasksViewModel {
        val vm = TasksViewModel(repo, date)
        main.own(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.homeState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.syncState.collect {} }
        return vm
    }
    private fun List<TaskItem>.ids() = map { it.task.id }
    private fun item(id: String, due: LocalDate? = today, completed: Boolean = false, deleted: Boolean = false,
        priority: TaskPriority = TaskPriority.MEDIUM, sync: LocalSyncState = LocalSyncState.PENDING) = TaskItem(
        Task(id, id, subjectCode = SubjectCode.MOBILE, dueDate = due, priority = priority,
            isCompleted = completed, isDeleted = deleted), syncStatus = sync,
    )
}

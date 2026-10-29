package com.studyflow.ui.tasks

import com.studyflow.domain.model.TaskContent
import com.studyflow.testing.FakeTaskRepository
import com.studyflow.testing.MainDispatcherRule
import com.studyflow.ui.navigation.TaskMutationResult
import com.studyflow.ui.navigation.TaskPage
import com.studyflow.ui.taskeditor.TaskSaveResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskStorageViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun TestScope.model(repo: FakeTaskRepository): TasksViewModel {
        val vm = TasksViewModel(repo)
        main.own(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.flowState.collect {} }
        return vm
    }

    @Test fun loadingEmptyReadFailureAndRetryRemainDistinct() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeTaskRepository().apply { readGate = gate }
        val vm = model(repo)
        assertTrue(vm.uiState.value.isLoading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.hasReadError)
        assertEquals(0, vm.uiState.value.totalCount)
        repo.failRead = true
        vm.retryRead()
        runCurrent()
        assertTrue(vm.uiState.value.hasReadError)
        assertFalse(vm.uiState.value.isLoading)
        repo.failRead = false
        vm.retryRead()
        runCurrent()
        assertFalse(vm.uiState.value.hasReadError)
        repo.create(TaskContent(title = "Room"))
        runCurrent()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("Room", vm.uiState.value.tasks.single().task.title)
    }

    @Test fun editorPublishesInputImmediatelyWithoutWaitingForDerivedFlowCollectors() = runTest {
        val vm = TasksViewModel(FakeTaskRepository())
        main.own(vm)
        // No derived state collectors: IME edits must not wait for a combine coroutine to run.
        vm.openNewTask()
        var expected = ""
        for (character in "RoomOffline") {
            val fieldValue = requireNotNull(vm.navigationState.value.editor).draft.title
            expected += character
            vm.updateDraft { it.copy(title = fieldValue + character) }
            assertEquals(expected, vm.navigationState.value.editor?.draft?.title)
            val queryField = vm.conditionsState.value.query
            vm.onQueryChange(queryField + character)
            assertEquals(expected, vm.conditionsState.value.query)
        }
    }

    @Test fun saveFailureKeepsDraftAndRetryNavigatesOnlyAfterSuccess() = runTest {
        val repo = FakeTaskRepository().apply { failWrite = true }
        val vm = model(repo)
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "Draft") }
        assertEquals(TaskSaveResult.Failed, vm.saveEditor())
        runCurrent()
        assertEquals(TaskPage.EDITOR, vm.flowState.value.page)
        assertEquals("Draft", vm.flowState.value.editor?.draft?.title)
        assertTrue(requireNotNull(vm.flowState.value.editor).hasStorageError)
        assertNull(vm.notices.value)
        assertTrue(repo.observeActiveTasks().first().isEmpty())
        repo.failWrite = false
        assertTrue(vm.saveEditor() is TaskSaveResult.Success)
        runCurrent()
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
        assertEquals(1, repo.observeActiveTasks().first().size)
        assertNotNull(vm.notices.value)
    }

    @Test fun repeatedSaveWhileSuspendedCreatesOnlyOneRecordAndCannotLeaveFormEarly() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeTaskRepository().apply { writeGate = gate }
        val vm = model(repo)
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "Only once") }
        val first = async(start = CoroutineStart.UNDISPATCHED) { vm.saveEditor() }
        runCurrent()
        assertTrue(requireNotNull(vm.flowState.value.editor).isSaving)
        vm.requestBack()
        vm.updateDraft { it.copy(title = "Too late") }
        assertEquals(TaskSaveResult.Busy, vm.saveEditor())
        assertTrue(repo.observeActiveTasks().first().isEmpty())
        gate.complete(Unit)
        assertTrue(first.await() is TaskSaveResult.Success)
        runCurrent()
        assertEquals("Only once", repo.observeActiveTasks().first().single().task.title)
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
    }

    @Test fun cancellationPropagatesAndDoesNotReportSuccessOrLoseDraft() = runTest {
        val repo = FakeTaskRepository().apply { writeGate = CompletableDeferred() }
        val vm = model(repo)
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "Still draft") }
        val save = launch(start = CoroutineStart.UNDISPATCHED) { vm.saveEditor() }
        save.cancelAndJoin()
        runCurrent()
        assertTrue(save.isCancelled)
        assertEquals(TaskPage.EDITOR, vm.flowState.value.page)
        assertEquals("Still draft", vm.flowState.value.editor?.draft?.title)
        assertFalse(requireNotNull(vm.flowState.value.editor).isSaving)
        assertFalse(requireNotNull(vm.flowState.value.editor).hasStorageError)
        assertNull(vm.notices.value)
        assertTrue(repo.observeActiveTasks().first().isEmpty())
    }

    @Test fun detailReadFailureCanRetryAndExternalWritesAreObservedById() = runTest {
        val repo = FakeTaskRepository()
        val id = repo.create(TaskContent(title = "SQL")).id
        val vm = model(repo)
        vm.onQueryChange("SQL")
        repo.failRead = true
        vm.openDetail(id)
        runCurrent()
        assertTrue(vm.flowState.value.detail.hasReadError)
        repo.failRead = false
        vm.retryRead()
        runCurrent()
        assertEquals("SQL", vm.flowState.value.detail.item?.task?.title)
        repo.updateContent(id, TaskContent(title = "Mobile"))
        runCurrent()
        assertEquals("Mobile", vm.flowState.value.detail.item?.task?.title)
        assertTrue(vm.uiState.value.tasks.isEmpty())
        assertEquals("SQL", vm.uiState.value.query)
        assertFalse(vm.flowState.value.detail.isLoading)
    }

    @Test fun deleteFailureKeepsConfirmationAndRetryRemovesRouteOnlyAfterCommit() = runTest {
        val repo = FakeTaskRepository()
        val id = repo.create(TaskContent(title = "Delete me")).id
        val vm = model(repo)
        vm.openDetail(id)
        runCurrent()
        vm.requestDelete()
        repo.failWrite = true
        assertEquals(TaskMutationResult.FAILED, vm.confirmDelete())
        runCurrent()
        assertEquals(TaskPage.DETAIL, vm.flowState.value.page)
        assertTrue(vm.flowState.value.detail.showDeleteDialog)
        assertTrue(vm.flowState.value.detail.hasWriteError)
        assertFalse(requireNotNull(repo.getStoredTask(id)).task.isDeleted)
        assertNull(vm.notices.value)
        repo.failWrite = false
        assertEquals(TaskMutationResult.UPDATED, vm.confirmDelete())
        runCurrent()
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
        assertTrue(requireNotNull(repo.getStoredTask(id)).task.isDeleted)
    }

    @Test fun detailUsesStoredUpdateTimeAndKeepsItWhenAWriteFails() = runTest {
        val repo = FakeTaskRepository()
        val id = repo.create(TaskContent(title = "Timestamp")).id
        val vm = model(repo)
        vm.openDetail(id)
        runCurrent()
        val before = requireNotNull(repo.getStoredTask(id)).localUpdatedAt
        assertEquals(before, vm.flowState.value.detail.localUpdatedAt)
        repo.updateContent(id, TaskContent(title = "Changed elsewhere"))
        runCurrent()
        val after = requireNotNull(repo.getStoredTask(id)).localUpdatedAt
        assertNotEquals(before, after)
        assertEquals(after, vm.flowState.value.detail.localUpdatedAt)
        repo.failWrite = true
        vm.toggleCompleted(id)
        runCurrent()
        assertEquals(after, vm.flowState.value.detail.localUpdatedAt)
        assertEquals("Changed elsewhere", vm.flowState.value.detail.item?.task?.title)
        assertTrue(vm.flowState.value.detail.hasWriteError)
        assertFalse(requireNotNull(repo.getStoredTask(id)).task.isCompleted)
    }
}

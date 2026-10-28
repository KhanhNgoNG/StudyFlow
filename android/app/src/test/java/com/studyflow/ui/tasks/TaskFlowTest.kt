package com.studyflow.ui.tasks

import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.Task
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.navigation.TaskMutationResult
import com.studyflow.ui.navigation.TaskPage
import com.studyflow.ui.taskeditor.TaskDraft
import com.studyflow.ui.taskeditor.TaskSaveResult
import com.studyflow.ui.taskeditor.TitleError
import java.time.LocalDate
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.testing.FakeTaskRepository
import com.studyflow.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskFlowTest {
    @get:Rule val main = MainDispatcherRule()
    private val today = LocalDate.of(2026, 10, 27)
    private val original = TaskItem(
        task = Task("original", "SQL", "Mô tả gốc", SubjectCode.DATABASE,
            TaskPriority.HIGH, today, isCompleted = true),
        syncStatus = LocalSyncState.SYNCED,
    )

    private fun TestScope.model(items: List<TaskItem> = listOf(original)) = createModel(items)
    private fun TestScope.createModel(items: List<TaskItem>, date: () -> LocalDate = { today }): TasksViewModel {
        val vm = TasksViewModel(FakeTaskRepository(items), date)
        main.own(vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.flowState.collect {} }
        return vm
    }

    private fun TasksViewModel.editOriginal() {
        openDetail("original")
        editTask()
    }

    private fun TasksViewModel.editor() = requireNotNull(flowState.value.editor)

    @Test
    fun `blank and whitespace titles cannot save or close the editor`() = runTest {
        val vm = model(emptyList())
        vm.openNewTask()
        for (title in listOf("", "  \n\t ")) {
            vm.updateDraft { it.copy(title = title) }
            assertEquals(TaskSaveResult.Invalid, vm.saveEditor())
            assertEquals(TitleError.REQUIRED, vm.editor().validation.title)
            assertEquals(TaskPage.EDITOR, vm.flowState.value.page)
            assertEquals(0, vm.uiState.value.totalCount)
        }
        assertEquals(2, vm.editor().validationAttempt)
    }

    @Test
    fun `title limit applies after trimming and description allows exactly 2000 characters`() = runTest {
        val vm = model(emptyList())
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "x".repeat(121)) }
        assertEquals(TaskSaveResult.Invalid, vm.saveEditor())
        assertEquals(TitleError.TOO_LONG, vm.editor().validation.title)
        vm.updateDraft { it.copy(title = "  ${"x".repeat(120)}  ", description = "d".repeat(2_000)) }
        assertTrue(vm.saveEditor() is TaskSaveResult.Success)
        assertEquals("x".repeat(120), vm.uiState.value.tasks.single().task.title)
        assertEquals(2_000, vm.uiState.value.tasks.single().task.description.length)
    }

    @Test
    fun `long description reports its own error without changing source`() = runTest {
        val vm = model()
        vm.editOriginal()
        vm.updateDraft { it.copy(description = "d".repeat(2_001)) }
        assertEquals(TaskSaveResult.Invalid, vm.saveEditor())
        assertNull(vm.editor().validation.title)
        assertTrue(vm.editor().validation.descriptionTooLong)
        assertEquals(original, vm.storedTask("original"))
    }

    @Test
    fun `new tasks have unique UUIDs defaults and only one save per editor`() = runTest {
        val vm = model(emptyList())
        repeat(2) { index ->
            vm.openNewTask()
            assertEquals(TaskDraft(), vm.editor().draft)
            vm.updateDraft { it.copy(title = "  Công việc $index  ") }
            val result = vm.saveEditor() as TaskSaveResult.Success
            assertEquals(result.id, UUID.fromString(result.id).toString())
            assertTrue(result.created)
            assertTrue(result.changed)
            assertEquals(TaskPage.LIST, vm.flowState.value.page)
            assertNull(vm.flowState.value.editor)
            assertEquals(TaskSaveResult.NoEditor, vm.saveEditor())
        }
        val tasks = vm.uiState.value.tasks
        assertEquals(2, tasks.size)
        assertEquals(2, tasks.map { it.task.id }.toSet().size)
        tasks.forEach {
            assertFalse(it.task.isCompleted)
            assertFalse(it.task.isDeleted)
            assertNull(it.task.dueDate)
            assertEquals(SubjectCode.OTHER, it.task.subjectCode)
            assertEquals(TaskPriority.MEDIUM, it.task.priority)
            assertEquals(LocalSyncState.PENDING, it.syncStatus)
        }
    }

    @Test
    fun `editing all fields preserves ID completion and publishes latest detail`() = runTest {
        val vm = model()
        vm.editOriginal()
        vm.updateDraft { TaskDraft("  Mobile  ", "Mô tả mới", SubjectCode.MOBILE,
            today.minusDays(3), TaskPriority.LOW) }
        val result = vm.saveEditor() as TaskSaveResult.Success
        assertEquals(TaskSaveResult.Success("original", created = false, changed = true), result)
        assertEquals(TaskPage.DETAIL, vm.flowState.value.page)
        val item = requireNotNull(vm.flowState.value.detail.item)
        assertEquals(original.task.copy(title = "Mobile", description = "Mô tả mới",
            subjectCode = SubjectCode.MOBILE, priority = TaskPriority.LOW, dueDate = today.minusDays(3)), item.task)
        assertEquals(LocalSyncState.PENDING, item.syncStatus)
        assertEquals(item, vm.uiState.value.tasks.single())
        assertNull(vm.flowState.value.editor)
        assertEquals(TaskSaveResult.NoEditor, vm.saveEditor())
    }

    @Test
    fun `unchanged edit including trimmed title does not dirty illustrative sync status`() = runTest {
        val vm = model()
        vm.editOriginal()
        vm.updateDraft { it.copy(title = " SQL ") }
        val before = vm.uiState.value
        assertEquals(TaskSaveResult.Success("original", created = false, changed = false), vm.saveEditor())
        assertEquals(before, vm.uiState.value)
        assertEquals(original, vm.storedTask("original"))
        assertEquals(LocalSyncState.SYNCED, vm.flowState.value.detail.item?.syncStatus)
    }

    @Test
    fun `draft changes do not leak and discard returns to original detail`() = runTest {
        val vm = model()
        vm.editOriginal()
        vm.updateDraft { it.copy(title = "Bản nháp", description = "Chưa lưu", dueDate = null) }
        assertEquals(original, vm.storedTask("original"))
        assertEquals(original.task, vm.uiState.value.tasks.single().task)
        vm.requestBack()
        assertTrue(vm.editor().showDiscardDialog)
        vm.continueEditing()
        assertFalse(vm.editor().showDiscardDialog)
        assertEquals("Bản nháp", vm.editor().draft.title)
        vm.requestBack()
        vm.discardChanges()
        assertEquals(TaskPage.DETAIL, vm.flowState.value.page)
        assertNull(vm.flowState.value.editor)
        assertEquals(original.task, vm.flowState.value.detail.item?.task)
        vm.editTask()
        assertEquals(TaskDraft.from(original.task), vm.editor().draft)
    }

    @Test
    fun `reverting changes and unmodified editor leave without a discard prompt`() = runTest {
        val vm = model()
        vm.editOriginal()
        vm.updateDraft { it.copy(title = "Khác") }
        vm.updateDraft { it.copy(title = "SQL") }
        assertFalse(vm.editor().hasChanges)
        vm.requestBack()
        assertEquals(TaskPage.DETAIL, vm.flowState.value.page)
        vm.requestBack()
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
        vm.openNewTask()
        vm.requestBack()
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
    }

    @Test
    fun `discard new draft leaves no record and reopening starts blank`() = runTest {
        val vm = model(emptyList())
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "Nháp") }
        vm.returnToList()
        assertTrue(vm.editor().showDiscardDialog)
        vm.discardChanges()
        assertEquals(0, vm.uiState.value.totalCount)
        vm.openNewTask()
        assertEquals(TaskDraft(), vm.editor().draft)
    }

    @Test
    fun `detail observes complete reopen and overdue changes by ID`() = runTest {
        val vm = model(listOf(original.copy(task = original.task.copy(dueDate = today.minusDays(1)))))
        vm.openDetail("original")
        assertFalse(requireNotNull(vm.flowState.value.detail.item).isOverdue)
        assertEquals(TaskMutationResult.UPDATED, vm.toggleCompleted("original"))
        assertFalse(requireNotNull(vm.flowState.value.detail.item).task.isCompleted)
        assertTrue(requireNotNull(vm.flowState.value.detail.item).isOverdue)
        assertEquals(LocalSyncState.PENDING, vm.flowState.value.detail.item?.syncStatus)
        vm.toggleCompleted("original")
        assertTrue(requireNotNull(vm.flowState.value.detail.item).task.isCompleted)
        assertFalse(requireNotNull(vm.flowState.value.detail.item).isOverdue)
    }

    @Test
    fun `delete requires confirmation keeps tombstone and removes detail from navigation`() = runTest {
        val vm = model()
        vm.openDetail("original")
        assertEquals(TaskMutationResult.NOT_REQUESTED, vm.confirmDelete())
        vm.requestDelete()
        assertFalse(requireNotNull(vm.storedTask("original")).task.isDeleted)
        vm.cancelDelete()
        assertEquals(TaskMutationResult.NOT_REQUESTED, vm.confirmDelete())
        assertEquals(1, vm.uiState.value.totalCount)
        vm.requestDelete()
        assertEquals(TaskMutationResult.UPDATED, vm.confirmDelete())
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
        assertNull(vm.flowState.value.taskId)
        assertTrue(vm.uiState.value.tasks.isEmpty())
        assertEquals(0, vm.uiState.value.totalCount)
        val tombstone = requireNotNull(vm.storedTask("original"))
        assertTrue(tombstone.task.isDeleted)
        assertEquals(original.task.copy(isDeleted = true), tombstone.task)
        assertEquals(LocalSyncState.PENDING, tombstone.syncStatus)
        vm.requestBack()
        assertEquals(TaskPage.LIST, vm.flowState.value.page)
    }

    @Test
    fun `search and every filter exclude deleted tasks and conditions survive all routes`() = runTest {
        val vm = model(listOf(original, original.copy(task = original.task.copy(id = "deleted", isDeleted = true))))
        vm.onQueryChange("  sql ")
        vm.onFilterChange(TaskFilter.COMPLETED)
        vm.editOriginal()
        vm.updateDraft { it.copy(description = "Đã sửa") }
        vm.saveEditor()
        vm.returnToList()
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "Mobile") }
        vm.saveEditor()
        assertEquals("  sql ", vm.uiState.value.query)
        assertEquals(TaskFilter.COMPLETED, vm.uiState.value.filter)
        assertEquals(listOf("original"), vm.uiState.value.tasks.map { it.task.id })
        vm.deleteTask("original")
        TaskFilter.entries.forEach {
            vm.onFilterChange(it)
            assertTrue(vm.uiState.value.tasks.isEmpty())
        }
        assertEquals(1, vm.uiState.value.totalCount)
        vm.clearConditions()
        assertEquals("Mobile", vm.uiState.value.tasks.single().task.title)
    }

    @Test
    fun `missing and deleted IDs have explicit mutation failure and unavailable detail`() = runTest {
        val vm = model(listOf(original.copy(task = original.task.copy(isDeleted = true))))
        for (id in listOf("missing", "original")) {
            assertEquals(TaskMutationResult.UNAVAILABLE, vm.toggleCompleted(id))
            assertEquals(TaskMutationResult.UNAVAILABLE, vm.deleteTask(id))
            vm.openDetail(id)
            assertNull(vm.flowState.value.detail.item)
            vm.editTask()
            assertEquals(TaskPage.DETAIL, vm.flowState.value.page)
            vm.returnToList()
        }
        assertTrue(requireNotNull(vm.storedTask("original")).task.isDeleted)
    }

    @Test
    fun `deletion during an edit prevents saving or resurrecting a missing task`() = runTest {
        val vm = model()
        vm.editOriginal()
        vm.updateDraft { it.copy(title = "Mới") }
        vm.deleteTask("original")
        assertEquals(TaskSaveResult.Unavailable, vm.saveEditor())
        assertTrue(vm.editor().unavailable)
        assertEquals("Mới", vm.editor().draft.title)
        assertTrue(requireNotNull(vm.storedTask("original")).task.isDeleted)
        vm.requestBack()
        vm.discardChanges()
        assertNull(vm.flowState.value.detail.item)
    }

    @Test
    fun `past deadline is retained on create and clearing a date persists only after save`() = runTest {
        val vm = model(emptyList())
        vm.openNewTask()
        vm.updateDraft { it.copy(title = "Quá hạn", dueDate = today.minusDays(10)) }
        val result = vm.saveEditor() as TaskSaveResult.Success
        assertTrue(vm.uiState.value.tasks.single().isOverdue)
        assertEquals(today.minusDays(10), vm.uiState.value.tasks.single().task.dueDate)
        vm.openDetail(result.id)
        vm.editTask()
        vm.updateDraft { it.copy(dueDate = null) }
        assertNotNull(vm.storedTask(result.id)?.task?.dueDate)
        vm.saveEditor()
        assertNull(vm.flowState.value.detail.item?.task?.dueDate)
        assertFalse(requireNotNull(vm.flowState.value.detail.item).isOverdue)
    }
}

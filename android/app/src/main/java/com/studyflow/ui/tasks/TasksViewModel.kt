package com.studyflow.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studyflow.data.repository.TaskRepository
import com.studyflow.data.repository.TaskStorageException
import com.studyflow.data.repository.TaskWriteResult
import com.studyflow.domain.model.LocalTask
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.navigation.TaskFlowUiState
import com.studyflow.ui.navigation.TaskMutationResult
import com.studyflow.ui.navigation.TaskNotice
import com.studyflow.ui.navigation.TaskNoticeKind
import com.studyflow.ui.navigation.TaskPage
import com.studyflow.ui.taskdetail.TaskDetailUiState
import com.studyflow.ui.taskeditor.TaskDraft
import com.studyflow.ui.taskeditor.TaskEditorUiState
import com.studyflow.ui.taskeditor.TaskSaveResult
import com.studyflow.ui.taskeditor.validateTask
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    private val repository: TaskRepository,
    private val todayProvider: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private sealed interface ReadState<out T> {
        data object Loading : ReadState<Nothing>
        data class Data<T>(val value: T) : ReadState<T>
        data object Error : ReadState<Nothing>
    }
    private data class DetailRead(val id: String?, val state: ReadState<LocalTask?>)

    private val conditions = MutableStateFlow(TaskConditions())
    val conditionsState: StateFlow<TaskConditions> = conditions.asStateFlow()
    private val today = MutableStateFlow(todayProvider())
    private val retry = MutableStateFlow(0)
    private val pendingIds = MutableStateFlow<Set<String>>(emptySet())
    private val writeError = MutableStateFlow(false)
    private val route = MutableStateFlow(TaskFlowUiState())
    // Text input must be published immediately; do not send editor values through combine/stateIn.
    val navigationState: StateFlow<TaskFlowUiState> = route.asStateFlow()
    private val mutableNotice = MutableStateFlow<TaskNotice?>(null)
    private var noticeId = 0L
    val notices: StateFlow<TaskNotice?> = mutableNotice.asStateFlow()
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    private val source = retry.flatMapLatest {
        repository.observeActiveTasks().readStates()
    }.stateIn(viewModelScope, sharing, ReadState.Loading)

    val uiState: StateFlow<TasksUiState> = combine(source, conditions, today, writeError, pendingIds) {
        read, criteria, date, error, pending ->
        val items = (read as? ReadState.Data)?.value.orEmpty()
        TasksUiState(
            query = criteria.query, filter = criteria.filter,
            tasks = items.asSequence().filter { !it.task.isDeleted }
                .filter { it.task.title.contains(criteria.query.trim(), ignoreCase = true) }
                .filter { when (criteria.filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.INCOMPLETE -> !it.task.isCompleted
                    TaskFilter.COMPLETED -> it.task.isCompleted
                } }
                .sortedWith(compareBy<LocalTask, LocalDate?>(nullsLast()) { it.task.dueDate }
                    .thenBy { when (it.task.priority) {
                        TaskPriority.HIGH -> 0
                        TaskPriority.MEDIUM -> 1
                        TaskPriority.LOW -> 2
                    } }.thenBy { it.task.id })
                .map { it.toItem(date) }.toList(),
            totalCount = items.count { !it.task.isDeleted },
            isLoading = read == ReadState.Loading,
            hasReadError = read == ReadState.Error,
            hasWriteError = error, mutatingIds = pending,
        )
    }.stateIn(viewModelScope, sharing, TasksUiState())

    private val detailRead = combine(
        route.map { if (it.page == TaskPage.DETAIL) it.taskId else null }.distinctUntilChanged(), retry,
    ) { id, attempt -> id to attempt }.flatMapLatest { (id, _) ->
        if (id == null) flowOf(DetailRead(null, ReadState.Data(null)))
        else repository.observeTask(id).readStates().map { DetailRead(id, it) }
    }

    val flowState: StateFlow<TaskFlowUiState> = combine(route, detailRead, today, pendingIds) {
        current, read, date, pending ->
        if (current.page != TaskPage.DETAIL) current
        else {
            val state = if (read.id == current.taskId) read.state else ReadState.Loading
            current.copy(detail = current.detail.copy(
                item = (state as? ReadState.Data)?.value?.toItem(date),
                isLoading = state == ReadState.Loading,
                hasReadError = state == ReadState.Error,
                isMutating = current.taskId in pending,
            ))
        }
    }.stateIn(viewModelScope, sharing, TaskFlowUiState())

    fun onQueryChange(value: String) { conditions.value = conditions.value.copy(query = value) }
    fun onFilterChange(value: TaskFilter) { conditions.value = conditions.value.copy(filter = value) }
    fun clearConditions() { conditions.value = TaskConditions() }
    fun refreshToday() { today.value = todayProvider() }
    fun retryRead() { retry.value += 1 }
    fun dismissWriteError() { writeError.value = false }

    fun consumeNotice(id: Long) { if (mutableNotice.value?.id == id) mutableNotice.value = null }
    private fun notify(kind: TaskNoticeKind) { mutableNotice.value = TaskNotice(++noticeId, kind) }

    fun openNewTask() {
        if (route.value.page == TaskPage.LIST) {
            route.value = TaskFlowUiState(page = TaskPage.EDITOR, editor = TaskEditorUiState())
        }
    }

    fun openDetail(id: String) {
        if (route.value.page == TaskPage.LIST) route.value = TaskFlowUiState(page = TaskPage.DETAIL, taskId = id)
    }

    fun editTask() {
        val current = flowState.value
        if (current.page != TaskPage.DETAIL || current.detail.isMutating) return
        val task = current.detail.item?.task ?: return
        route.value = TaskFlowUiState(
            page = TaskPage.EDITOR, taskId = task.id,
            editor = TaskEditorUiState(taskId = task.id, draft = TaskDraft.from(task)),
        )
    }

    fun updateDraft(transform: (TaskDraft) -> TaskDraft) {
        val current = route.value
        val editor = current.editor ?: return
        if (editor.isSaving) return
        val draft = transform(editor.draft)
        route.value = current.copy(editor = editor.copy(
            draft = draft, hasStorageError = false,
            validation = if (editor.validationAttempt > 0) validateTask(draft) else editor.validation,
        ))
    }

    // UI events own mutations in the ViewModel lifecycle, never in a composable scope.
    fun onSave() { viewModelScope.launch { saveEditor() } }
    fun onToggleCompleted(id: String) { viewModelScope.launch { toggleCompleted(id) } }
    fun onConfirmDelete() { viewModelScope.launch { confirmDelete() } }

    internal suspend fun saveEditor(): TaskSaveResult {
        val current = route.value
        val editor = current.editor ?: return TaskSaveResult.NoEditor
        if (editor.isSaving) return TaskSaveResult.Busy
        val validation = validateTask(editor.draft)
        if (!validation.isValid) {
            route.value = current.copy(editor = editor.copy(
                validation = validation, validationAttempt = editor.validationAttempt + 1,
            ))
            return TaskSaveResult.Invalid
        }
        route.value = current.copy(editor = editor.copy(isSaving = true, hasStorageError = false))
        try {
            val result = if (editor.taskId == null) repository.create(editor.draft)
                else repository.updateContent(editor.taskId, editor.draft)
            return when (result) {
                TaskWriteResult.Unavailable -> {
                    route.value = current.copy(editor = editor.copy(unavailable = true))
                    TaskSaveResult.Unavailable
                }
                is TaskWriteResult.Success -> {
                    val created = editor.taskId == null
                    route.value = if (created) TaskFlowUiState()
                        else TaskFlowUiState(page = TaskPage.DETAIL, taskId = result.id)
                    if (created) notify(TaskNoticeKind.CREATED)
                    TaskSaveResult.Success(result.id, created, result.changed)
                }
            }
        } catch (failure: TaskStorageException) {
            route.value = current.copy(editor = editor.copy(hasStorageError = true))
            return TaskSaveResult.Failed
        } finally {
            val latest = route.value
            if (latest.editor?.isSaving == true) route.value = latest.copy(editor = latest.editor.copy(isSaving = false))
        }
    }

    internal suspend fun toggleCompleted(id: String): TaskMutationResult = mutate(id) { repository.toggleCompleted(id) }
    internal suspend fun deleteTask(id: String): TaskMutationResult = mutate(id) { repository.softDelete(id) }
    internal suspend fun storedTask(id: String): TaskItem? = repository.getStoredTask(id)?.toItem(today.value)

    private suspend fun mutate(id: String, operation: suspend () -> TaskWriteResult): TaskMutationResult {
        if (id in pendingIds.value) return TaskMutationResult.BUSY
        pendingIds.value += id
        writeError.value = false
        route.value = route.value.copy(detail = route.value.detail.copy(hasWriteError = false))
        try {
            return when (val result = operation()) {
                TaskWriteResult.Unavailable -> TaskMutationResult.UNAVAILABLE
                is TaskWriteResult.Success -> if (result.changed) TaskMutationResult.UPDATED else TaskMutationResult.UNCHANGED
            }
        } catch (failure: TaskStorageException) {
            writeError.value = true
            val current = route.value
            route.value = current.copy(detail = current.detail.copy(hasWriteError = true))
            return TaskMutationResult.FAILED
        } finally {
            pendingIds.value -= id
        }
    }

    fun requestBack() {
        val current = route.value
        if (current.editor?.isSaving == true || current.taskId in pendingIds.value) return
        if (current.editor?.hasChanges == true) {
            route.value = current.copy(editor = current.editor.copy(showDiscardDialog = true))
        } else leaveEditorOrDetail()
    }

    fun continueEditing() {
        val current = route.value
        route.value = current.copy(editor = current.editor?.copy(showDiscardDialog = false))
    }

    fun discardChanges() {
        if (route.value.editor?.showDiscardDialog == true) leaveEditorOrDetail()
    }

    private fun leaveEditorOrDetail() {
        val current = route.value
        route.value = if (current.page == TaskPage.EDITOR && current.taskId != null) {
            TaskFlowUiState(page = TaskPage.DETAIL, taskId = current.taskId)
        } else TaskFlowUiState()
    }

    fun returnToList() {
        if (route.value.editor != null) requestBack() else route.value = TaskFlowUiState()
    }

    fun requestDelete() {
        val current = flowState.value
        if (current.page == TaskPage.DETAIL && current.detail.item != null && !current.detail.isMutating) {
            route.value = route.value.copy(detail = route.value.detail.copy(showDeleteDialog = true))
        }
    }

    fun cancelDelete() {
        val current = route.value
        if (current.taskId !in pendingIds.value) {
            route.value = current.copy(detail = current.detail.copy(showDeleteDialog = false))
        }
    }

    internal suspend fun confirmDelete(): TaskMutationResult {
        val current = route.value
        if (current.page != TaskPage.DETAIL || !current.detail.showDeleteDialog) return TaskMutationResult.NOT_REQUESTED
        val result = deleteTask(current.taskId ?: return TaskMutationResult.UNAVAILABLE)
        if (result == TaskMutationResult.UPDATED) {
            route.value = TaskFlowUiState()
            notify(TaskNoticeKind.DELETED)
        }
        return result
    }

    private fun <T> Flow<T>.readStates(): Flow<ReadState<T>> =
        map<T, ReadState<T>> { ReadState.Data(it) }
            .onStart { emit(ReadState.Loading) }
            .catch { failure ->
                if (failure is TaskStorageException) emit(ReadState.Error) else throw failure
            }
}

private fun LocalTask.toItem(today: LocalDate) = TaskItem(
    task = task, syncStatus = syncState,
    isOverdue = !task.isCompleted && task.dueDate?.isBefore(today) == true,
)

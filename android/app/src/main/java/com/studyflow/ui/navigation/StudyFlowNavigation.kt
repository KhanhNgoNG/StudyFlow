package com.studyflow.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studyflow.R
import com.studyflow.ui.home.HomeScreen
import com.studyflow.ui.sync.SyncScreen
import com.studyflow.ui.tasks.TasksScreen
import com.studyflow.ui.tasks.TasksViewModel
import com.studyflow.ui.taskdetail.TaskDetailScreen
import com.studyflow.ui.taskdetail.TaskDetailUiState
import com.studyflow.ui.taskeditor.TaskEditorScreen
import kotlinx.coroutines.flow.filterNotNull

private enum class Destination(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    HOME(R.string.nav_home, R.drawable.ic_home),
    TASKS(R.string.nav_tasks, R.drawable.ic_tasks),
    SYNC(R.string.nav_sync, R.drawable.ic_sync),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyFlowNavigation(viewModel: TasksViewModel) {
    val tasksState by viewModel.uiState.collectAsStateWithLifecycle()
    val conditions by viewModel.conditionsState.collectAsStateWithLifecycle()
    val flow by viewModel.navigationState.collectAsStateWithLifecycle()
    val observedFlow by viewModel.flowState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.TASKS) }
    // Kept above the tab branch so returning to tasks also preserves scroll position.
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val addMessage = stringResource(R.string.task_added)
    val deleteMessage = stringResource(R.string.task_deleted)
    val dismissLabel = stringResource(R.string.dismiss_snackbar)
    val noticeLabels by rememberUpdatedState(Triple(addMessage, deleteMessage, dismissLabel))
    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.notices.filterNotNull().collect { notice ->
            // Consume before suspending so configuration changes do not replay navigation/messages.
            viewModel.consumeNotice(notice.id)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = if (notice.kind == TaskNoticeKind.CREATED) noticeLabels.first else noticeLabels.second,
                actionLabel = noticeLabels.third,
                duration = SnackbarDuration.Short,
            )
        }
    }
    val selectDestination: (Destination) -> Unit = {
        focusManager.clearFocus()
        snackbarHostState.currentSnackbarData?.dismiss()
        destination = it
    }

    BackHandler(enabled = flow.page != TaskPage.LIST || destination != Destination.TASKS) {
        if (flow.page != TaskPage.LIST) {
            focusManager.clearFocus()
            viewModel.requestBack()
        } else selectDestination(Destination.TASKS)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(when (flow.page) {
                            TaskPage.LIST -> destination.label
                            TaskPage.DETAIL -> R.string.task_detail_title
                            TaskPage.EDITOR -> if (flow.editor?.taskId == null) {
                                R.string.add_task
                            } else R.string.task_edit_title
                        }),
                        style = if (flow.page == TaskPage.LIST) {
                            MaterialTheme.typography.headlineMedium
                        } else MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (flow.page != TaskPage.LIST) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            viewModel.requestBack()
                        }) {
                            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.task_back))
                        }
                    }
                },
                actions = {
                    if (flow.page == TaskPage.EDITOR) {
                        TextButton(
                            onClick = viewModel::onSave,
                            enabled = flow.editor?.isSaving != true,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(if (flow.editor?.isSaving == true) R.string.task_saving else R.string.task_save))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (flow.page == TaskPage.LIST) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { selectDestination(item) },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(stringResource(item.label), textAlign = TextAlign.Center) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (destination == Destination.TASKS && flow.page == TaskPage.LIST) {
                FloatingActionButton(
                    onClick = {
                        focusManager.clearFocus()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        viewModel.openNewTask()
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_task))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val contentModifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()
            when (flow.page) {
                TaskPage.EDITOR -> flow.editor?.let { editor ->
                    TaskEditorScreen(
                        state = editor,
                        onDraftChange = viewModel::updateDraft,
                        onContinueEditing = viewModel::continueEditing,
                        onDiscardChanges = viewModel::discardChanges,
                        modifier = contentModifier,
                    )
                }
                TaskPage.DETAIL -> TaskDetailScreen(
                    state = if (observedFlow.page == TaskPage.DETAIL && observedFlow.taskId == flow.taskId) {
                        observedFlow.detail
                    } else TaskDetailUiState(isLoading = true),
                    onEdit = viewModel::editTask,
                    onToggleCompleted = { flow.taskId?.let(viewModel::onToggleCompleted) },
                    onRequestDelete = viewModel::requestDelete,
                    onCancelDelete = viewModel::cancelDelete,
                    onConfirmDelete = viewModel::onConfirmDelete,
                    onRetry = viewModel::retryRead,
                    onReturnToList = viewModel::returnToList,
                    modifier = contentModifier,
                )
                TaskPage.LIST -> when (destination) {
                    Destination.TASKS -> TasksScreen(
                        state = tasksState.copy(query = conditions.query, filter = conditions.filter),
                        onQueryChange = viewModel::onQueryChange,
                        onFilterChange = viewModel::onFilterChange,
                        onToggleCompleted = viewModel::onToggleCompleted,
                        onRetry = viewModel::retryRead,
                        onDismissWriteError = viewModel::dismissWriteError,
                        onClearConditions = viewModel::clearConditions,
                        onTaskClick = {
                            focusManager.clearFocus()
                            snackbarHostState.currentSnackbarData?.dismiss()
                            viewModel.openDetail(it)
                        },
                        listState = listState,
                        modifier = contentModifier,
                    )
                    Destination.HOME -> HomeScreen(
                        onOpenTasks = { selectDestination(Destination.TASKS) },
                        modifier = contentModifier,
                    )
                    Destination.SYNC -> SyncScreen(modifier = contentModifier)
                }
            }
        }
    }
}

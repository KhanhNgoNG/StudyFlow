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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
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
import com.studyflow.ui.taskdetail.TaskDetailTopBar
import com.studyflow.ui.taskdetail.TaskDetailUiState
import com.studyflow.ui.taskeditor.TaskEditorScreen
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private enum class Destination(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    HOME(R.string.nav_home, R.drawable.ic_home),
    TASKS(R.string.nav_tasks, R.drawable.ic_tasks),
    SYNC(R.string.nav_sync, R.drawable.ic_sync),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyFlowNavigation(viewModel: TasksViewModel) {
    val tasksState by viewModel.uiState.collectAsStateWithLifecycle()
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val conditions by viewModel.conditionsState.collectAsStateWithLifecycle()
    val flow by viewModel.navigationState.collectAsStateWithLifecycle()
    val observedFlow by viewModel.flowState.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    // Kept above the tab branch so returning to tasks also preserves scroll position.
    val listState = rememberLazyListState()
    val homeListState = rememberLazyListState()
    var returnScrollKey by rememberSaveable { mutableStateOf<String?>(null) }
    var returnScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var returnScrollDestination by rememberSaveable { mutableStateOf<Destination?>(null) }
    val uiScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val navigationLabelMinHeight = with(density) {
        if (fontScale > 1.3f) MaterialTheme.typography.labelMedium.lineHeight.toDp() * 2 else 0.dp
    }
    val detailState = if (observedFlow.page == TaskPage.DETAIL && observedFlow.taskId == flow.taskId) {
        observedFlow.detail
    } else TaskDetailUiState(isLoading = true)
    val editTask: () -> Unit = {
        focusManager.clearFocus()
        viewModel.editTask()
    }
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
    val captureSourceScroll: () -> Unit = {
        val sourceScroll = if (destination == Destination.HOME) homeListState else listState
        val anchor = sourceScroll.layoutInfo.visibleItemsInfo.firstOrNull()
        returnScrollKey = anchor?.key as? String
        returnScrollOffset = anchor?.offset ?: 0
        returnScrollDestination = destination
    }
    val openTask: (String) -> Unit = { id ->
        captureSourceScroll()
        focusManager.clearFocus()
        snackbarHostState.currentSnackbarData?.dismiss()
        viewModel.openDetail(id)
    }
    val openNewTask: () -> Unit = {
        captureSourceScroll()
        focusManager.clearFocus()
        snackbarHostState.currentSnackbarData?.dismiss()
        viewModel.openNewTask()
    }
    LaunchedEffect(flow.page, destination) {
        if (flow.page == TaskPage.LIST && returnScrollDestination == destination) {
            // Hiding the bottom bar may clamp the old lazy layout during navigation.
            // Restore a visible key after the returning viewport has been measured.
            withFrameNanos { }
            withFrameNanos { }
            val returningScroll = if (destination == Destination.HOME) homeListState else listState
            returningScroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == returnScrollKey }?.let { anchor ->
                returningScroll.scrollBy((anchor.offset - returnScrollOffset).toFloat())
            }
            returnScrollKey = null
            returnScrollDestination = null
        }
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
            if (flow.page == TaskPage.DETAIL) {
                TaskDetailTopBar(
                    onBack = { focusManager.clearFocus(); viewModel.requestBack() },
                    onEdit = editTask,
                    canEdit = detailState.item != null && !detailState.isLoading &&
                        !detailState.hasReadError && !detailState.isMutating,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(when (flow.page) {
                                TaskPage.LIST -> if (destination == Destination.HOME) R.string.app_name else destination.label
                                TaskPage.DETAIL -> R.string.task_detail_title
                                TaskPage.EDITOR -> if (flow.editor?.taskId == null) {
                                    R.string.add_task
                                } else R.string.task_edit_title
                            }),
                            style = if (flow.page == TaskPage.LIST) {
                                MaterialTheme.typography.headlineMedium
                            } else MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                            color = if (flow.page == TaskPage.LIST && destination == Destination.HOME) {
                                MaterialTheme.colorScheme.primary
                            } else MaterialTheme.colorScheme.onBackground,
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (flow.page == TaskPage.LIST) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { selectDestination(item) },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = {
                                Text(stringResource(item.label), textAlign = TextAlign.Center, maxLines = 2,
                                    modifier = Modifier.heightIn(min = navigationLabelMinHeight))
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (destination == Destination.TASKS && flow.page == TaskPage.LIST) {
                FloatingActionButton(
                    onClick = openNewTask,
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
                        onSave = viewModel::onSave,
                        modifier = contentModifier,
                    )
                }
                TaskPage.DETAIL -> TaskDetailScreen(
                    state = detailState,
                    onEdit = editTask,
                    onToggleCompleted = { flow.taskId?.let(viewModel::onToggleCompleted) },
                    onRequestDelete = viewModel::requestDelete,
                    onCancelDelete = viewModel::cancelDelete,
                    onConfirmDelete = viewModel::onConfirmDelete,
                    onRetry = viewModel::retryRead,
                    onReturnToList = {
                        selectDestination(Destination.TASKS)
                        viewModel.returnToList()
                    },
                    modifier = contentModifier,
                )
                TaskPage.LIST -> when (destination) {
                    Destination.TASKS -> TasksScreen(
                        state = tasksState.copy(query = conditions.query, filter = conditions.filter, dateFilter = conditions.dateFilter),
                        onQueryChange = viewModel::onQueryChange,
                        onFilterChange = viewModel::onFilterChange,
                        onToggleCompleted = viewModel::onToggleCompleted,
                        onRetry = viewModel::retryRead,
                        onDismissWriteError = viewModel::dismissWriteError,
                        onClearConditions = viewModel::clearConditions,
                        onTaskClick = openTask,
                        listState = listState,
                        pendingCount = syncState.takeUnless { it.isLoading || it.hasReadError }?.pendingCount,
                        onOpenSync = { selectDestination(Destination.SYNC) },
                        modifier = contentModifier,
                    )
                    Destination.HOME -> HomeScreen(
                        state = homeState,
                        onTaskClick = openTask,
                        onToggleCompleted = viewModel::onToggleCompleted,
                        onViewAll = {
                            viewModel.showHomeGroup(it)
                            selectDestination(Destination.TASKS)
                            uiScope.launch { listState.scrollToItem(0) }
                        },
                        onCreateTask = openNewTask,
                        onOpenSync = { selectDestination(Destination.SYNC) },
                        onRetry = viewModel::retryRead,
                        onDismissWriteError = viewModel::dismissWriteError,
                        listState = homeListState,
                        modifier = contentModifier,
                    )
                    Destination.SYNC -> SyncScreen(state = syncState, onRetry = viewModel::retryRead, modifier = contentModifier)
                }
            }
        }
    }
}

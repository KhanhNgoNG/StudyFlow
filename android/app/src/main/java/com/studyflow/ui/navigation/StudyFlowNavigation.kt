package com.studyflow.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.home.HomeScreen
import com.studyflow.ui.sync.SyncScreen
import com.studyflow.ui.tasks.TaskFilter
import com.studyflow.ui.tasks.TasksScreen
import com.studyflow.ui.tasks.TasksUiState
import kotlinx.coroutines.launch

private enum class Destination(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    HOME(R.string.nav_home, R.drawable.ic_home),
    TASKS(R.string.nav_tasks, R.drawable.ic_tasks),
    SYNC(R.string.nav_sync, R.drawable.ic_sync),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyFlowNavigation(
    tasksState: TasksUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onToggleCompleted: (String) -> Unit,
    onClearConditions: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.TASKS) }
    // Kept above the tab branch so returning to tasks also preserves scroll position.
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val addMessage = stringResource(R.string.add_task_coming_soon)
    val detailMessage = stringResource(R.string.task_detail_coming_soon)
    val dismissLabel = stringResource(R.string.dismiss_snackbar)
    val selectDestination: (Destination) -> Unit = {
        focusManager.clearFocus()
        snackbarHostState.currentSnackbarData?.dismiss()
        destination = it
    }
    val showMessage: (String) -> Unit = { message ->
        focusManager.clearFocus()
        snackbarHostState.currentSnackbarData?.dismiss()
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = dismissLabel,
                duration = SnackbarDuration.Short,
            )
        }
    }

    BackHandler(enabled = destination != Destination.TASKS) {
        selectDestination(Destination.TASKS)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(destination.label),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
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
        },
        floatingActionButton = {
            if (destination == Destination.TASKS) {
                FloatingActionButton(
                    onClick = { showMessage(addMessage) },
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
            when (destination) {
                Destination.TASKS -> TasksScreen(
                    state = tasksState,
                    onQueryChange = onQueryChange,
                    onFilterChange = onFilterChange,
                    onToggleCompleted = onToggleCompleted,
                    onClearConditions = onClearConditions,
                    onTaskClick = { showMessage(detailMessage) },
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

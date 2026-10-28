package com.studyflow.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.components.TaskCard

@Composable
fun TasksScreen(
    state: TasksUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onToggleCompleted: (String) -> Unit,
    onClearConditions: () -> Unit,
    onTaskClick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissWriteError: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    // The search field and filters scroll too, keeping them usable with a large font or IME.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "search", contentType = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tasks_search)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_task_search), contentDescription = null)
                },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_task_close),
                                contentDescription = stringResource(R.string.tasks_clear_query),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }
        item(key = "filters", contentType = "filters") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TaskFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(stringResource(filter.labelResource())) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        }
        item(key = "demo_notice", contentType = "notice") {
            Text(
                text = stringResource(R.string.tasks_local_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.hasWriteError) {
            item(key = "write_error") {
                Column {
                    Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismissWriteError, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.dismiss_snackbar))
                    }
                }
            }
        }
        if (state.isLoading) {
            item(key = "loading") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tasks_loading), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else if (state.hasReadError) {
            item(key = "read_error") {
                Column {
                    Text(stringResource(R.string.tasks_read_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.tasks_retry))
                    }
                }
            }
        } else if (state.tasks.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptyTasks(
                    hasSourceTasks = state.totalCount > 0,
                    onClearConditions = {
                        focusManager.clearFocus()
                        onClearConditions()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(items = state.tasks, key = { it.task.id }, contentType = { "task" }) { item ->
                TaskCard(
                    item = item,
                    isMutating = item.task.id in state.mutatingIds,
                    onToggleCompleted = { onToggleCompleted(item.task.id) },
                    onClick = { onTaskClick(item.task.id) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyTasks(
    hasSourceTasks: Boolean,
    onClearConditions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_task_empty),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (hasSourceTasks) R.string.tasks_no_results_title else R.string.tasks_empty_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Text(
                text = stringResource(
                    if (hasSourceTasks) R.string.tasks_no_results_body else R.string.tasks_empty_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hasSourceTasks) {
                TextButton(onClick = onClearConditions, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tasks_clear_conditions), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun TaskFilter.labelResource(): Int = when (this) {
    TaskFilter.ALL -> R.string.tasks_filter_all
    TaskFilter.INCOMPLETE -> R.string.tasks_filter_incomplete
    TaskFilter.COMPLETED -> R.string.tasks_filter_completed
}

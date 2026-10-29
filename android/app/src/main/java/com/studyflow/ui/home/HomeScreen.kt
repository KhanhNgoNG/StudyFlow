package com.studyflow.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.components.LocalReadStatus
import com.studyflow.ui.components.TaskCard
import com.studyflow.ui.components.LocalStatusBanner
import com.studyflow.ui.tasks.TaskDateFilter
import com.studyflow.ui.tasks.TaskItem
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HomeScreen(
    state: HomeUiState,
    onTaskClick: (String) -> Unit,
    onToggleCompleted: (String) -> Unit,
    onViewAll: (TaskDateFilter) -> Unit,
    onCreateTask: () -> Unit,
    onOpenSync: () -> Unit,
    onRetry: () -> Unit,
    onDismissWriteError: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    LazyColumn(
        modifier = modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("greeting") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
                Text(state.today.format(formatter), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.isLoading || state.hasReadError) {
            item("read_status") { LocalReadStatus(state.isLoading, state.hasReadError, onRetry) }
        } else {
            if (state.activeCount == 0) {
                item("empty") {
                    HomeCard {
                        SectionTitle(stringResource(R.string.tasks_empty_title))
                        Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onCreateTask, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.add_task))
                        }
                    }
                }
            }
            item("progress") {
                Card(shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(R.drawable.ic_detail_calendar), null, Modifier.size(32.dp))
                                Text(stringResource(R.string.home_progress_title), style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() })
                            }
                            if (state.todayTotal > 0) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(R.string.ui_progress_fraction, state.todayCompleted, state.todayTotal),
                                        style = MaterialTheme.typography.headlineMedium)
                                    Text(stringResource(R.string.ui_progress_completed), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (state.todayTotal == 0) {
                            Text(stringResource(R.string.home_no_due_today), style = MaterialTheme.typography.bodyLarge)
                        } else {
                            LinearProgressIndicator(progress = { state.todayProgress }, modifier = Modifier.fillMaxWidth().height(10.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                                drawStopIndicator = {})
                        }
                    }
                }
            }
            if (state.hasWriteError) {
                item("write_error") {
                    Column {
                        Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onDismissWriteError, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.dismiss_snackbar))
                        }
                    }
                }
            }
            if (state.overdue.isNotEmpty()) {
                taskSection("overdue", R.string.home_overdue_title, R.string.home_no_overdue,
                    state.overdue, state.mutatingIds, { onViewAll(TaskDateFilter.BEFORE_TODAY) }, onTaskClick, onToggleCompleted)
            }
            taskSection("upcoming", R.string.home_upcoming_title, R.string.home_no_upcoming,
                state.upcoming, state.mutatingIds, { onViewAll(TaskDateFilter.FROM_TODAY) }, onTaskClick, onToggleCompleted)
            item("pending") {
                LocalStatusBanner(state.pendingCount, onOpenSync, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun LazyListScope.taskSection(
    key: String, title: Int, empty: Int, tasks: List<TaskItem>, mutatingIds: Set<String>,
    onViewAll: () -> Unit, onTaskClick: (String) -> Unit, onToggleCompleted: (String) -> Unit,
) {
    item("${key}_heading") {
        val viewAllLabel = stringResource(R.string.home_view_all_description, stringResource(title))
        // Wrapping keeps the action readable at large font sizes.
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionTitle(stringResource(title), Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onViewAll,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = viewAllLabel }) {
                Text(stringResource(R.string.home_view_all))
            }
        }
    }
    if (tasks.isEmpty()) item("${key}_empty") {
        Text(stringResource(empty), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(tasks, key = { "${key}_${it.task.id}" }) { item ->
        TaskCard(item, onToggleCompleted = { onToggleCompleted(item.task.id) },
            onClick = { onTaskClick(item.task.id) }, isMutating = item.task.id in mutatingIds,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.semantics { heading() })
}

package com.studyflow.ui.taskdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.components.labelResource
import com.studyflow.domain.model.LocalSyncState
import java.time.format.DateTimeFormatter

@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    onEdit: () -> Unit,
    onToggleCompleted: () -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onReturnToList: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isLoading) {
            CircularProgressIndicator()
            Text(stringResource(R.string.tasks_loading), style = MaterialTheme.typography.bodyLarge)
        } else if (state.hasReadError) {
            Text(stringResource(R.string.tasks_read_error), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tasks_retry))
            }
        } else if (item == null) {
            Text(stringResource(R.string.task_unavailable_title),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.task_unavailable_body), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onReturnToList, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.task_return_to_list))
            }
        } else {
            val task = item.task
            Text(task.title, style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            DetailField(stringResource(R.string.task_editor_description),
                task.description.ifBlank { stringResource(R.string.task_empty_description) })
            DetailField(stringResource(R.string.task_subject_label), stringResource(task.subjectCode.labelResource()))
            DetailField(stringResource(R.string.task_due_label),
                task.dueDate?.format(formatter) ?: stringResource(R.string.task_no_due_date))
            if (item.isOverdue) {
                Text(stringResource(R.string.task_overdue), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error)
            }
            DetailField(stringResource(R.string.task_priority_label), stringResource(task.priority.labelResource()))
            DetailField(stringResource(R.string.task_completion_label), stringResource(
                if (task.isCompleted) R.string.tasks_filter_completed else R.string.tasks_filter_incomplete,
            ))
            DetailField(stringResource(R.string.task_sync_label), stringResource(
                if (item.syncStatus == LocalSyncState.PENDING) R.string.task_sync_pending else R.string.task_sync_synced,
            ))
            Text(stringResource(R.string.tasks_local_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.hasWriteError) {
                Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onEdit, enabled = !state.isMutating, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.task_edit))
            }
            OutlinedButton(onClick = onToggleCompleted, enabled = !state.isMutating, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (task.isCompleted) R.string.task_reopen else R.string.task_complete))
            }
            TextButton(onClick = onRequestDelete, enabled = !state.isMutating, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (item != null && state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.task_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.task_delete_body, item.task.title))
                    if (state.hasWriteError) Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.isMutating, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.isMutating, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

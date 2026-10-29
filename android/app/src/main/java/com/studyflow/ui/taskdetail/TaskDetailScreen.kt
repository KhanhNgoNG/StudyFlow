package com.studyflow.ui.taskdetail

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.studyflow.R
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.components.LocalReadStatus
import com.studyflow.ui.components.labelResource
import com.studyflow.ui.tasks.TaskItem
import com.studyflow.ui.theme.LocalStudyFlowColors
import java.time.Instant
import java.time.ZoneId
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when {
            state.isLoading || state.hasReadError -> LocalReadStatus(state.isLoading, state.hasReadError, onRetry)
            item == null -> {
                Text(stringResource(R.string.task_unavailable_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.task_unavailable_body), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onReturnToList, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_return_to_list))
                }
            }
            else -> {
                DetailSummary(item)
                Description(item.task.description)
                TaskInformation(item)
                UpdatedTime(state.localUpdatedAt)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.hasWriteError) {
                        Text(
                            stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Button(
                        onClick = onToggleCompleted, enabled = !state.isMutating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        DetailActionLabel(
                            if (item.task.isCompleted) R.drawable.ic_sync else R.drawable.ic_detail_check,
                            stringResource(if (item.task.isCompleted) R.string.task_detail_reopen else R.string.task_complete),
                        )
                    }
                    OutlinedButton(
                        onClick = onEdit, enabled = !state.isMutating,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (state.isMutating) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        DetailActionLabel(R.drawable.ic_detail_edit, stringResource(R.string.task_detail_edit))
                    }
                    TextButton(
                        onClick = onRequestDelete, enabled = !state.isMutating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        DetailActionLabel(R.drawable.ic_detail_delete, stringResource(R.string.task_delete))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(painterResource(R.drawable.ic_detail_cloud), null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(if (item.syncStatus == LocalSyncState.PENDING) R.string.task_detail_local_pending else R.string.task_detail_local_synced),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
    if (item != null && state.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.task_delete_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.task_delete_body, item.task.title))
                    if (state.hasWriteError) Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.isMutating, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_delete))
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
private fun DetailSummary(item: TaskItem) {
    val task = item.task
    val colors = LocalStudyFlowColors.current
    val pending = item.syncStatus == LocalSyncState.PENDING
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailChip(
            text = stringResource(if (pending) R.string.task_sync_pending else R.string.task_sync_synced),
            container = if (pending) colors.warningContainer else colors.successContainer,
            content = if (pending) colors.onWarningContainer else colors.onSuccessContainer,
            icon = if (pending) R.drawable.ic_sync else R.drawable.ic_detail_check,
        )
        Text(task.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val (subjectContainer, subjectContent) = when (task.subjectCode) {
                SubjectCode.MOBILE -> colors.subjectMobile to colors.subjectOnMobile
                SubjectCode.DATABASE -> colors.subjectDatabase to colors.subjectOnDatabase
                SubjectCode.GENERAL -> colors.subjectGeneral to colors.subjectOnGeneral
                SubjectCode.OTHER -> colors.subjectOther to colors.subjectOnOther
            }
            DetailChip(stringResource(task.subjectCode.labelResource()), subjectContainer, subjectContent)
            DetailChip(
                stringResource(if (task.isCompleted) R.string.task_detail_completed else R.string.task_detail_incomplete),
                if (task.isCompleted) colors.successContainer else MaterialTheme.colorScheme.surfaceContainer,
                if (task.isCompleted) colors.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Description(description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.task_editor_description), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(description.ifBlank { stringResource(R.string.task_empty_description) }, Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TaskInformation(item: TaskItem) {
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("dd/MM/yyyy", locale) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            InformationRow(R.drawable.ic_detail_calendar, stringResource(R.string.task_due_label)) {
                Text(item.task.dueDate?.format(dateFormatter) ?: stringResource(R.string.task_no_due_date),
                    style = MaterialTheme.typography.bodyLarge)
                if (item.isOverdue && !item.task.isCompleted) {
                    Text(stringResource(R.string.task_overdue), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InformationRow(R.drawable.ic_detail_flag, stringResource(R.string.task_detail_priority)) {
                val colors = LocalStudyFlowColors.current
                val priority = item.task.priority
                DetailChip(
                    stringResource(when (priority) {
                        TaskPriority.LOW -> R.string.task_editor_priority_low
                        TaskPriority.MEDIUM -> R.string.task_editor_priority_medium
                        TaskPriority.HIGH -> R.string.task_editor_priority_high
                    }),
                    when (priority) {
                        TaskPriority.LOW -> MaterialTheme.colorScheme.surfaceContainer
                        TaskPriority.MEDIUM -> colors.warningContainer
                        TaskPriority.HIGH -> MaterialTheme.colorScheme.errorContainer
                    },
                    when (priority) {
                        TaskPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                        TaskPriority.MEDIUM -> colors.onWarningContainer
                        TaskPriority.HIGH -> MaterialTheme.colorScheme.onErrorContainer
                    },
                    icon = R.drawable.ic_detail_flag,
                )
            }
        }
    }
}

@Composable
private fun InformationRow(@DrawableRes icon: Int, label: String, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, Modifier.padding(top = 4.dp).size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

@Composable
private fun UpdatedTime(localUpdatedAt: Long?) {
    val locale = LocalConfiguration.current.locales[0]
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { zone = ZoneId.systemDefault() }
    val formatter = remember(locale, zone) { DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm", locale).withZone(zone) }
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_detail_clock), null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            localUpdatedAt?.let { stringResource(R.string.task_detail_updated, formatter.format(Instant.ofEpochMilli(it))) }
                ?: stringResource(R.string.task_detail_updated_unknown),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailChip(text: String, container: Color, content: Color, @DrawableRes icon: Int? = null) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun RowScope.DetailActionLabel(@DrawableRes icon: Int, label: String) {
    Icon(painterResource(icon), null, Modifier.size(24.dp))
    Spacer(Modifier.width(10.dp))
    Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f, fill = false))
}

package com.studyflow.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import com.studyflow.domain.model.LocalSyncState
import com.studyflow.ui.tasks.TaskItem
import com.studyflow.ui.theme.LocalStudyFlowColors
import java.time.format.DateTimeFormatter

@Composable
fun TaskCard(
    item: TaskItem,
    onToggleCompleted: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMutating: Boolean = false,
) {
    val task = item.task
    val completionLabel = stringResource(if (task.isCompleted) R.string.tasks_filter_completed else R.string.tasks_filter_incomplete)
    val checkboxLabel = stringResource(if (task.isCompleted) R.string.task_mark_incomplete else R.string.task_mark_completed, task.title)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("dd/MM/yyyy", locale) }
    val fontScale = LocalDensity.current.fontScale

    Card(
        onClick = onClick,
        modifier = modifier.semantics { stateDescription = completionLabel },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A checkbox remains its own 48 dp action and consumes its tap.
            Checkbox(
                enabled = !isMutating, checked = task.isCompleted, onCheckedChange = { onToggleCompleted() },
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = checkboxLabel
                    stateDescription = completionLabel
                },
            )
            BoxWithConstraints(Modifier.weight(1f)) {
                val inlineSync = maxWidth >= 260.dp && fontScale <= 1.3f
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            task.title, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                        )
                        if (inlineSync) SyncBadge(item.syncStatus)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SubjectBadge(task.subjectCode)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(R.drawable.ic_detail_flag), null, Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(when (task.priority) {
                                TaskPriority.LOW -> R.string.task_editor_priority_low
                                TaskPriority.MEDIUM -> R.string.task_editor_priority_medium
                                TaskPriority.HIGH -> R.string.task_editor_priority_high
                            }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(R.drawable.ic_detail_calendar), null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                task.dueDate?.let { stringResource(R.string.task_due_date, it.format(dateFormatter)) }
                                    ?: stringResource(R.string.task_no_due_date),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (item.isOverdue && !task.isCompleted) {
                            StatusBadge(stringResource(R.string.task_overdue),
                                MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        }
                        if (!inlineSync) SyncBadge(item.syncStatus)
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncBadge(status: LocalSyncState) {
    val colors = LocalStudyFlowColors.current
    val pending = status == LocalSyncState.PENDING
    Surface(
        color = if (pending) colors.warningContainer else colors.successContainer,
        contentColor = if (pending) colors.onWarningContainer else colors.onSuccessContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(if (pending) R.drawable.ic_sync else R.drawable.ic_detail_check), null, Modifier.size(14.dp))
            Text(stringResource(if (pending) R.string.task_sync_pending else R.string.task_sync_synced),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SubjectBadge(subjectCode: SubjectCode) {
    val colors = LocalStudyFlowColors.current
    val (background, foreground) = when (subjectCode) {
        SubjectCode.MOBILE -> colors.subjectMobile to colors.subjectOnMobile
        SubjectCode.DATABASE -> colors.subjectDatabase to colors.subjectOnDatabase
        SubjectCode.GENERAL -> colors.subjectGeneral to colors.subjectOnGeneral
        SubjectCode.OTHER -> colors.subjectOther to colors.subjectOnOther
    }
    StatusBadge(stringResource(subjectCode.labelResource()), background, foreground)
}

@Composable
private fun StatusBadge(text: String, containerColor: Color, contentColor: Color) {
    Surface(color = containerColor, contentColor = contentColor, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}

internal fun SubjectCode.labelResource(): Int = when (this) {
    SubjectCode.MOBILE -> R.string.task_subject_mobile
    SubjectCode.DATABASE -> R.string.task_subject_database
    SubjectCode.GENERAL -> R.string.task_subject_general
    SubjectCode.OTHER -> R.string.task_subject_other
}

internal fun TaskPriority.labelResource(): Int = when (this) {
    TaskPriority.LOW -> R.string.task_priority_low
    TaskPriority.MEDIUM -> R.string.task_priority_medium
    TaskPriority.HIGH -> R.string.task_priority_high
}

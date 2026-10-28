package com.studyflow.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.util.Locale

@Composable
fun TaskCard(
    item: TaskItem,
    onToggleCompleted: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isMutating: Boolean = false,
) {
    val task = item.task
    val completionLabel = stringResource(
        if (task.isCompleted) R.string.tasks_filter_completed else R.string.tasks_filter_incomplete,
    )
    val checkboxLabel = stringResource(
        if (task.isCompleted) R.string.task_mark_incomplete else R.string.task_mark_completed,
        task.title,
    )
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("vi-VN")) }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Checkbox consumes its own tap; the parent card action is a separate target.
                Checkbox(
                    enabled = !isMutating,
                    checked = task.isCompleted,
                    onCheckedChange = { onToggleCompleted() },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = checkboxLabel
                            stateDescription = completionLabel
                        },
                )
                Text(
                    text = task.title,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubjectBadge(subjectCode = task.subjectCode)
                StatusBadge(
                    text = stringResource(task.priority.labelResource()),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_task_calendar),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = task.dueDate?.let {
                        stringResource(R.string.task_due_date, it.format(dateFormatter))
                    } ?: stringResource(R.string.task_no_due_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.isOverdue) {
                    StatusBadge(
                        text = stringResource(R.string.task_overdue),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                StatusBadge(
                    text = completionLabel,
                    containerColor = if (task.isCompleted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    contentColor = if (task.isCompleted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                StatusBadge(
                    text = stringResource(
                        when (item.syncStatus) {
                            LocalSyncState.PENDING -> R.string.task_sync_pending
                            LocalSyncState.SYNCED -> R.string.task_sync_synced
                        },
                    ),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubjectBadge(subjectCode: SubjectCode, modifier: Modifier = Modifier) {
    val colors = LocalStudyFlowColors.current
    val (background, foreground) = when (subjectCode) {
        SubjectCode.MOBILE -> colors.subjectMobile to colors.subjectOnMobile
        SubjectCode.DATABASE -> colors.subjectDatabase to colors.subjectOnDatabase
        SubjectCode.GENERAL -> colors.subjectGeneral to colors.subjectOnGeneral
        SubjectCode.OTHER -> colors.subjectOther to colors.subjectOnOther
    }
    StatusBadge(
        text = stringResource(subjectCode.labelResource()),
        containerColor = background,
        contentColor = foreground,
        modifier = modifier,
    )
}

@Composable
private fun StatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
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

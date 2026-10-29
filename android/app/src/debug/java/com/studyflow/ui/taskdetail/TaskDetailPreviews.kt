package com.studyflow.ui.taskdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.studyflow.R
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.Task
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.tasks.TaskItem
import com.studyflow.ui.theme.StudyFlowTheme
import java.time.Instant
import java.time.LocalDate

@Composable
private fun DetailPreview(completed: Boolean = false, long: Boolean = false, empty: Boolean = false, overdue: Boolean = false) {
    val task = Task(
        id = "detail-preview", title = stringResource(if (long) R.string.detail_sample_long_title else R.string.detail_sample_title),
        description = when {
            empty -> ""
            long -> List(12) { stringResource(R.string.detail_sample_long_description) }.joinToString("\n\n")
            else -> stringResource(R.string.detail_sample_description)
        },
        subjectCode = SubjectCode.MOBILE, priority = if (overdue) TaskPriority.HIGH else TaskPriority.MEDIUM,
        dueDate = if (empty) null else LocalDate.of(2026, 10, if (overdue) 27 else 30), isCompleted = completed,
    )
    StudyFlowTheme {
        Scaffold(topBar = { TaskDetailTopBar({}, {}, true) }) { padding ->
            TaskDetailScreen(
                TaskDetailUiState(item = TaskItem(task, isOverdue = overdue && !completed),
                    localUpdatedAt = Instant.parse("2026-10-28T02:30:00Z").toEpochMilli()),
                onEdit = {}, onToggleCompleted = {}, onRequestDelete = {}, onCancelDelete = {},
                onConfirmDelete = {}, onReturnToList = {}, onRetry = {}, modifier = Modifier.padding(padding),
            )
        }
    }
}

@Preview(name = "Minh họa · chưa hoàn thành", locale = "vi", showBackground = true, heightDp = 1000)
@Composable private fun IncompleteDetail() = DetailPreview()

@Preview(name = "Minh họa · đã hoàn thành", locale = "vi", showBackground = true, heightDp = 1000)
@Composable private fun CompletedDetail() = DetailPreview(completed = true)

@Preview(name = "Minh họa · tiêu đề và mô tả dài · chữ lớn", locale = "vi", showBackground = true, fontScale = 2f, widthDp = 320, heightDp = 1000)
@Composable private fun LongDetail() = DetailPreview(long = true)

@Preview(name = "Minh họa · không deadline hoặc mô tả", locale = "vi", showBackground = true, heightDp = 1000)
@Composable private fun EmptyDetail() = DetailPreview(empty = true)

@Preview(name = "Minh họa · quá hạn", locale = "vi", showBackground = true, heightDp = 1000)
@Composable private fun OverdueDetail() = DetailPreview(overdue = true)

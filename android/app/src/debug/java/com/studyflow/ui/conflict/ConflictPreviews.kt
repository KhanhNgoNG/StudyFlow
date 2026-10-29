package com.studyflow.ui.conflict

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.studyflow.R
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.theme.StudyFlowTheme
import java.time.LocalDate

@Composable
internal fun conflictExample(deleted: Boolean = false, long: Boolean = false): ConflictUiState {
    val local = ConflictTaskSnapshot(
        title = stringResource(R.string.ui_demo_device_title),
        description = stringResource(R.string.ui_demo_device_description).let { if (long) List(24) { _ -> it }.joinToString("\n\n") else it },
        subject = SubjectCode.MOBILE, dueDate = LocalDate.of(2026, 10, 28),
        priority = TaskPriority.HIGH, completed = false,
    )
    return ConflictUiState(
        device = if (deleted) ConflictVersion.Deleted else ConflictVersion.Present(local),
        server = ConflictVersion.Present(local.copy(
            title = stringResource(R.string.ui_demo_server_title),
            description = stringResource(R.string.ui_demo_server_description),
            subject = SubjectCode.GENERAL, dueDate = LocalDate.of(2026, 10, 29),
            priority = TaskPriority.MEDIUM, completed = true,
        )),
    )
}

@Composable
private fun ConflictPreview(deleted: Boolean = false, long: Boolean = false, selected: ConflictSide? = null) {
    StudyFlowTheme {
        var choice by remember { mutableStateOf(selected) }
        ConflictScreen(conflictExample(deleted, long).copy(selected = choice), { choice = it }, {}, {})
    }
}

@Preview(name = "Minh họa sửa–sửa · chưa chọn", showBackground = true, locale = "vi", heightDp = 1100)
@Composable private fun EditEditUnselected() = ConflictPreview()

@Preview(name = "Minh họa sửa–sửa · đã chọn", showBackground = true, locale = "vi", heightDp = 1100)
@Composable private fun EditEditSelected() = ConflictPreview(selected = ConflictSide.DEVICE)

@Preview(name = "Minh họa xóa–sửa · chưa chọn", showBackground = true, locale = "vi", heightDp = 1100)
@Composable private fun DeleteEditUnselected() = ConflictPreview(deleted = true)

@Preview(name = "Minh họa xóa–sửa · đã chọn", showBackground = true, locale = "vi", heightDp = 1100)
@Composable private fun DeleteEditSelected() = ConflictPreview(deleted = true, selected = ConflictSide.SERVER)

@Preview(name = "Minh họa nội dung dài · chữ lớn", showBackground = true, locale = "vi", fontScale = 2f, heightDp = 1100)
@Composable private fun LongContentSelected() = ConflictPreview(long = true, selected = ConflictSide.DEVICE)

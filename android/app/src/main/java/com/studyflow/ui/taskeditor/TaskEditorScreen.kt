package com.studyflow.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.domain.model.SubjectCode
import com.studyflow.domain.model.TaskPriority
import com.studyflow.ui.components.labelResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    state: TaskEditorUiState,
    onDraftChange: ((TaskDraft) -> TaskDraft) -> Unit,
    onContinueEditing: () -> Unit,
    onDiscardChanges: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val titleFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val titleInView = remember { BringIntoViewRequester() }
    val descriptionInView = remember { BringIntoViewRequester() }
    val focusManager = LocalFocusManager.current
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }
    val scrollState = rememberScrollState()
    LaunchedEffect(state.hasStorageError) {
        if (state.hasStorageError) scrollState.scrollTo(0)
    }

    LaunchedEffect(state.validationAttempt) {
        if (state.validationAttempt > 0) {
            when {
                state.validation.title != null -> {
                    titleFocus.requestFocus()
                    titleInView.bringIntoView()
                }
                state.validation.descriptionTooLong -> {
                    descriptionFocus.requestFocus()
                    descriptionInView.bringIntoView()
                }
            }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.hasStorageError) {
            Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            stringResource(R.string.task_editor_storage_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.bringIntoViewRequester(titleInView)) {
            OutlinedTextField(
                value = draft.title,
                enabled = !state.isSaving,
                onValueChange = { value -> onDraftChange { it.copy(title = value) } },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                label = { Text(stringResource(R.string.task_editor_title)) },
                singleLine = true,
                isError = state.validation.title != null,
                supportingText = {
                    Text(stringResource(when (state.validation.title) {
                        TitleError.REQUIRED -> R.string.task_title_required
                        TitleError.TOO_LONG -> R.string.task_title_too_long
                        null -> R.string.task_title_hint
                    }))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
            )
        }
        Column(Modifier.bringIntoViewRequester(descriptionInView)) {
            OutlinedTextField(
                value = draft.description,
                enabled = !state.isSaving,
                onValueChange = { value -> onDraftChange { it.copy(description = value) } },
                modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus),
                label = { Text(stringResource(R.string.task_editor_description)) },
                minLines = 4,
                maxLines = 8,
                isError = state.validation.descriptionTooLong,
                supportingText = {
                    Text(if (state.validation.descriptionTooLong) {
                        stringResource(R.string.task_description_too_long)
                    } else stringResource(R.string.task_description_count, draft.description.length))
                },
            )
        }
        EditorHeading(stringResource(R.string.task_subject_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubjectCode.entries.forEach { subject ->
                FilterChip(
                    selected = draft.subjectCode == subject,
                    enabled = !state.isSaving,
                    onClick = { onDraftChange { it.copy(subjectCode = subject) } },
                    label = { Text(stringResource(subject.labelResource())) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        EditorHeading(stringResource(R.string.task_due_label))
        Text(
            draft.dueDate?.format(formatter) ?: stringResource(R.string.task_no_due_date),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (draft.dueDate?.isBefore(LocalDate.now()) == true) {
            Text(stringResource(R.string.task_overdue), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { focusManager.clearFocus(); showDatePicker = true },
                enabled = !state.isSaving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.task_choose_date)) }
            if (draft.dueDate != null) {
                TextButton(
                    onClick = { onDraftChange { it.copy(dueDate = null) } },
                    enabled = !state.isSaving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.task_clear_date)) }
            }
        }
        EditorHeading(stringResource(R.string.task_priority_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskPriority.entries.forEach { priority ->
                FilterChip(
                    selected = draft.priority == priority,
                    enabled = !state.isSaving,
                    onClick = { onDraftChange { it.copy(priority = priority) } },
                    label = { Text(stringResource(when (priority) {
                        TaskPriority.LOW -> R.string.task_editor_priority_low
                        TaskPriority.MEDIUM -> R.string.task_editor_priority_medium
                        TaskPriority.HIGH -> R.string.task_editor_priority_high
                    })) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (state.unavailable) {
            Text(stringResource(R.string.task_unavailable_body),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showDatePicker) {
        // Material3 DatePicker represents calendar dates at midnight UTC, independent of device zone.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = draft.dueDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val date = pickerState.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    onDraftChange { it.copy(dueDate = date) }
                    showDatePicker = false
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_date_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_cancel))
                }
            },
        ) { DatePicker(state = pickerState, modifier = Modifier.verticalScroll(rememberScrollState())) }
    }

    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = onContinueEditing,
            title = { Text(stringResource(R.string.task_discard_title)) },
            text = { Text(stringResource(R.string.task_discard_body)) },
            confirmButton = {
                TextButton(onClick = onDiscardChanges, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = onContinueEditing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_continue_editing))
                }
            },
        )
    }
}

@Composable
private fun EditorHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

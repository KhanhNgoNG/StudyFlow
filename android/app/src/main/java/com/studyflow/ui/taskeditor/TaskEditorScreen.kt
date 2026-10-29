package com.studyflow.ui.taskeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val titleFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }
    val titleInView = remember { BringIntoViewRequester() }
    val descriptionInView = remember { BringIntoViewRequester() }
    val focusManager = LocalFocusManager.current
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showSubjects by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("dd/MM/yyyy", locale) }
    val scrollState = rememberScrollState()
    val titleLabel = stringResource(R.string.task_editor_title)
    val descriptionLabel = stringResource(R.string.task_editor_description)
    val subjectLabel = stringResource(R.string.task_subject_label)
    val chooseDateLabel = stringResource(R.string.task_choose_date)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.hasStorageError) {
            Text(stringResource(R.string.task_write_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge)
        }
        Column(Modifier.bringIntoViewRequester(titleInView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorHeading(titleLabel)
            OutlinedTextField(
                value = draft.title, enabled = !state.isSaving,
                onValueChange = { value -> onDraftChange { it.copy(title = value) } },
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).semantics { contentDescription = titleLabel },
                maxLines = 3, shape = MaterialTheme.shapes.small, colors = fieldColors,
                isError = state.validation.title != null,
                supportingText = if (state.validation.title != null) {{
                    Text(stringResource(if (state.validation.title == TitleError.REQUIRED) R.string.task_title_required else R.string.task_title_too_long))
                }} else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
            )
        }
        Column(Modifier.bringIntoViewRequester(descriptionInView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorHeading(descriptionLabel)
            OutlinedTextField(
                value = draft.description, enabled = !state.isSaving,
                onValueChange = { value -> onDraftChange { it.copy(description = value) } },
                modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus).semantics { contentDescription = descriptionLabel },
                minLines = 4, maxLines = 8, shape = MaterialTheme.shapes.small, colors = fieldColors,
                isError = state.validation.descriptionTooLong,
                supportingText = {
                    Text(if (state.validation.descriptionTooLong) stringResource(R.string.task_description_too_long)
                        else stringResource(R.string.task_description_count, draft.description.length))
                },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorHeading(subjectLabel)
            ExposedDropdownMenuBox(
                expanded = showSubjects,
                onExpandedChange = { focusManager.clearFocus(); showSubjects = it },
            ) {
                OutlinedTextField(
                    value = stringResource(draft.subjectCode.labelResource()), onValueChange = {},
                    readOnly = true, enabled = !state.isSaving, maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = !state.isSaving)
                        .semantics { contentDescription = subjectLabel },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showSubjects) },
                    shape = MaterialTheme.shapes.small, colors = fieldColors,
                )
                ExposedDropdownMenu(expanded = showSubjects, onDismissRequest = { showSubjects = false }) {
                    SubjectCode.entries.forEach { subject ->
                        val isSelected = draft.subjectCode == subject
                        DropdownMenuItem(
                            text = { Text(stringResource(subject.labelResource())) },
                            onClick = { onDraftChange { it.copy(subjectCode = subject) }; showSubjects = false },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { selected = isSelected },
                            trailingIcon = if (isSelected) {{ Icon(painterResource(R.drawable.ic_detail_check), null) }} else null,
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorHeading(stringResource(R.string.task_due_label))
            OutlinedCard(
                onClick = { focusManager.clearFocus(); showDatePicker = true }, enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = chooseDateLabel },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_detail_calendar), null, Modifier.size(24.dp))
                    Text(draft.dueDate?.format(formatter) ?: stringResource(R.string.task_no_due_date),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp))
                }
            }
            if (draft.dueDate?.isBefore(LocalDate.now()) == true) {
                Text(stringResource(R.string.task_overdue), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge)
            }
            if (draft.dueDate != null) {
                TextButton(onClick = { onDraftChange { it.copy(dueDate = null) } },
                    enabled = !state.isSaving, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.task_clear_date))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorHeading(stringResource(R.string.task_detail_priority))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskPriority.entries.forEach { priority ->
                    val isSelected = draft.priority == priority
                    FilterChip(
                        selected = isSelected, enabled = !state.isSaving,
                        onClick = { onDraftChange { it.copy(priority = priority) } },
                        label = { Text(stringResource(when (priority) {
                            TaskPriority.LOW -> R.string.task_editor_priority_low
                            TaskPriority.MEDIUM -> R.string.task_editor_priority_medium
                            TaskPriority.HIGH -> R.string.task_editor_priority_high
                        })) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_detail_flag), null, Modifier.size(20.dp),
                                tint = when {
                                    state.isSaving -> LocalContentColor.current
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    priority == TaskPriority.HIGH -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                })
                        },
                        modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
        if (state.unavailable) {
            Text(stringResource(R.string.task_unavailable_body), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { focusManager.clearFocus(); onSave() }, enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(16.dp),
        ) {
            Text(stringResource(if (state.isSaving) R.string.task_saving else R.string.ui_save_task),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_detail_cloud), null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.task_editor_storage_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
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

package com.studyflow.ui.conflict

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.components.labelResource
import com.studyflow.ui.theme.LocalStudyFlowColors
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Pure UI: the caller owns selection and decides what confirmation means. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    state: ConflictUiState,
    onSelect: (ConflictSide) -> Unit,
    onKeepSelected: (ConflictSide) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_title), modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.task_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val colors = LocalStudyFlowColors.current
                Surface(color = colors.warningContainer, contentColor = colors.onWarningContainer,
                    shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_ui_warning), null, Modifier.size(24.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.ui_conflict_intro), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.conflict_explanation), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(stringResource(R.string.conflict_differences_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    VersionCard(state.device, ConflictSide.DEVICE, state, onSelect)
                    VersionCard(state.server, ConflictSide.SERVER, state, onSelect)
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.conflict_whole_version_warning), Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { state.selected?.let(onKeepSelected) }, enabled = state.selected != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.conflict_keep_selected)) }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.conflict_decide_later))
                }
            }
        }
    }
}

@Composable
private fun VersionCard(version: ConflictVersion, side: ConflictSide, state: ConflictUiState, onSelect: (ConflictSide) -> Unit) {
    val selected = state.selected == side
    val label = stringResource(if (side == ConflictSide.DEVICE) R.string.conflict_device else R.string.conflict_server)
    Card(
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = { onSelect(side) }),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    if (state.isDeleteEdit) {
                        Text(stringResource(if (version == ConflictVersion.Deleted) R.string.conflict_confirm_delete else R.string.conflict_keep_task),
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            when (version) {
                ConflictVersion.Deleted -> {
                    Text(stringResource(R.string.conflict_deleted), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.conflict_deleted_body), style = MaterialTheme.typography.bodyMedium)
                }
                is ConflictVersion.Present -> SnapshotFields(version.content, state.differentFields)
            }
        }
    }
}

@Composable
private fun SnapshotFields(snapshot: ConflictTaskSnapshot, differences: Set<ConflictField>) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val fields = listOf(
        Triple(ConflictField.TITLE, R.string.task_editor_title, snapshot.title),
        Triple(ConflictField.DESCRIPTION, R.string.task_editor_description,
            snapshot.description.ifBlank { stringResource(R.string.task_empty_description) }),
        Triple(ConflictField.SUBJECT, R.string.task_subject_label, stringResource(snapshot.subject.labelResource())),
        Triple(ConflictField.DEADLINE, R.string.task_due_label,
            snapshot.dueDate?.format(formatter) ?: stringResource(R.string.task_no_due_date)),
        Triple(ConflictField.PRIORITY, R.string.task_priority_label, stringResource(snapshot.priority.labelResource())),
        Triple(ConflictField.COMPLETED, R.string.task_completion_label,
            stringResource(if (snapshot.completed) R.string.tasks_filter_completed else R.string.tasks_filter_incomplete)),
    )
    fields.take(2).forEach { (field, label, value) ->
        SnapshotField(label, value, field in differences, Modifier.fillMaxWidth())
    }
    BoxWithConstraints {
        val twoColumns = maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f
        FlowRow(maxItemsInEachRow = if (twoColumns) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.drop(2).forEach { (field, label, value) ->
                SnapshotField(label, value, field in differences, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SnapshotField(label: Int, value: String, differs: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        border = if (differs) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(8.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (differs) Text(stringResource(R.string.conflict_field_differs), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

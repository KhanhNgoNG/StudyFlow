package com.studyflow.ui.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.components.LocalReadStatus
import com.studyflow.ui.theme.LocalStudyFlowColors

@Composable
fun SyncScreen(state: SyncUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val colors = LocalStudyFlowColors.current
        Surface(color = colors.warningContainer, contentColor = colors.onWarningContainer,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_detail_cloud), null, Modifier.size(28.dp))
                Text(stringResource(R.string.sync_not_connected), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
        }
        Text(stringResource(R.string.sync_explanation), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.isLoading || state.hasReadError) {
                    LocalReadStatus(state.isLoading, state.hasReadError, onRetry)
                } else {
                    Text(stringResource(R.string.sync_pending_count, state.pendingCount),
                        style = MaterialTheme.typography.titleLarge)
                    if (state.pendingDeletedCount > 0) {
                        Text(stringResource(R.string.sync_pending_deleted, state.pendingDeletedCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.sync_last_never), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.sync_now))
        }
        Text(stringResource(R.string.sync_disabled_reason), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.sync_storage_notice), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

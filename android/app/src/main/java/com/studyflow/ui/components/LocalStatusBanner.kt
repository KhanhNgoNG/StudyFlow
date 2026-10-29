package com.studyflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.studyflow.R
import com.studyflow.ui.theme.LocalStudyFlowColors

/** Local metadata only. A pending record is neither a network event nor an outbox operation. */
@Composable
internal fun LocalStatusBanner(pendingCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalStudyFlowColors.current
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
        color = if (pendingCount > 0) colors.warningContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (pendingCount > 0) colors.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ui_pending_records, pendingCount), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.sync_not_connected), style = MaterialTheme.typography.bodySmall)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp))
        }
    }
}

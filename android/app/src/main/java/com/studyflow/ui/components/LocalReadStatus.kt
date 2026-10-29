package com.studyflow.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyflow.R

@Composable
internal fun LocalReadStatus(isLoading: Boolean, hasReadError: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isLoading) {
            CircularProgressIndicator()
            Text(stringResource(R.string.tasks_loading), style = MaterialTheme.typography.bodyLarge)
        } else if (hasReadError) {
            Text(stringResource(R.string.tasks_read_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.tasks_retry))
            }
        }
    }
}

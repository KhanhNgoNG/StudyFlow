package com.studyflow.ui.taskdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studyflow.R

/** A wrapping app bar keeps the full title readable on narrow screens and at large font sizes. */
@Composable
fun TaskDetailTopBar(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.task_back))
            }
            Text(
                stringResource(R.string.task_detail_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp).semantics { heading() },
            )
            IconButton(onClick = onEdit, enabled = canEdit, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_detail_edit), stringResource(R.string.task_detail_edit))
            }
        }
    }
}

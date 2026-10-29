package com.studyflow.ui.sync

data class SyncUiState(
    val pendingCount: Int = 0,
    val pendingDeletedCount: Int = 0,
    val isLoading: Boolean = true,
    val hasReadError: Boolean = false,
)

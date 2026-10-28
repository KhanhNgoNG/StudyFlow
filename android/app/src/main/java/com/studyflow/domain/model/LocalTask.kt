package com.studyflow.domain.model

enum class LocalSyncState { PENDING, SYNCED }

/** Local bookkeeping, not a server payload or a server-generated version. */
data class LocalTask(
    val task: Task,
    val baseVersion: Long,
    val localRevision: Long,
    val localUpdatedAt: Long,
    val syncState: LocalSyncState,
)

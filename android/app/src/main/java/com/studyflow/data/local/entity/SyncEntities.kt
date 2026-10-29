package com.studyflow.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.studyflow.sync.api.ApiTask
import com.studyflow.sync.api.TaskSnapshot

/** One unresolved request per task, with an immutable business snapshot. */
@Entity(tableName = "sync_outbox", indices = [Index(value = ["mutationId"], unique = true)])
data class OutboxEntity(
    @PrimaryKey val taskId: String,
    val mutationId: String,
    val operation: String,
    val baseVersion: Long,
    val localRevision: Long,
    @Embedded(prefix = "snapshot_") val snapshot: TaskSnapshot,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val retryNotBefore: Long?,
)

@Entity(tableName = "sync_conflicts")
data class ConflictEntity(
    @PrimaryKey val taskId: String,
    @Embedded(prefix = "local_") val local: TaskSnapshot,
    val localBaseVersion: Long,
    val localRevision: Long,
    val localUpdatedAt: Long,
    val localPending: Boolean,
    @Embedded(prefix = "server_") val server: ApiTask,
    val mutationId: String?,
)

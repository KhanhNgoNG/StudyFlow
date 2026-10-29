package com.studyflow.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.studyflow.data.local.entity.ConflictEntity
import com.studyflow.data.local.entity.OutboxEntity

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_outbox ORDER BY taskId")
    suspend fun outbox(): List<OutboxEntity>
    @Query("SELECT * FROM sync_outbox WHERE taskId = :id")
    suspend fun mutation(id: String): OutboxEntity?
    @Upsert suspend fun putMutation(mutation: OutboxEntity)
    @Query("DELETE FROM sync_outbox WHERE taskId = :id")
    suspend fun removeMutation(id: String)
    @Query("SELECT * FROM sync_conflicts ORDER BY taskId")
    suspend fun conflicts(): List<ConflictEntity>
    @Query("SELECT * FROM sync_conflicts WHERE taskId = :id")
    suspend fun conflict(id: String): ConflictEntity?
    @Insert suspend fun putConflict(conflict: ConflictEntity)
}

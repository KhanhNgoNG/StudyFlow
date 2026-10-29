package com.studyflow.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.studyflow.data.local.converter.TaskConverters
import com.studyflow.data.local.dao.TaskDao
import com.studyflow.data.local.entity.TaskEntity
import com.studyflow.data.local.entity.OutboxEntity
import com.studyflow.data.local.entity.ConflictEntity
import com.studyflow.data.local.dao.SyncDao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.studyflow.sync.SyncCoordinator

@Database(entities = [TaskEntity::class, OutboxEntity::class, ConflictEntity::class], version = 2, exportSchema = true)
@TypeConverters(TaskConverters::class)
abstract class StudyFlowDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun syncDao(): SyncDao
    val syncCoordinator = SyncCoordinator()

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The v1 tasks table is deliberately untouched, including all tombstones/metadata.
                db.execSQL("""CREATE TABLE IF NOT EXISTS sync_outbox (
                    taskId TEXT NOT NULL PRIMARY KEY, mutationId TEXT NOT NULL, operation TEXT NOT NULL,
                    baseVersion INTEGER NOT NULL, localRevision INTEGER NOT NULL,
                    snapshot_id TEXT NOT NULL, snapshot_title TEXT NOT NULL, snapshot_description TEXT NOT NULL,
                    snapshot_subjectCode TEXT NOT NULL, snapshot_dueDate TEXT, snapshot_priority TEXT NOT NULL,
                    snapshot_completed INTEGER NOT NULL, snapshot_isDeleted INTEGER NOT NULL,
                    state TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT, retryNotBefore INTEGER)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_mutationId ON sync_outbox(mutationId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS sync_conflicts (
                    taskId TEXT NOT NULL PRIMARY KEY,
                    local_id TEXT NOT NULL, local_title TEXT NOT NULL, local_description TEXT NOT NULL,
                    local_subjectCode TEXT NOT NULL, local_dueDate TEXT, local_priority TEXT NOT NULL,
                    local_completed INTEGER NOT NULL, local_isDeleted INTEGER NOT NULL,
                    localBaseVersion INTEGER NOT NULL, localRevision INTEGER NOT NULL,
                    localUpdatedAt INTEGER NOT NULL, localPending INTEGER NOT NULL,
                    server_id TEXT NOT NULL, server_title TEXT NOT NULL, server_description TEXT NOT NULL,
                    server_subjectCode TEXT NOT NULL, server_dueDate TEXT, server_priority TEXT NOT NULL,
                    server_completed INTEGER NOT NULL, server_isDeleted INTEGER NOT NULL,
                    server_version INTEGER NOT NULL, server_serverUpdatedAt TEXT NOT NULL, mutationId TEXT)""")
            }
        }

        fun create(context: Context): StudyFlowDatabase = Room.databaseBuilder(
            context.applicationContext, StudyFlowDatabase::class.java, "studyflow.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}

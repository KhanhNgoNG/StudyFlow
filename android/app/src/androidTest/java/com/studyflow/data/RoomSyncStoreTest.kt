package com.studyflow.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyflow.data.local.database.StudyFlowDatabase
import com.studyflow.data.local.entity.TaskEntity
import com.studyflow.data.repository.*
import com.studyflow.domain.model.*
import com.studyflow.sync.*
import com.studyflow.sync.api.*
import com.studyflow.sync.fake.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StudyFlowDatabase
    private val files = mutableListOf<String>()
    private val content = TaskContent("Study", "Saved locally", SubjectCode.MOBILE,
        LocalDate.of(2026, 10, 29), TaskPriority.HIGH)
    @Before fun before() { db = Room.inMemoryDatabaseBuilder(context, StudyFlowDatabase::class.java).build() }
    @After fun after() {
        db.close()
        files.forEach { check(it.startsWith("studyflow-sync-test-")); context.deleteDatabase(it) }
    }
    private fun fileName() = "studyflow-sync-test-${UUID.randomUUID()}.db".also { files += it }
    private fun open(name: String) = Room.databaseBuilder(context, StudyFlowDatabase::class.java, name)
        .addMigrations(StudyFlowDatabase.MIGRATION_1_2).build()

    @Test fun migrationFromRealV1PreservesEveryTaskColumnAndTombstone() = runBlocking {
        db.close(); val name = fileName()
        val originals = listOf(
            TaskEntity(UUID.randomUUID().toString(), "Synced", "Original text", SubjectCode.DATABASE,
                LocalDate.of(2026, 2, 28), TaskPriority.LOW, true, false, 42, 7, 54321, LocalSyncState.SYNCED),
            TaskEntity(UUID.randomUUID().toString(), "Deleted pending", "Tombstone", SubjectCode.OTHER,
                null, TaskPriority.HIGH, false, true, 8, 19, 12345, LocalSyncState.PENDING),
            TaskEntity(UUID.randomUUID().toString(), "Never sent", "Local", SubjectCode.GENERAL,
                null, TaskPriority.MEDIUM, false, false, 0, 3, 98765, LocalSyncState.PENDING),
        )
        withContext(Dispatchers.IO) {
            // Exact tasks DDL + identity from the preserved, exported schemas/.../1.json.
            val path = context.getDatabasePath(name); path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("""CREATE TABLE IF NOT EXISTS tasks (id TEXT NOT NULL, title TEXT NOT NULL,
                    description TEXT NOT NULL, subjectCode TEXT NOT NULL, dueDate TEXT, priority TEXT NOT NULL,
                    isCompleted INTEGER NOT NULL, isDeleted INTEGER NOT NULL, baseVersion INTEGER NOT NULL,
                    localRevision INTEGER NOT NULL, localUpdatedAt INTEGER NOT NULL, syncState TEXT NOT NULL,
                    PRIMARY KEY(id))""")
                old.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES(42, '3d804c1572a1a36bec2f04b478e36817')")
                originals.forEach { task ->
                    old.execSQL("INSERT INTO tasks VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(task.id, task.title,
                        task.description, task.subjectCode.name, task.dueDate?.toString(), task.priority.name,
                        if (task.isCompleted) 1 else 0, if (task.isDeleted) 1 else 0, task.baseVersion,
                        task.localRevision, task.localUpdatedAt, task.syncState.name))
                }
                old.version = 1
            }
        }
        db = open(name)
        // Opening through Room runs the migration AND its generated full schema validation.
        assertEquals(originals.sortedBy { it.id }, db.taskDao().getStored())
        assertTrue(db.syncDao().outbox().isEmpty()); assertTrue(db.syncDao().conflicts().isEmpty())
        withContext(Dispatchers.IO) { assertEquals(2, db.openHelper.readableDatabase.version) }
        assertEquals(2, withTimeout(5_000) { RoomTaskRepository(db).observeActiveTasks().first() }.size)
    }

    @Test fun durableOutboxResumesSameRequestAfterDatabaseAndEngineReopen() = runBlocking {
        db.close(); val name = fileName(); db = open(name)
        val id = RoomTaskRepository(db).create(content).id
        val api = StatefulFakeTaskApi(); api.enqueue(FakePlan(FakeFault.LOST_AFTER_COMMIT))
        assertEquals(1, SyncEngine(RoomSyncStore(db), api).sync().pending)
        val stored = db.syncDao().outbox().single()
        assertEquals(0L, db.taskDao().getStoredById(id)?.baseVersion)
        db.close(); db = open(name)
        assertEquals(stored, db.syncDao().outbox().single())
        assertTrue(SyncEngine(RoomSyncStore(db), api).sync().complete)
        assertEquals(api.requests()[0], api.requests()[1])
        assertEquals(1, api.commitCount()); assertTrue(db.syncDao().outbox().isEmpty())
        assertEquals(LocalSyncState.SYNCED, db.taskDao().getStoredById(id)?.syncState)
    }

    @Test fun pullStoresNewServerTaskAndTombstoneButActiveFlowHidesDeletedTask() = runBlocking {
        val api = StatefulFakeTaskApi()
        val active = TaskSnapshot(UUID.randomUUID().toString(), "Remote active", "", "OTHER", null, "LOW", false, false)
        api.changeFromOtherClient(active)
        api.changeFromOtherClient(active.copy(id = UUID.randomUUID().toString(), title = "Remote tombstone", isDeleted = true))
        assertTrue(SyncEngine(RoomSyncStore(db), api).sync().complete)
        assertEquals(2, db.taskDao().getStored().size)
        assertTrue(db.taskDao().getStored().all { it.syncState == LocalSyncState.SYNCED })
        val visible = withTimeout(5_000) { RoomTaskRepository(db).observeActiveTasks().first() }
        assertEquals(listOf(active.id), visible.map { it.task.id })
        assertTrue(api.requests().isEmpty())
    }

    @Test fun retryAfterSurvivesDatabaseReopenAndDoesNotSendEarly() = runBlocking {
        db.close(); val name = fileName(); db = open(name)
        val id = RoomTaskRepository(db).create(content).id
        val fake = StatefulFakeTaskApi(); val sent = mutableListOf<MutationRequest>(); var clock = 0L
        val api = object : TaskApi by fake {
            override suspend fun mutate(request: MutationRequest): ApiResponse<TaskEnvelope> {
                sent += request
                return if (sent.size == 1) ApiResponse(429, retryAfterSeconds = 60) else fake.mutate(request)
            }
        }
        SyncEngine(RoomSyncStore(db), api, now = { clock }).sync()
        assertEquals(60_000L, db.syncDao().mutation(id)?.retryNotBefore)
        db.close(); db = open(name)
        val engine = SyncEngine(RoomSyncStore(db), api, now = { clock })
        assertEquals(1, engine.sync().pending); assertEquals(1, sent.size)
        clock = 60_000L
        assertTrue(engine.sync().complete); assertEquals(sent[0], sent[1])
    }

    @Test fun twoRoomStoreInstancesShareOneRunCoordinator() = runBlocking {
        RoomTaskRepository(db).create(content)
        val api = StatefulFakeTaskApi(); val gate = RequestGate()
        api.enqueue(FakePlan(beforeCommit = gate))
        val first = async { SyncEngine(RoomSyncStore(db), api).sync() }; gate.started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { SyncEngine(RoomSyncStore(db), api).sync() }
        assertFalse(second.isCompleted); assertEquals(1, api.requests().size)
        gate.release.complete(Unit)
        assertTrue(first.await().complete); assertTrue(second.await().complete)
        assertEquals(1, api.requests().size)
    }

    @Test fun conflictSnapshotsSurviveReopenAndBlockEditingOnlyTheirTask() = runBlocking {
        db.close(); val name = fileName(); db = open(name)
        var repo = RoomTaskRepository(db); val api = StatefulFakeTaskApi()
        val id = repo.create(content).id; SyncEngine(RoomSyncStore(db), api).sync()
        repo.updateContent(id, content.copy(title = "Local"))
        val remote = requireNotNull(api.getTask(id).body?.task).snapshot().copy(title = "Other client")
        api.changeFromOtherClient(remote)
        assertEquals(1, SyncEngine(RoomSyncStore(db), api).sync().conflicts)
        val conflict = db.syncDao().conflicts().single(); val local = repo.getStoredTask(id)
        db.close(); db = open(name); repo = RoomTaskRepository(db)
        assertEquals(conflict, db.syncDao().conflicts().single())
        assertEquals(local, repo.getStoredTask(id))
        assertEquals(TaskWriteResult.Unavailable, repo.updateContent(id, content.copy(title = "Must stay locked")))
        assertEquals(TaskWriteResult.Unavailable, repo.toggleCompleted(id))
        assertEquals(TaskWriteResult.Unavailable, repo.softDelete(id))
        val otherId = repo.create(content.copy(title = "Independent task")).id
        val calls = api.requests().size
        val result = SyncEngine(RoomSyncStore(db), api).sync()
        assertEquals(1, result.conflicts); assertEquals(1, result.acknowledged)
        assertEquals(calls + 1, api.requests().size)
        assertEquals(LocalSyncState.SYNCED, repo.getStoredTask(otherId)?.syncState)
        assertEquals(conflict, db.syncDao().conflicts().single())
    }

    private suspend fun duringSend(change: suspend (RoomTaskRepository, String) -> Unit) = coroutineScope {
        val repo = RoomTaskRepository(db); val api = StatefulFakeTaskApi(); val id = repo.create(content).id
        val gate = RequestGate(); api.enqueue(FakePlan(beforeCommit = gate))
        val engine = SyncEngine(RoomSyncStore(db), api)
        val job = async { engine.sync() }; gate.started.await()
        // This completes before network release: no Room transaction is held over API suspension.
        withTimeout(5_000) { change(repo, id) }
        val latest = requireNotNull(repo.getStoredTask(id))
        gate.release.complete(Unit)
        assertEquals(1, job.await().pending)
        val acknowledged = requireNotNull(repo.getStoredTask(id))
        assertEquals(latest.task, acknowledged.task)
        assertEquals(latest.localRevision, acknowledged.localRevision)
        assertEquals(1L, acknowledged.baseVersion)
        assertEquals(LocalSyncState.PENDING, acknowledged.syncState)
        assertTrue(engine.sync().complete)
        assertEquals(1L, api.requests().last().baseVersion)
    }
    @Test fun roomContentEditDuringSendIsPreserved() = runBlocking {
        duringSend { repo, id -> repo.updateContent(id, content.copy(title = "Latest content")); Unit }
    }
    @Test fun roomToggleDuringSendIsPreserved() = runBlocking {
        duringSend { repo, id -> repo.toggleCompleted(id); Unit }
    }
    @Test fun roomDeleteDuringCreateIsPreservedAndLaterSent() = runBlocking {
        duringSend { repo, id -> repo.softDelete(id); Unit }
        assertTrue(withTimeout(5_000) { RoomTaskRepository(db).observeActiveTasks().first() }.isEmpty())
        assertTrue(db.taskDao().getStored().single().isDeleted)
    }

    @Test fun acknowledgementAndOutboxRemovalRollBackTogetherOnSQLiteFailure() = runBlocking {
        val id = RoomTaskRepository(db).create(content).id; val api = StatefulFakeTaskApi()
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_receipt_cleanup BEFORE DELETE ON sync_outbox
                BEGIN SELECT RAISE(ABORT, 'test acknowledgement failure'); END""")
        }
        val result = SyncEngine(RoomSyncStore(db), api).sync()
        assertEquals(SyncStage.ACKNOWLEDGE, result.issues.first().stage)
        assertEquals("STORAGE", result.issues.first().code)
        assertEquals(0L, db.taskDao().getStoredById(id)?.baseVersion)
        assertEquals(LocalSyncState.PENDING, db.taskDao().getStoredById(id)?.syncState)
        assertEquals(1, db.syncDao().outbox().size)
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_receipt_cleanup") }
        assertTrue(SyncEngine(RoomSyncStore(db), api).sync().complete)
        assertEquals(1, api.commitCount())
        assertEquals(api.requests()[0], api.requests()[1])
    }

    @Test fun cancellationIsNotWrappedAsStorageFailureAndTransactionRollsBack() = runBlocking {
        val store = RoomSyncStore(db)
        try {
            store.transaction {
                val snapshot = com.studyflow.sync.api.TaskSnapshot(UUID.randomUUID().toString(), "Canceled", "",
                    "OTHER", null, "MEDIUM", false, false)
                putTask(LocalSyncRecord(snapshot, 0, 1, 1, true))
                throw CancellationException("Deliberate cancellation")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* Expected, never converted to SyncStorageException. */ }
        assertTrue(db.taskDao().getStored().isEmpty())
    }
}

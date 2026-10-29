package com.studyflow.sync

import com.studyflow.sync.api.*
import com.studyflow.sync.fake.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private fun values(id: String = UUID.randomUUID().toString()) =
        TaskSnapshot(id, "Study", "Description", "MOBILE", "2026-10-29", "HIGH", false, false)
    private suspend fun MemorySyncStore.add(task: TaskSnapshot = values(), base: Long = 0, pending: Boolean = true): String {
        transaction { putTask(LocalSyncRecord(task, base, 1, 123, pending)) }
        return task.id
    }
    private suspend fun MemorySyncStore.local(id: String) = transaction { requireNotNull(task(id)) }
    private suspend fun MemorySyncStore.edit(id: String, change: (TaskSnapshot) -> TaskSnapshot) = transaction {
        val old = requireNotNull(task(id))
        putTask(old.copy(snapshot = change(old.snapshot), localRevision = old.localRevision + 1, pending = true))
    }
    private fun proxy(fake: TaskApi, push: (suspend (MutationRequest) -> ApiResponse<TaskEnvelope>)? = null,
        pull: (suspend () -> ApiResponse<TasksEnvelope>)? = null) = object : TaskApi by fake {
        override suspend fun mutate(request: MutationRequest) = push?.invoke(request) ?: fake.mutate(request)
        override suspend fun getTasks(includeDeleted: Boolean) = pull?.invoke() ?: fake.getTasks(includeDeleted)
    }

    @Test fun createUpdateDeleteUseExactContractAndServerVersions() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add()
        assertTrue(engine.sync().complete)
        assertEquals(1L, store.local(id).baseVersion)
        store.edit(id) { it.copy(title = "Edited") }
        assertTrue(engine.sync().complete)
        store.edit(id) { it.copy(isDeleted = true) }
        assertTrue(engine.sync().complete)
        val requests = api.requests()
        assertEquals(listOf("POST", "PUT", "DELETE"), requests.map { it.method })
        assertEquals(listOf(0L, 1L, 2L), requests.map { it.baseVersion })
        assertEquals("/api/v1/tasks/$id?baseVersion=2", requests.last().path)
        assertNull(requests.last().body)
        assertEquals(requests.first().mutationId, requests.first().headers["Idempotency-Key"])
        assertEquals(3, requests.map { it.mutationId }.toSet().size)
        assertTrue(requireNotNull(api.getTask(id).body?.task).isDeleted)
        assertEquals(3L, store.local(id).baseVersion)
        assertTrue(store.local(id).snapshot.isDeleted)
        assertEquals(3, api.commitCount())
    }

    @Test fun pullImportsNewTasksAndTombstonesWithoutPendingOrFakeWrites() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi()
        val active = api.changeFromOtherClient(values())
        val deleted = api.changeFromOtherClient(values().copy(isDeleted = true))
        assertTrue(SyncEngine(store, api).sync().complete)
        assertEquals(active.snapshot(), store.local(active.id).snapshot)
        assertEquals(deleted.snapshot(), store.local(deleted.id).snapshot)
        assertFalse(store.local(deleted.id).pending)
        assertTrue(api.requests().isEmpty())
    }

    @Test fun serverOnlyEditUpdatesCleanClientWithoutConflict() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync()
        val remote = api.changeFromOtherClient(store.local(id).snapshot.copy(title = "Other client"))
        assertTrue(engine.sync().complete)
        assertEquals(remote.snapshot(), store.local(id).snapshot)
        assertEquals(2L, store.local(id).baseVersion)
    }

    @Test fun independentEditsPersistBothSnapshotsAndBlockOnlyThatTask() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync()
        store.edit(id) { it.copy(title = "Local edit") }
        val local = store.local(id)
        val remote = api.changeFromOtherClient(local.snapshot.copy(title = "Remote edit"))
        val other = store.add()
        val result = engine.sync()
        assertEquals(1, result.conflicts)
        assertFalse(result.complete)
        assertEquals(local, store.local(id))
        assertFalse(store.local(other).pending)
        val conflict = store.transaction { requireNotNull(conflict(id)) }
        assertEquals(local, conflict.local)
        assertEquals(remote, conflict.server)
        val calls = api.requests().size
        api.changeFromOtherClient(remote.snapshot().copy(title = "Later remote edit"))
        engine.sync()
        assertEquals(calls, api.requests().size)
        assertEquals(conflict, store.transaction { conflict(id) })
    }

    @Test fun equalBusinessValuesConvergeWithoutComparingTimestamps() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync()
        store.edit(id) { it.copy(title = "Same intent") }
        api.changeFromOtherClient(store.local(id).snapshot)
        val result = engine.sync()
        assertTrue(result.complete)
        assertEquals(0, result.conflicts)
        assertEquals(2L, store.local(id).baseVersion)
        assertEquals(1, api.commitCount())
    }

    @Test fun bothDeletedConvergeEvenWithDifferentHistoricalDescriptions() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync()
        store.edit(id) { it.copy(isDeleted = true) }
        api.changeFromOtherClient(store.local(id).snapshot.copy(description = "Other history"))
        assertTrue(engine.sync().complete)
        assertTrue(store.local(id).snapshot.isDeleted)
    }

    @Test fun timeoutBeforeCommitRetriesExactRequestOncePerExplicitRun() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); api.enqueue(FakePlan(FakeFault.TIMEOUT_BEFORE))
        val first = engine.sync()
        assertEquals(0, api.commitCount()); assertEquals(1, first.pending)
        assertEquals(SyncStage.PUSH, first.issues.single().stage)
        val saved = store.transaction { requireNotNull(mutation(id)) }
        assertEquals(OutboxState.RETRY, saved.state)
        assertTrue(engine.sync().complete)
        assertEquals(api.requests()[0], api.requests()[1])
        assertEquals(saved.request(), api.requests()[1])
        assertEquals(1, api.commitCount())
    }

    @Test fun lostCommitResponseResumesReceiptBeforeVersionEvenAfterRemoteEdit() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi()
        val id = store.add(); api.enqueue(FakePlan(FakeFault.LOST_AFTER_COMMIT))
        assertFalse(SyncEngine(store, api).sync().complete)
        assertEquals(0L, store.local(id).baseVersion)
        val remote = api.changeFromOtherClient(values(id).copy(title = "Version two"))
        val result = SyncEngine(store, api).sync()
        assertTrue(result.complete); assertEquals(1, result.acknowledged)
        assertEquals(api.requests()[0], api.requests()[1])
        assertEquals(1, api.commitCount())
        assertEquals(remote.snapshot(), store.local(id).snapshot)
        assertEquals(2L, store.local(id).baseVersion)
    }

    @Test fun successfulReceiptRejectsReusedIdWithDifferentRequestBeforeValidation() = runTest {
        val api = StatefulFakeTaskApi(); val task = values()
        val request = MutationRequest(UUID.randomUUID().toString(), task.id, MutationOperation.CREATE, 0,
            TaskWriteBody.from(task, 0))
        val first = api.mutate(request)
        api.changeFromOtherClient(task.copy(title = "Other"))
        api.enqueue(FakePlan(FakeFault.VALIDATION))
        assertEquals(first, api.mutate(request))
        val changed = request.copy(body = request.body?.copy(title = ""))
        assertEquals("MUTATION_ID_REUSED", api.mutate(changed).error?.code)
        assertEquals(1, api.commitCount())
    }

    private suspend fun editDuringSend(change: (TaskSnapshot) -> TaskSnapshot) = coroutineScope {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); val gate = RequestGate(); api.enqueue(FakePlan(beforeCommit = gate))
        val run = async { engine.sync() }; gate.started.await()
        store.edit(id, change); val edited = store.local(id)
        gate.release.complete(Unit)
        val first = run.await()
        assertEquals(1, first.acknowledged); assertEquals(1, first.pending)
        assertEquals(edited.snapshot, store.local(id).snapshot)
        assertEquals(edited.localRevision, store.local(id).localRevision)
        assertEquals(1L, store.local(id).baseVersion)
        assertTrue(store.local(id).pending)
        assertTrue(engine.sync().complete)
        assertEquals(1L, api.requests().last().baseVersion)
        assertNotEquals(api.requests().first().mutationId, api.requests().last().mutationId)
    }
    @Test fun contentEditDuringSendSurvivesOldAcknowledgement() = runTest { editDuringSend { it.copy(title = "Revision two") } }
    @Test fun toggleDuringSendSurvivesOldAcknowledgement() = runTest { editDuringSend { it.copy(completed = true) } }
    @Test fun deleteDuringCreateSurvivesOldAcknowledgement() = runTest { editDuringSend { it.copy(isDeleted = true) } }

    @Test fun editAfterTimeoutDoesNotChangeUnresolvedRequestPayload() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); api.enqueue(FakePlan(FakeFault.TIMEOUT_BEFORE)); engine.sync()
        store.edit(id) { it.copy(title = "New draft") }
        val result = engine.sync()
        assertEquals(1, result.pending)
        assertEquals(api.requests()[0], api.requests()[1])
        assertEquals("New draft", store.local(id).snapshot.title)
        assertTrue(engine.sync().complete)
        assertEquals("New draft", api.requests()[2].body?.title)
    }

    @Test fun newCreateThenDeleteIsRemovedOnlyWhenProvenUnsent() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val id = store.add()
        store.edit(id) { it.copy(isDeleted = true) }
        assertTrue(SyncEngine(store, api).sync().complete)
        assertNull(store.transaction { task(id) })
        assertTrue(api.requests().isEmpty())
    }

    @Test fun deleteAfterUncertainCreateMustFirstRetryTheOriginalCreate() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); api.enqueue(FakePlan(FakeFault.LOST_AFTER_COMMIT)); engine.sync()
        store.edit(id) { it.copy(isDeleted = true) }
        assertEquals(1, engine.sync().pending)
        assertEquals(api.requests()[0], api.requests()[1])
        assertEquals(1L, store.local(id).baseVersion)
        assertTrue(engine.sync().complete)
        assertEquals(MutationOperation.DELETE, api.requests()[2].operation)
        assertEquals(2, api.commitCount())
    }

    private suspend fun cancelAndRecreate(afterCommit: Boolean) = coroutineScope {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val id = store.add()
        val gate = RequestGate()
        api.enqueue(if (afterCommit) FakePlan(afterCommit = gate) else FakePlan(beforeCommit = gate))
        val job = async { SyncEngine(store, api).sync() }
        val sent = gate.started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(sent, store.transaction { requireNotNull(mutation(id)).request() })
        assertTrue(SyncEngine(store, api).sync().complete)
        assertEquals(sent, api.requests().last())
        assertEquals(1, api.commitCount())
    }
    @Test fun cancellationBeforeCommitPropagatesAndRequestCanResume() = runTest { cancelAndRecreate(false) }
    @Test fun cancellationAfterCommitPropagatesAndReceiptCanResume() = runTest { cancelAndRecreate(true) }

    @Test fun separateEnginesSharingDatabaseSerializeWholeRuns() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); store.add()
        val gate = RequestGate(); api.enqueue(FakePlan(beforeCommit = gate))
        val first = async { SyncEngine(store, api).sync() }; gate.started.await()
        val second = async { SyncEngine(store, api).sync() }; runCurrent()
        assertEquals(1, api.requests().size); assertFalse(second.isCompleted)
        gate.release.complete(Unit)
        assertTrue(first.await().complete); assertTrue(second.await().complete)
        assertEquals(1, api.requests().size)
    }

    @Test fun serverErrorKeepsMutationForNextExplicitRetry() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); store.add()
        api.enqueue(FakePlan(FakeFault.SERVER_ERROR)); val engine = SyncEngine(store, api)
        assertEquals("HTTP_503", engine.sync().issues.single().code)
        assertEquals(1, api.requests().size)
        assertTrue(engine.sync().complete)
        assertEquals(api.requests()[0], api.requests()[1])
    }

    @Test fun rateLimitHonorsDurableRetryAfterUsingVirtualTime() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val id = store.add()
        val requests = mutableListOf<MutationRequest>()
        val api = proxy(fake, push = { request ->
            requests += request
            if (requests.size == 1) ApiResponse(429, retryAfterSeconds = 60) else fake.mutate(request)
        })
        val engine = SyncEngine(store, api, now = { testScheduler.currentTime })
        assertEquals("HTTP_429", engine.sync().issues.single().code)
        assertEquals(60_000L, store.transaction { mutation(id)?.retryNotBefore })
        advanceTimeBy(59_999); engine.sync(); assertEquals(1, requests.size)
        advanceTimeBy(1); assertTrue(SyncEngine(store, api, now = { testScheduler.currentTime }).sync().complete)
        assertEquals(requests[0], requests[1])
    }

    @Test fun lostDeleteResponseRetriesReceiptWithoutIncreasingVersionAgain() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync(); store.edit(id) { it.copy(isDeleted = true) }
        api.enqueue(FakePlan(FakeFault.LOST_AFTER_COMMIT)); assertFalse(engine.sync().complete)
        assertTrue(engine.sync().complete)
        assertEquals(api.requests()[1], api.requests()[2])
        assertEquals(2L, api.getTask(id).body?.task?.version)
        assertEquals(2, api.commitCount())
    }

    @Test fun validationBlocksUntilUserEditWithoutInfiniteRetry() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val id = store.add()
        api.enqueue(FakePlan(FakeFault.VALIDATION)); val engine = SyncEngine(store, api)
        assertEquals("VALIDATION_ERROR", engine.sync().issues.single().code)
        repeat(3) { assertFalse(engine.sync().complete) }
        assertEquals(1, api.requests().size)
        store.edit(id) { it.copy(title = "Corrected") }
        assertTrue(engine.sync().complete)
        assertNotEquals(api.requests()[0].mutationId, api.requests()[1].mutationId)
    }

    @Test fun validationBlockedTaskStillDetectsIndependentServerChangesOnPull() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync(); store.edit(id) { it.copy(title = "Local rejected edit") }
        api.enqueue(FakePlan(FakeFault.VALIDATION)); engine.sync()
        val local = store.local(id)
        val remote = api.changeFromOtherClient(local.snapshot.copy(title = "Independent remote edit"))
        val calls = api.requests().size
        assertEquals(1, engine.sync().conflicts)
        assertEquals(calls, api.requests().size)
        assertEquals(local, store.transaction { conflict(id)?.local })
        assertEquals(remote, store.transaction { conflict(id)?.server })
    }

    @Test fun validationBlockedTaskMayConvergeAfterASeparateClientMakesSameEdit() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync(); store.edit(id) { it.copy(title = "Same desired content") }
        api.enqueue(FakePlan(FakeFault.VALIDATION)); engine.sync()
        api.changeFromOtherClient(store.local(id).snapshot)
        val calls = api.requests().size
        assertTrue(engine.sync().complete)
        assertEquals(calls, api.requests().size)
        assertEquals(2L, store.local(id).baseVersion)
    }

    @Test fun mutationIdReusedBlocksWithoutHidingItWithNewId() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val id = store.add(); var calls = 0
        val api = proxy(fake, push = { calls++; ApiResponse(409, error = ApiError("MUTATION_ID_REUSED", id)) })
        val engine = SyncEngine(store, api)
        assertEquals("MUTATION_ID_REUSED", engine.sync().issues.single().code)
        val request = store.transaction { mutation(id)?.request() }
        store.edit(id) { it.copy(title = "Still blocked") }; engine.sync()
        assertEquals(1, calls)
        assertEquals(request, store.transaction { mutation(id)?.request() })
    }

    @Test fun malformedSuccessCannotBeAcknowledgedByMatchingPull() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val id = store.add()
        val broken = proxy(fake, push = { fake.mutate(it).copy(body = TaskEnvelope(null)) })
        val result = SyncEngine(store, broken).sync()
        assertEquals("INVALID_RESPONSE", result.issues.single().code)
        assertEquals(0, result.acknowledged); assertEquals(1, result.pending)
        assertEquals(0L, store.local(id).baseVersion)
        assertNotNull(store.transaction { mutation(id) })
        assertTrue(SyncEngine(store, fake).sync().complete)
        assertEquals(fake.requests()[0], fake.requests()[1])
    }

    @Test fun malformedConflictResponseKeepsOriginalRequestForSafeRetry() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val id = store.add()
        val api = proxy(fake, push = { ApiResponse(409, error = ApiError("VERSION_CONFLICT", id, currentTask = null)) })
        val result = SyncEngine(store, api).sync()
        assertEquals("INVALID_CONFLICT_RESPONSE", result.issues.single().code)
        assertEquals(0, result.conflicts); assertEquals(1, result.pending)
        assertEquals(OutboxState.RETRY, store.transaction { mutation(id)?.state })
    }

    @Test fun validationForWrongTaskCannotDiscardAnUncertainRequest() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val id = store.add()
        val api = proxy(fake, push = { ApiResponse(422, error = ApiError("VALIDATION_ERROR", UUID.randomUUID().toString())) })
        SyncEngine(store, api).sync()
        assertEquals(OutboxState.RETRY, store.transaction { mutation(id)?.state })
        val original = store.transaction { mutation(id)?.request() }
        store.edit(id) { it.copy(title = "Still preserve retry identity") }
        SyncEngine(store, api).sync()
        assertEquals(original, store.transaction { mutation(id)?.request() })
    }

    @Test fun invalidAcknowledgementIdVersionOrBusinessValuesPreserveOutbox() = runTest {
        val variants: List<(ApiTask) -> ApiTask> = listOf(
            { it.copy(id = UUID.randomUUID().toString()) }, { it.copy(version = 99) },
            { it.copy(title = "Wrong response") }, { it.copy(dueDate = "2026-02-30") },
            { it.copy(serverUpdatedAt = "invalid") },
        )
        for (breakResponse in variants) {
            val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val id = store.add()
            val api = proxy(fake, push = {
                val response = fake.mutate(it)
                response.copy(body = TaskEnvelope(breakResponse(requireNotNull(response.body?.task))))
            })
            assertEquals(0, SyncEngine(store, api).sync().acknowledged)
            assertTrue(store.local(id).pending)
            assertNotNull(store.transaction { mutation(id) })
        }
    }

    @Test fun invalidOrIncompletePullDoesNotPartiallyApplyOrEraseLocalTasks() = runTest {
        val fake = StatefulFakeTaskApi(); val valid = fake.changeFromOtherClient(values())
        val responses = listOf(ApiResponse<TasksEnvelope>(503), ApiResponse(200, TasksEnvelope(null)),
            ApiResponse(200, TasksEnvelope(listOf(valid, valid))),
            ApiResponse(200, TasksEnvelope(listOf(valid, valid.copy(id = UUID.randomUUID().toString(), priority = "INVALID")))))
        for (response in responses) {
            val store = MemorySyncStore(); val local = values(); store.add(local, 10, pending = false)
            val result = SyncEngine(store, proxy(fake, pull = { response })).sync()
            assertFalse(result.complete); assertEquals(SyncStage.PULL, result.issues.single().stage)
            assertEquals(listOf(local), store.transaction { tasks().map { it.snapshot } })
        }
    }

    @Test fun olderPullAndMissingIdsNeverOverwriteOrDeleteNewerLocalData() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val old = fake.changeFromOtherClient(values())
        store.add(old.snapshot().copy(title = "Newer"), base = 2, pending = false)
        assertTrue(SyncEngine(store, fake).sync().complete)
        assertEquals("Newer", store.local(old.id).snapshot.title)
        assertTrue(SyncEngine(store, proxy(fake, pull = { ApiResponse(200, TasksEnvelope(emptyList())) })).sync().complete)
        assertFalse(store.local(old.id).snapshot.isDeleted)
    }

    @Test fun cleanTombstoneIsNotAutomaticallyRestoredByPull() = runTest {
        val store = MemorySyncStore(); val fake = StatefulFakeTaskApi(); val task = values()
        store.add(task.copy(isDeleted = true), base = 1, pending = false)
        fake.changeFromOtherClient(task); fake.changeFromOtherClient(task)
        assertEquals(1, SyncEngine(store, fake).sync().conflicts)
        assertTrue(store.local(task.id).snapshot.isDeleted)
    }

    @Test fun conflictDuringSendCapturesLatestLocalRevision() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync(); store.edit(id) { it.copy(title = "Sending") }
        val gate = RequestGate(); api.enqueue(FakePlan(beforeCommit = gate))
        val job = async { engine.sync() }; gate.started.await()
        store.edit(id) { it.copy(description = "Latest revision") }
        val latest = store.local(id)
        val remote = api.changeFromOtherClient(latest.snapshot.copy(title = "Other client"))
        gate.release.complete(Unit); assertEquals(1, job.await().conflicts)
        assertEquals(latest, store.transaction { conflict(id)?.local })
        assertEquals(remote, store.transaction { conflict(id)?.server })
    }

    @Test fun independentDeleteVersusEditKeepsLocalTombstoneAndRemoteSnapshot() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync(); store.edit(id) { it.copy(isDeleted = true) }
        api.changeFromOtherClient(values(id).copy(title = "Remote edit"))
        assertEquals(1, engine.sync().conflicts)
        assertTrue(store.local(id).snapshot.isDeleted)
        assertFalse(requireNotNull(store.transaction { conflict(id) }).server.isDeleted)
    }

    @Test fun remoteDeleteVersusLocalEditNeverSilentlyDropsTheEdit() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val engine = SyncEngine(store, api)
        val id = store.add(); engine.sync(); store.edit(id) { it.copy(title = "Local edit") }
        api.changeFromOtherClient(values(id).copy(isDeleted = true))
        assertEquals(1, engine.sync().conflicts)
        assertEquals("Local edit", store.local(id).snapshot.title)
        assertTrue(requireNotNull(store.transaction { conflict(id) }).server.isDeleted)
    }

    @Test fun failedAcknowledgementLeavesOriginalMutationRecoverable() = runTest {
        val store = MemorySyncStore(); val api = StatefulFakeTaskApi(); val id = store.add()
        val gate = RequestGate(); api.enqueue(FakePlan(afterCommit = gate))
        val job = async { SyncEngine(store, api).sync() }; gate.started.await()
        store.failTaskWrite = true; gate.release.complete(Unit)
        val failure = job.await()
        assertEquals(SyncStage.ACKNOWLEDGE, failure.issues.first().stage)
        assertEquals("STORAGE", failure.issues.first().code)
        assertTrue(store.local(id).pending); assertNotNull(store.transaction { mutation(id) })
        store.failTaskWrite = false
        assertTrue(SyncEngine(store, api).sync().complete)
        assertEquals(1, api.commitCount())
    }
}

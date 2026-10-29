package com.studyflow.sync

import com.studyflow.sync.api.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/** Explicit caller-owned run; no background scope or automatic retry loop. */
class SyncEngine(
    private val store: SyncStore,
    private val api: TaskApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun sync(): SyncRunResult = store.coordinator.mutex.withLock {
        val runId = newId()
        var acknowledged = 0
        val issues = mutableListOf<SyncIssue>()
        var stage = SyncStage.PREPARE
        try {
            // Resume unresolved requests first. At most one attempt per task per explicit run.
            val ids = store.transaction {
                (mutations().map { it.taskId } + tasks().filter { it.pending }.map { it.snapshot.id }).distinct()
            }
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                stage = SyncStage.PREPARE
                val mutation = prepare(id) ?: continue
                stage = SyncStage.PUSH
                val response = try { api.mutate(mutation.request()) } catch (_: IOException) {
                    defer(mutation, OutboxState.RETRY, "TRANSPORT")
                    issues += SyncIssue(stage, "TRANSPORT", id)
                    continue
                }
                stage = SyncStage.ACKNOWLEDGE
                val server = response.body?.task
                val expectedStatus = if (mutation.operation == MutationOperation.CREATE) 201 else 200
                if (response.status == expectedStatus) {
                    if (response.error == null && server != null && validAcknowledgement(mutation, server)) {
                        store.transaction { acknowledge(mutation, server) }
                        acknowledged++
                    } else {
                        defer(mutation, OutboxState.RETRY, "INVALID_RESPONSE")
                        issues += SyncIssue(stage, "INVALID_RESPONSE", id)
                    }
                } else {
                    val code = response.error?.code
                    when {
                        response.status == 409 && code == "VERSION_CONFLICT" -> {
                            val current = response.error.currentTask
                            if (response.error.taskId == id && current != null && current.isValid() &&
                                current.id == id && current.version > mutation.baseVersion) {
                                store.transaction { conflictOrConverge(id, current, mutation.mutationId) }
                            } else {
                                defer(mutation, OutboxState.RETRY, "INVALID_CONFLICT_RESPONSE")
                                issues += SyncIssue(stage, "INVALID_CONFLICT_RESPONSE", id)
                            }
                        }
                        response.status == 422 && code == "VALIDATION_ERROR" && response.error.taskId == id -> {
                            defer(mutation, OutboxState.VALIDATION, "VALIDATION_ERROR")
                            issues += SyncIssue(stage, "VALIDATION_ERROR", id)
                        }
                        response.status == 409 && code == "MUTATION_ID_REUSED" -> {
                            defer(mutation, OutboxState.CONTRACT, "MUTATION_ID_REUSED")
                            issues += SyncIssue(stage, "MUTATION_ID_REUSED", id)
                        }
                        response.status >= 500 || response.status == 429 -> {
                            val retryAt = if (response.status == 429) response.retryAfterSeconds?.takeIf { it >= 0 }?.let {
                                val time = now()
                                time + it.coerceAtMost((Long.MAX_VALUE - time.coerceAtLeast(0)) / 1_000) * 1_000
                            } else null
                            defer(mutation, OutboxState.RETRY, "HTTP_${response.status}", retryAt)
                            issues += SyncIssue(stage, "HTTP_${response.status}", id)
                        }
                        response.status == 404 && code == "TASK_NOT_FOUND" && response.error.taskId == id -> {
                            defer(mutation, OutboxState.CONTRACT, "TASK_NOT_FOUND")
                            issues += SyncIssue(stage, "TASK_NOT_FOUND", id)
                        }
                        else -> {
                            // Also covers malformed 2xx: the outcome may have committed.
                            defer(mutation, OutboxState.RETRY, "UNEXPECTED_RESPONSE")
                            issues += SyncIssue(stage, "UNEXPECTED_RESPONSE", id)
                        }
                    }
                }
            }
            stage = SyncStage.PULL
            val response = try { api.getTasks(includeDeleted = true) } catch (_: IOException) { null }
            val remote = response?.body?.tasks
            if (response?.status != 200 || response.error != null || remote == null || remote.any { !it.isValid() } ||
                remote.map { it.id }.toSet().size != remote.size) {
                issues += SyncIssue(stage, if (response == null) "TRANSPORT" else "INVALID_PULL_RESPONSE")
            } else {
                stage = SyncStage.RECONCILE
                store.transaction { remote.forEach { reconcile(it, issues) } }
            }
        } catch (_: SyncStorageException) {
            issues += SyncIssue(stage, "STORAGE")
        }
        // CancellationException is deliberately not caught; the request remains recoverable.
        try {
            store.transaction {
                mutations().filter { it.state == OutboxState.VALIDATION || it.state == OutboxState.CONTRACT }
                    .forEach { mutation ->
                        if (issues.none { it.taskId == mutation.taskId && it.code == mutation.lastError }) {
                            issues += SyncIssue(SyncStage.PREPARE, mutation.lastError ?: mutation.state.name, mutation.taskId)
                        }
                    }
                SyncRunResult(runId, acknowledged, tasks().count { it.pending }, conflicts().size, issues.toList())
            }
        } catch (_: SyncStorageException) {
            SyncRunResult(runId, acknowledged, null, null, issues + SyncIssue(SyncStage.SUMMARY, "STORAGE"))
        }
    }

    private suspend fun prepare(id: String): OutboxMutation? = store.transaction {
        val local = task(id) ?: return@transaction null
        if (conflict(id) != null) return@transaction null
        var existing = mutation(id)
        // Definitive 422 did not commit. Only a real user edit may replace that request.
        // Ambiguous and contract failures NEVER acquire a fresh mutationId automatically.
        if (existing?.state == OutboxState.VALIDATION && existing.localRevision != local.localRevision) {
            removeMutation(id)
            existing = null
        }
        if (existing?.state in setOf(OutboxState.CONFLICT, OutboxState.VALIDATION, OutboxState.CONTRACT)) return@transaction null
        if (existing?.retryNotBefore?.let { it > now() } == true) return@transaction null
        if (existing == null && !local.pending) return@transaction null
        if (existing == null && local.baseVersion == 0L && local.snapshot.isDeleted) {
            // Entirely local create/delete, proven unsent: no fabricated SYNCED tombstone.
            removeTask(id)
            return@transaction null
        }
        val snapshot = existing ?: OutboxMutation(newId(), id,
            when {
                local.baseVersion == 0L -> MutationOperation.CREATE
                local.snapshot.isDeleted -> MutationOperation.DELETE
                else -> MutationOperation.UPDATE
            }, local.baseVersion, local.localRevision, local.snapshot)
        snapshot.copy(attempts = snapshot.attempts + 1, state = OutboxState.READY, lastError = null, retryNotBefore = null)
            .also { putMutation(it) }
    }

    private suspend fun defer(sent: OutboxMutation, state: OutboxState, code: String, retryAt: Long? = null) = store.transaction {
        check(mutation(sent.taskId)?.mutationId == sent.mutationId)
        putMutation(sent.copy(state = state, lastError = code, retryNotBefore = retryAt))
    }

    private fun validAcknowledgement(sent: OutboxMutation, server: ApiTask): Boolean =
        server.isValid() && server.id == sent.taskId && server.version == sent.baseVersion + 1 &&
            (if (sent.operation == MutationOperation.DELETE) server.isDeleted
             else !server.isDeleted && server.snapshot() == sent.snapshot)

    private suspend fun SyncTransaction.acknowledge(sent: OutboxMutation, server: ApiTask) {
        check(mutation(sent.taskId)?.mutationId == sent.mutationId)
        val local = requireNotNull(task(sent.taskId))
        check(local.baseVersion <= server.version)
        // Keep latest business values/revision, including deletion during an uncertain CREATE.
        putTask(local.copy(baseVersion = server.version, pending = local.localRevision != sent.localRevision))
        removeMutation(sent.taskId)
    }

    private suspend fun SyncTransaction.conflictOrConverge(id: String, server: ApiTask, mutationId: String?) {
        val local = requireNotNull(task(id))
        if (server.version < local.baseVersion) return
        if (local.snapshot.sameIntent(server.snapshot())) {
            putTask(local.copy(baseVersion = server.version, pending = false))
            removeMutation(id)
        } else {
            putConflict(StoredConflict(id, local, server, mutationId))
            mutation(id)?.let { putMutation(it.copy(state = OutboxState.CONFLICT, lastError = "VERSION_CONFLICT")) }
        }
    }

    private suspend fun SyncTransaction.reconcile(server: ApiTask, issues: MutableList<SyncIssue>) {
        val id = server.id
        val local = task(id)
        if (local == null) {
            putTask(LocalSyncRecord(server.snapshot(), server.version, 0, now(), pending = false))
            return
        }
        if (conflict(id) != null || server.version < local.baseVersion) return
        val unresolved = mutation(id)
        if (unresolved != null) {
            // A definitive 422 is known not to have committed, so independent server changes
            // can still be compared. Ambiguous writes must ALWAYS resume their original receipt.
            if (unresolved.state == OutboxState.VALIDATION &&
                (local.snapshot.sameIntent(server.snapshot()) || server.version > local.baseVersion)) {
                conflictOrConverge(id, server, unresolved.mutationId)
            }
            return
        }
        if (local.pending) {
            if (local.snapshot.sameIntent(server.snapshot()) || server.version > local.baseVersion) {
                conflictOrConverge(id, server, null)
            }
        } else if (server.version > local.baseVersion) {
            if (local.snapshot.isDeleted && !server.isDeleted) {
                putConflict(StoredConflict(id, local, server, null))
            } else {
                putTask(local.copy(snapshot = server.snapshot(), baseVersion = server.version, localUpdatedAt = now()))
            }
        } else if (!local.snapshot.sameIntent(server.snapshot())) {
            issues += SyncIssue(SyncStage.RECONCILE, "INCONSISTENT_SERVER_VERSION", id)
        }
    }
}

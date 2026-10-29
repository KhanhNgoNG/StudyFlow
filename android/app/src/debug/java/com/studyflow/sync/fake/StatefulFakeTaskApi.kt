package com.studyflow.sync.fake

import com.studyflow.sync.api.*
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deterministic test gate. Waiting never holds the server's data lock. */
class RequestGate {
    val started = CompletableDeferred<MutationRequest>()
    val release = CompletableDeferred<Unit>()
    internal suspend fun await(request: MutationRequest) {
        started.complete(request)
        release.await()
    }
}
enum class FakeFault { NONE, TIMEOUT_BEFORE, LOST_AFTER_COMMIT, SERVER_ERROR, VALIDATION }
data class FakePlan(
    val fault: FakeFault = FakeFault.NONE,
    val beforeCommit: RequestGate? = null,
    val afterCommit: RequestGate? = null,
)

/** Debug/test-only server. Its tasks/receipts are entirely separate from client Room. */
class StatefulFakeTaskApi(
    private val clock: () -> Instant = Instant::now,
) : TaskApi {
    private data class Receipt(val request: MutationRequest, val response: ApiResponse<TaskEnvelope>)
    private val mutex = Mutex()
    private val tasks = linkedMapOf<String, ApiTask>()
    private val receipts = mutableMapOf<String, Receipt>()
    private val plans = ArrayDeque<FakePlan>()
    private val received = mutableListOf<MutationRequest>()
    private var commits = 0

    suspend fun enqueue(plan: FakePlan) = mutex.withLock { plans.addLast(plan) }
    suspend fun requests(): List<MutationRequest> = mutex.withLock { received.toList() }
    suspend fun commitCount(): Int = mutex.withLock { commits }

    /** Simulates an independent client, with the same version/validation rules. */
    suspend fun changeFromOtherClient(snapshot: TaskSnapshot): ApiTask = mutex.withLock {
        require(snapshot.isValid())
        ApiTask.from(snapshot, (tasks[snapshot.id]?.version ?: 0) + 1, clock().toString())
            .also { tasks[snapshot.id] = it }
    }

    override suspend fun mutate(request: MutationRequest): ApiResponse<TaskEnvelope> {
        val plan = mutex.withLock {
            received += request
            if (plans.isEmpty()) FakePlan() else plans.removeFirst()
        }
        plan.beforeCommit?.await(request)
        when (plan.fault) {
            FakeFault.TIMEOUT_BEFORE -> throw SocketTimeoutException("Simulated timeout before commit")
            FakeFault.SERVER_ERROR -> return ApiResponse(503)
            else -> Unit
        }
        val response = mutex.withLock { apply(request, plan.fault == FakeFault.VALIDATION) }
        plan.afterCommit?.await(request)
        if (plan.fault == FakeFault.LOST_AFTER_COMMIT && response.status in 200..299) {
            throw SocketTimeoutException("Simulated lost response after commit")
        }
        return response
    }

    private fun apply(request: MutationRequest, rejectValidation: Boolean): ApiResponse<TaskEnvelope> {
        // Receipt lookup MUST precede version checks and business validation.
        receipts[request.mutationId]?.let {
            return if (it.request == request) it.response
            else ApiResponse(409, error = ApiError("MUTATION_ID_REUSED", request.taskId))
        }
        if (rejectValidation) return ApiResponse(422, error = ApiError("VALIDATION_ERROR", request.taskId,
            fieldErrors = mapOf("title" to "Rejected by test control")))
        val keyValid = try { UUID.fromString(request.mutationId).toString().equals(request.mutationId, ignoreCase = true) }
            catch (_: IllegalArgumentException) { false }
        val body = request.body
        val values = body?.snapshot()?.let { it.copy(title = it.title.trim()) }
        val valid = keyValid && when (request.operation) {
            MutationOperation.CREATE -> request.baseVersion == 0L && body?.baseVersion == 0L &&
                body.id == request.taskId && values?.isDeleted == false && values.isValid()
            MutationOperation.UPDATE -> request.baseVersion > 0 && body?.baseVersion == request.baseVersion &&
                body.id == request.taskId && values?.isDeleted == false && values.isValid()
            MutationOperation.DELETE -> request.baseVersion > 0 && body == null
        }
        if (!valid) return ApiResponse(422, error = ApiError("VALIDATION_ERROR", request.taskId,
            fieldErrors = mapOf("request" to "Invalid task request")))
        val current = tasks[request.taskId]
        if (request.operation != MutationOperation.CREATE && current == null) {
            return ApiResponse(404, error = ApiError("TASK_NOT_FOUND", request.taskId))
        }
        if ((request.operation == MutationOperation.CREATE && current != null) ||
            (current != null && request.baseVersion != current.version)) {
            return ApiResponse(409, error = ApiError("VERSION_CONFLICT", request.taskId, current))
        }
        val saved = ApiTask.from(
            if (request.operation == MutationOperation.DELETE) requireNotNull(current).snapshot().copy(isDeleted = true)
            else requireNotNull(values), (current?.version ?: 0) + 1, clock().toString())
        val response = ApiResponse(if (request.operation == MutationOperation.CREATE) 201 else 200, TaskEnvelope(saved))
        // Task + successful receipt become visible together under the same lock.
        tasks[request.taskId] = saved
        receipts[request.mutationId] = Receipt(request, response)
        commits++
        return response
    }

    override suspend fun getTasks(includeDeleted: Boolean): ApiResponse<TasksEnvelope> = mutex.withLock {
        ApiResponse(200, TasksEnvelope(tasks.values.filter { includeDeleted || !it.isDeleted }.toList()))
    }
    override suspend fun getTask(id: String): ApiResponse<TaskEnvelope> = mutex.withLock {
        tasks[id]?.let { ApiResponse(200, TaskEnvelope(it)) }
            ?: ApiResponse(404, error = ApiError("TASK_NOT_FOUND", id))
    }
}

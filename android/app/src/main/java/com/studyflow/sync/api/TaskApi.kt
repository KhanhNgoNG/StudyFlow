package com.studyflow.sync.api

import java.time.Instant
import java.time.LocalDate
import java.time.DateTimeException
import java.util.UUID

/** Business values only; Room/UI metadata never crosses this boundary. */
data class TaskSnapshot(
    val id: String, val title: String, val description: String, val subjectCode: String,
    val dueDate: String?, val priority: String, val completed: Boolean, val isDeleted: Boolean,
) {
    fun sameIntent(other: TaskSnapshot): Boolean = id == other.id &&
        ((isDeleted && other.isDeleted) || this == other)
    fun isValid(): Boolean = try {
        UUID.fromString(id).toString().equals(id, ignoreCase = true) && title == title.trim() && title.length in 1..120 &&
            description.length <= 2_000 && subjectCode in setOf("MOBILE", "DATABASE", "GENERAL", "OTHER") &&
            priority in setOf("LOW", "MEDIUM", "HIGH") &&
            (dueDate == null || LocalDate.parse(dueDate).toString() == dueDate)
    } catch (_: IllegalArgumentException) { false } catch (_: DateTimeException) { false }
}

/** Full POST/PUT JSON body from contract 1.0. */
data class TaskWriteBody(
    val id: String, val baseVersion: Long, val title: String, val description: String,
    val subjectCode: String, val dueDate: String?, val priority: String,
    val completed: Boolean, val isDeleted: Boolean,
) {
    fun snapshot() = TaskSnapshot(id, title, description, subjectCode, dueDate, priority, completed, isDeleted)
    companion object {
        fun from(task: TaskSnapshot, baseVersion: Long) = TaskWriteBody(task.id, baseVersion,
            task.title, task.description, task.subjectCode, task.dueDate, task.priority, task.completed, task.isDeleted)
    }
}

/** Complete server task; future adapters must reject missing required JSON fields. */
data class ApiTask(
    val id: String, val title: String, val description: String, val subjectCode: String,
    val dueDate: String?, val priority: String, val completed: Boolean, val isDeleted: Boolean,
    val version: Long, val serverUpdatedAt: String,
) {
    fun snapshot() = TaskSnapshot(id, title, description, subjectCode, dueDate, priority, completed, isDeleted)
    fun isValid(): Boolean = snapshot().isValid() && version > 0 && try {
        Instant.parse(serverUpdatedAt); serverUpdatedAt.endsWith("Z")
    } catch (_: DateTimeException) { false }
    companion object {
        fun from(task: TaskSnapshot, version: Long, updatedAt: String) = ApiTask(task.id, task.title,
            task.description, task.subjectCode, task.dueDate, task.priority, task.completed, task.isDeleted,
            version, updatedAt)
    }
}

enum class MutationOperation { CREATE, UPDATE, DELETE }
/** Exact retry identity. DELETE has a query parameter and no body. */
data class MutationRequest(
    val mutationId: String, val taskId: String, val operation: MutationOperation,
    val baseVersion: Long, val body: TaskWriteBody?,
) {
    val method: String get() = when (operation) {
        MutationOperation.CREATE -> "POST"
        MutationOperation.UPDATE -> "PUT"
        MutationOperation.DELETE -> "DELETE"
    }
    val path: String get() = "/api/v1/tasks" + when (operation) {
        MutationOperation.CREATE -> ""
        MutationOperation.UPDATE -> "/$taskId"
        MutationOperation.DELETE -> "/$taskId?baseVersion=$baseVersion"
    }
    val headers: Map<String, String> get() = buildMap {
        put("Idempotency-Key", mutationId)
        if (body != null) put("Content-Type", "application/json")
    }
}

data class TaskEnvelope(val task: ApiTask?)
data class TasksEnvelope(val tasks: List<ApiTask>?)
data class ApiError(
    val code: String, val taskId: String? = null, val currentTask: ApiTask? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
)
data class ApiResponse<T>(
    val status: Int, val body: T? = null, val error: ApiError? = null,
    /** Adapter-normalized Retry-After duration, if supplied by the server. */
    val retryAfterSeconds: Long? = null,
)

/** Main-safe suspend boundary; no networking or production fake binding in this phase. */
interface TaskApi {
    suspend fun mutate(request: MutationRequest): ApiResponse<TaskEnvelope>
    suspend fun getTasks(includeDeleted: Boolean = true): ApiResponse<TasksEnvelope>
    suspend fun getTask(id: String): ApiResponse<TaskEnvelope>
}

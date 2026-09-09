package pl.blizinski.todoiststore.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.todoiststore.internal.TodoistProject
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val TODOIST_API_BASE = "https://api.todoist.com/api/v1"
private const val PAGE_LIMIT = 200
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * The only place in this library that knows Todoist's REST-style `/api/v1` shapes and date
 * format — [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor] never
 * see either.
 *
 * **Uses the plain per-resource REST-style endpoints, not the batched Sync API `/sync`
 * commands** — see the design doc's Key Design Decisions. This means [getRecords] has no
 * incremental delta: `GET /api/v1/tasks?project_id=...` has no `updated_since`-style parameter
 * (confirmed against the reference doc's full parameter table), so [updatedMin] is accepted (to
 * satisfy the interface) but has no effect — every call is a full pull of that project's
 * current active tasks. A deliberate, documented v1 tradeoff, not an oversight.
 *
 * Todoist has a genuine, native cross-project move ([moveRecord]) — the first source library in
 * this app where that isn't the interface's throwing default.
 */
internal class TodoistNetworkSource(
    private val tokenProvider: AccessTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : NetworkSource<TodoistTask, TodoistProject> {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun request(method: String, url: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getToken()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
                "DELETE" -> requestBuilder.delete()
                else -> error("Unsupported method $method")
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TodoistApiException(response.code, "Todoist API $method $url failed: ${response.code} $responseBody")
                }
                responseBody
            }
        }

    private suspend inline fun <reified T> requestPage(baseUrl: String, params: Map<String, String>): List<T> {
        val serializer = TodoistPage.serializer(serializer<T>())
        val result = mutableListOf<T>()
        var cursor: String? = null
        do {
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            for ((key, value) in params) urlBuilder.addQueryParameter(key, value)
            urlBuilder.addQueryParameter("limit", PAGE_LIMIT.toString())
            cursor?.let { urlBuilder.addQueryParameter("cursor", it) }
            val page = json.decodeFromString(serializer, request("GET", urlBuilder.build().toString()))
            result += page.results
            cursor = page.nextCursor
        } while (cursor != null)
        return result
    }

    // -----------------------------------------------------------------------
    // Lists (Todoist projects) — real create/update/delete, unlike GitHub's read-only repos.
    // -----------------------------------------------------------------------

    override suspend fun getLists(): List<RemoteListRecord<TodoistProject>> =
        requestPage<TodoistProjectDto>("$TODOIST_API_BASE/projects", emptyMap())
            .map { RemoteListRecord(remoteId = it.id, content = TodoistProject(name = it.name)) }

    override suspend fun createList(content: TodoistProject): RemoteListRecord<TodoistProject> {
        val body = json.encodeToString(TodoistProjectCreateRequest.serializer(), TodoistProjectCreateRequest(name = content.name))
        val dto = json.decodeFromString(TodoistProjectDto.serializer(), request("POST", "$TODOIST_API_BASE/projects", body))
        return RemoteListRecord(remoteId = dto.id, content = TodoistProject(name = dto.name))
    }

    override suspend fun updateList(remoteListId: String, content: TodoistProject) {
        val body = json.encodeToString(TodoistProjectUpdateRequest.serializer(), TodoistProjectUpdateRequest(name = content.name))
        request("POST", "$TODOIST_API_BASE/projects/$remoteListId", body)
    }

    override suspend fun deleteList(remoteListId: String) {
        // A genuine delete, no archive-first step — that requirement is scoped to workspace
        // projects (Todoist API.html, "Delete a project"), out of scope for this v1's personal
        // projects. See the design doc's Background.
        request("DELETE", "$TODOIST_API_BASE/projects/$remoteListId")
    }

    // -----------------------------------------------------------------------
    // Tasks
    // -----------------------------------------------------------------------

    /** [updatedMin] is unused — see this class's top doc comment. */
    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<TodoistTask>> =
        requestPage<TodoistTaskDto>("$TODOIST_API_BASE/tasks", mapOf("project_id" to remoteListId))
            .map { it.toRemoteRecord() }

    override suspend fun createRecord(remoteListId: String, content: TodoistTask): RemoteRecord<TodoistTask> {
        val body = json.encodeToString(TodoistTaskCreateRequest.serializer(), content.toCreateRequest(remoteListId))
        val dto = json.decodeFromString(TodoistTaskDto.serializer(), request("POST", "$TODOIST_API_BASE/tasks", body))
        return dto.toRemoteRecord()
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: TodoistTask) {
        val body = json.encodeToString(JsonObject.serializer(), content.toUpdateRequestJson())
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId", body)
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) {
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId/close")
    }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) {
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId/reopen")
    }

    /** A genuine delete — unlike GitHub Issues, Todoist's REST API has a real delete endpoint,
     *  so no close-as-delete approximation is needed here. */
    override suspend fun deleteRecord(remoteListId: String, remoteId: String) {
        request("DELETE", "$TODOIST_API_BASE/tasks/$remoteId")
    }

    /**
     * Native cross-project move — [sourceRemoteListId]/[previousRemoteId] aren't needed by
     * Todoist's move endpoint (it only needs the destination), unlike a source that has to
     * reconstruct a move from separate create+delete calls.
     */
    override suspend fun moveRecord(sourceRemoteListId: String, remoteId: String, destRemoteListId: String, previousRemoteId: String?) {
        val body = json.encodeToString(TodoistTaskMoveRequest.serializer(), TodoistTaskMoveRequest(projectId = destRemoteListId))
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId/move", body)
    }
}

/** Thrown for any non-2xx Todoist API response; [httpStatus] drives [pl.blizinski.tasksync.SyncErrorClassifier]. */
internal class TodoistApiException(val httpStatus: Int, message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Mapping + date helpers. This is the only place in the library that touches Todoist's own
// JSON shape and date formats — everywhere above deals in epoch milliseconds.
// ---------------------------------------------------------------------------

/**
 * [updateRecord]'s POST body (Todoist's `/tasks/{id}` update, not a true PATCH, but same
 * omitted-vs-explicit-null risk this library already flags for [TodoistTaskUpdateRequest]'s
 * `due_date`/`due_datetime` fields). Verified against a live account (see
 * `Docs/2026-09-07-recurrence-write-path-verification.md` in the composeApp repo): sending
 * `due_string` alone (omitting `due_date`/`due_datetime` entirely) is sufficient — Todoist
 * computes the task's due date from the string itself, and including `due_date`/`due_datetime`
 * alongside it made no observable difference to the resulting `due` object. So whenever
 * [TodoistTask.recurrenceRule] is set, `due_string` is sent and `due_date`/`due_datetime` are
 * left out of the request entirely, rather than sent alongside it.
 *
 * Clearing a recurrence rule (a null [TodoistTask.recurrenceRule] on a task that previously had
 * one) was **not** part of what got live-verified — only setting one was. This explicitly
 * injects `"due_string": null` in that case anyway, by the same defensive reasoning as
 * `due_date`'s already-flagged (also unverified) explicit-null handling in
 * [TodoistTaskUpdateRequest]'s own doc comment: `encodeDefaults = false` would otherwise omit a
 * null `due_string` from the encoded body, and an omitted field is the one behavior most likely
 * to mean "leave unchanged" rather than "clear" on an endpoint shaped like this one. Flagged as
 * an assumption to verify, not a confirmed live-tested behavior.
 */
internal fun TodoistTask.toUpdateRequestJson(): JsonObject {
    val request = TodoistTaskUpdateRequest(
        content = title,
        description = notes,
        labels = labels,
        priority = priority,
        dueDate = if (recurrenceRule == null && !dueHasTime) dueDate?.toDateOnly() else null,
        dueDatetime = if (recurrenceRule == null && dueHasTime) dueDate?.toRfc3339Utc() else null,
        dueString = recurrenceRule,
    )
    val encoded = Json.encodeToJsonElement(TodoistTaskUpdateRequest.serializer(), request).jsonObject
    return if (recurrenceRule == null) JsonObject(encoded + ("due_string" to JsonNull)) else encoded
}

/**
 * [createRecord]'s POST body. Same `due_string`-alone-when-recurring rule as
 * [toUpdateRequestJson] — see that function's doc comment for what's live-verified (setting, on
 * the update path) versus assumed (the create endpoint behaving identically; see
 * [TodoistTaskCreateRequest]'s own doc comment). No explicit-null handling needed here — a
 * create request has no existing field value to clear, unlike an update.
 */
internal fun TodoistTask.toCreateRequest(remoteListId: String): TodoistTaskCreateRequest = TodoistTaskCreateRequest(
    content = title,
    description = notes,
    projectId = remoteListId,
    labels = labels,
    priority = priority,
    dueDate = if (recurrenceRule == null && !dueHasTime) dueDate?.toDateOnly() else null,
    dueDatetime = if (recurrenceRule == null && dueHasTime) dueDate?.toRfc3339Utc() else null,
    dueString = recurrenceRule,
)

internal fun TodoistTaskDto.toRemoteRecord(): RemoteRecord<TodoistTask> = RemoteRecord(
    remoteId = id,
    isCompleted = checked,
    isDeleted = false, // a hard-deleted task simply stops appearing in GET /api/v1/tasks; no tombstone signal to map here
    remoteUpdatedAt = null, // this endpoint's task object has no updated_at field usable as a delta cursor (see class doc comment) — updatedMin/remoteUpdatedAt play no role given the full-pull-every-cycle tradeoff
    content = TodoistTask(
        title = content,
        notes = description?.takeIf { it.isNotEmpty() },
        createdDate = addedAt?.parseRfc3339ToEpochMs(),
        completedDate = completedAt?.parseRfc3339ToEpochMs(),
        dueDate = due?.datetime?.parseRfc3339ToEpochMs() ?: due?.date?.parseDateOnlyToEpochMs(),
        dueHasTime = due?.datetime != null,
        priority = priority,
        labels = labels,
        isSubtask = parentId != null,
        recurrenceRule = due?.string?.takeIf { due.isRecurring },
    ),
)

/** Todoist's date-only fields: `YYYY-MM-DD`. */
internal fun Long.toDateOnly(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

internal fun String.parseDateOnlyToEpochMs(): Long? = try {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    sdf.parse(this)?.time
} catch (e: Exception) { null }

/** Todoist's full-datetime fields: RFC 3339 UTC, e.g. `2025-02-12T12:00:00Z`. */
internal fun Long.toRfc3339Utc(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

internal fun String.parseRfc3339ToEpochMs(): Long? = try {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    sdf.parse(this)?.time
} catch (e: Exception) { null }

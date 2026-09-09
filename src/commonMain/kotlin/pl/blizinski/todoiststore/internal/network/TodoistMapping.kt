package pl.blizinski.todoiststore.internal.network

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.tasksync.RemoteRecord

/**
 * The wire ⇄ [TodoistTask] mapping and Todoist's date formats. Shared by both
 * [TodoistNetworkSource] (androidMain, OkHttp) and `TodoistNetworkSourceWasm` (wasmJs, Ktor) —
 * only the HTTP transport differs between the two. Nothing here touches
 * [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor].
 */

/** Thrown for any non-2xx Todoist API response; [httpStatus] drives
 *  [pl.blizinski.tasksync.SyncErrorClassifier]. */
class TodoistApiException(val httpStatus: Int, message: String) : Exception(message)

/**
 * [TodoistNetworkSource.updateRecord]'s POST body. When [TodoistTask.recurrenceRule] is set,
 * `due_string` is sent and `due_date`/`due_datetime` are omitted (live-verified: Todoist derives
 * the due date from the string, and sending the explicit fields alongside made no difference).
 * A null [TodoistTask.recurrenceRule] injects an explicit `"due_string": null` to clear a
 * previously-set rule (`encodeDefaults = false` would otherwise omit it) — flagged as an
 * assumption to verify, not live-tested.
 */
@OptIn(ExperimentalTime::class)
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

/** [TodoistNetworkSource.createRecord]'s POST body — same `due_string`-alone-when-recurring rule
 *  as [toUpdateRequestJson]; no explicit-null handling needed (nothing to clear on a create). */
@OptIn(ExperimentalTime::class)
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

@OptIn(ExperimentalTime::class)
internal fun TodoistTaskDto.toRemoteRecord(): RemoteRecord<TodoistTask> = RemoteRecord(
    remoteId = id,
    isCompleted = checked,
    isDeleted = false, // a hard-deleted task simply stops appearing in GET /api/v1/tasks; no tombstone to map
    remoteUpdatedAt = null, // this endpoint's task object has no updated_at usable as a delta cursor
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

// --- Date helpers. Todoist: date-only `YYYY-MM-DD`, full datetime RFC 3339 UTC (`...T..:..:..Z`).
// kotlin.time.Instant parses/formats RFC 3339; date-only is the leading 10 chars of the same. ---

@OptIn(ExperimentalTime::class)
internal fun Long.toDateOnly(): String = Instant.fromEpochMilliseconds(this).toString().take(10)

@OptIn(ExperimentalTime::class)
internal fun String.parseDateOnlyToEpochMs(): Long? =
    runCatching { Instant.parse("${this}T00:00:00Z").toEpochMilliseconds() }.getOrNull()

@OptIn(ExperimentalTime::class)
internal fun Long.toRfc3339Utc(): String =
    // Truncate to whole seconds — Instant.toString() then emits `...:00Z` (no fractional part),
    // which is the shape Todoist's `due_datetime` expects.
    Instant.fromEpochMilliseconds((this / 1000) * 1000).toString()

@OptIn(ExperimentalTime::class)
internal fun String.parseRfc3339ToEpochMs(): Long? =
    runCatching { Instant.parse(this).toEpochMilliseconds() }.getOrNull()

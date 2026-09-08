package pl.blizinski.todoiststore.internal.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for Todoist's REST-style `/api/v1/tasks`/`/api/v1/projects` surface — the only
 * place in this library that knows Todoist's own JSON shape. [pl.blizinski.tasksync.SyncEngine]/
 * [pl.blizinski.tasksync.PendingOpsProcessor] never see these.
 *
 * **`priority` field-meaning discrepancy, flagged rather than silently picked**: the reference
 * material for this design (`Todoist API.html`) documents the Sync API's `item_add`/
 * `item_update` commands as "1-4, 4 = very urgent, 1 = natural" (with an explicit note that
 * client-visible "p1" maps to API value 4), but documents *this* REST-style surface's
 * `POST/GET /api/v1/tasks` as "1-4, where 1 is highest" — the opposite convention, in the same
 * reference document. This library only ever calls the REST-style surface, so [TodoistTaskDto
 * .priority] is passed through exactly as that surface returns/accepts it (no inversion
 * applied) — but this is worth verifying against a live account before shipping, since a wrong
 * guess here would silently invert every task's displayed priority. See the design doc's
 * Critical Test Case 2.
 */
@Serializable
internal data class TodoistTaskDto(
    val id: String = "",
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val content: String = "",
    val description: String? = null,
    val labels: List<String> = emptyList(),
    val priority: Int? = null,
    val due: TodoistDueDto? = null,
    val checked: Boolean = false,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
internal data class TodoistDueDto(
    /** Date-only, `YYYY-MM-DD`. */
    val date: String? = null,
    /** Full RFC 3339 datetime — present only when the due date carries a time component. */
    val datetime: String? = null,
    @SerialName("is_recurring") val isRecurring: Boolean = false,
    val string: String? = null,
    val timezone: String? = null,
)

/** Request body for `POST /api/v1/tasks`. */
@Serializable
internal data class TodoistTaskCreateRequest(
    val content: String,
    val description: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val labels: List<String> = emptyList(),
    val priority: Int? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_datetime") val dueDatetime: String? = null,
)

/**
 * Request body for `POST /api/v1/tasks/{task_id}`. Every field is sent (not omitted) because
 * this library's pending ops always carry the *full* desired content, not a per-field diff (see
 * `task-sync-kotlin`'s design) — a null [dueDate]/[dueDatetime] is sent as an explicit JSON
 * `null`, intended to clear the due date. Todoist's docs confirm null-to-clear for several other
 * fields (`assignee_id`, `duration`, `deadline_date`) but don't explicitly confirm it for
 * `due_date`/`due_datetime` — flagged as an assumption to verify (see Critical Test Case 2).
 *
 * [dueString] (Todoist's `due_string`, a natural-language field the server itself parses, e.g.
 * "every day") **is** wired into [TodoistNetworkSource.updateRecord] (via
 * [TodoistTask.toUpdateRequestJson]) — verified against a live account (see
 * `Docs/2026-09-07-recurrence-write-path-verification.md` in the composeApp repo): sending
 * `due_string` alone, with [dueDate]/[dueDatetime] omitted, is sufficient — Todoist computes the
 * due date from the string itself, and including [dueDate]/[dueDatetime] alongside it made no
 * observable difference to the resulting `due` object on the task. Clearing an existing
 * recurrence rule (an explicit `"due_string": null`) was **not** independently live-verified —
 * see [TodoistTask.toUpdateRequestJson]'s own doc comment for why it's handled defensively
 * anyway.
 */
@Serializable
internal data class TodoistTaskUpdateRequest(
    val content: String,
    val description: String? = null,
    val labels: List<String> = emptyList(),
    val priority: Int? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_datetime") val dueDatetime: String? = null,
    @SerialName("due_string") val dueString: String? = null,
)

/** Request body for `POST /api/v1/tasks/{task_id}/move`. */
@Serializable
internal data class TodoistTaskMoveRequest(
    @SerialName("project_id") val projectId: String,
)

@Serializable
internal data class TodoistProjectDto(
    val id: String = "",
    val name: String = "",
)

@Serializable
internal data class TodoistProjectCreateRequest(
    val name: String,
)

@Serializable
internal data class TodoistProjectUpdateRequest(
    val name: String,
)

/** Cursor-paginated envelope shared by `GET /api/v1/tasks` and `GET /api/v1/projects`. */
@Serializable
internal data class TodoistPage<T>(
    val results: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

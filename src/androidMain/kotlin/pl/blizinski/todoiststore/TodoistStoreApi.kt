package pl.blizinski.todoiststore

import kotlinx.coroutines.flow.Flow
import pl.blizinski.todoiststore.models.SyncStatus
import pl.blizinski.todoiststore.models.Task
import pl.blizinski.todoiststore.models.TaskList

/**
 * Public contract for the Todoist local store. Implemented by [TodoistStore]; can be faked in
 * tests without Android framework deps.
 *
 * Unlike GitHub Issues (read-only repos), Todoist projects behave like Google Tasks/Microsoft
 * To Do lists — real create/rename/delete — so this interface has a full [createList]/
 * [updateList]/[deleteList] surface. Todoist also has a native cross-project move
 * ([moveTask] returns the task's own id, unchanged, since [pl.blizinski.tasksync.NetworkSource
 * .moveRecord] preserves identity) — the first source library in this app where a move doesn't
 * need a delete+recreate fallback.
 */
interface TodoistStoreApi {

    // --- Read ---

    fun taskLists(): Flow<List<TaskList>>

    /** [listLocalId] is the [TaskList.id] returned by [taskLists]. */
    fun tasks(listLocalId: String): Flow<List<Task>>

    fun syncStatus(): Flow<SyncStatus>

    // --- Write (optimistic — applied locally, synced in background) ---

    /** Creates a project and returns its stable localId. */
    suspend fun createList(title: String): String

    /** Renames a project. */
    suspend fun updateList(localId: String, title: String)

    /**
     * Deletes a project and all its tasks. Locally-created tasks (not yet synced) are removed
     * immediately; synced tasks are cleaned up after the server confirms.
     */
    suspend fun deleteList(localId: String)

    /**
     * Creates a task and returns its stable [Task.id] localId. [recurrenceRule] is Todoist's own
     * `due.string` (e.g. "every day"), sent as `due_string` in the same create request — assumed
     * (not independently live-verified; see `TodoistTaskCreateRequest`'s own doc comment) to
     * behave the same way as the already-verified update path: when set, it takes over the due
     * date entirely, so [dueDate]/[dueHasTime] are ignored on the wire whenever [recurrenceRule]
     * is non-null.
     */
    suspend fun createTask(
        listLocalId: String,
        title: String,
        notes: String? = null,
        dueDate: Long? = null,
        dueHasTime: Boolean = false,
        priority: Int? = null,
        labels: List<String> = emptyList(),
        recurrenceRule: String? = null,
    ): String

    /**
     * Updates title, notes, due date, priority, labels, and recurrence rule. Pass null/empty to
     * clear a field. [recurrenceRule] is Todoist's own natural-language `due.string` (e.g.
     * "every day"), sent verbatim as `due_string` — when set, it takes over the due date
     * entirely (verified against a live account: sending `due_date`/`due_datetime` alongside it
     * has no observable effect on the resulting due date), so [dueDate]/[dueHasTime] are ignored
     * on the wire whenever [recurrenceRule] is non-null.
     */
    suspend fun updateTask(
        localId: String,
        title: String,
        notes: String?,
        dueDate: Long? = null,
        dueHasTime: Boolean = false,
        priority: Int? = null,
        labels: List<String> = emptyList(),
        recurrenceRule: String? = null,
    )

    suspend fun completeTask(localId: String)

    suspend fun uncompleteTask(localId: String)

    /** A genuine delete (`DELETE /api/v1/tasks/{id}`) — unlike GitHub Issues, Todoist has no
     *  close-as-delete approximation to make here. */
    suspend fun deleteTask(localId: String)

    /**
     * Moves a task to [destListLocalId] in place, preserving its id — Todoist's native
     * `POST /api/v1/tasks/{id}/move` (see [pl.blizinski.tasksync.NetworkSource.moveRecord]).
     */
    suspend fun moveTask(localId: String, destListLocalId: String)

    // --- Lifecycle ---

    /** Runs a full sync cycle synchronously (flush pending ops, then pull). */
    suspend fun forceSync()

    /**
     * Like [forceSync], but pulls every list from scratch instead of using each list's stored
     * incremental-sync cursor. Todoist's `GET /api/v1/tasks` has no delta parameter at all (see
     * the design doc's Key Design Decisions), so this differs from [forceSync] only in resetting
     * each list's `lastSyncedAt` bookkeeping — every regular sync is already a full pull per
     * project.
     */
    suspend fun fullSync()

    /** Cancels background work and closes the database. */
    fun close()
}

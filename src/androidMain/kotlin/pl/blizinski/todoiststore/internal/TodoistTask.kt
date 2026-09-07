package pl.blizinski.todoiststore.internal

import kotlinx.serialization.Serializable

/**
 * Opaque content type for [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor]
 * — everything about a Todoist task except the fields promoted into the shared sync envelope
 * (localId, remoteId, listLocalId, isCompleted, isDeleted, lastSyncedAt, remoteUpdatedAt).
 */
@Serializable
internal data class TodoistTask(
    val title: String,
    val notes: String? = null,
    val createdDate: Long? = null,
    val completedDate: Long? = null,
    val dueDate: Long? = null,
    val dueHasTime: Boolean = false,
    val priority: Int? = null,
    val labels: List<String> = emptyList(),
    val isSubtask: Boolean = false,
    /** Read-only for now — see [pl.blizinski.todoiststore.TodoistStoreApi.updateTask]. */
    val recurrenceRule: String? = null,
)

@Serializable
internal data class TodoistProject(
    val name: String,
)

package pl.blizinski.todoiststore.models

data class Task(
    val id: TaskId,
    val listId: String,
    val title: String,
    val notes: String? = null,
    val isCompleted: Boolean = false,
    val createdDate: Long? = null,
    /** Due date as epoch milliseconds. Null means no due date. */
    val dueDate: Long? = null,
    /** True when [dueDate] carries a meaningful time component (Todoist's `due.datetime`),
     *  not just a date (`due.date`). */
    val dueHasTime: Boolean = false,
    val completedDate: Long? = null,
    /** Todoist's native 1-4 scale, 4 = most urgent. Meaning is source-specific, matching
     *  [pl.blizinski.taskcompass.model.Reminder.priority]'s own doc comment — no normalization
     *  is done here. */
    val priority: Int? = null,
    /** Todoist's native, first-class label names (personal or shared) — a flat array, no
     *  id/name lookup required. */
    val labels: List<String> = emptyList(),
    /** True when this task is a subtask of another task (Todoist's `parent_id`), flattened the
     *  same way Google Tasks' `parentId` is. */
    val isSubtask: Boolean = false,
    /** Todoist's own `due.string`, verbatim (e.g. "every day", "every! last day") — null unless
     *  `due.is_recurring` is true. Read-only for now — see [pl.blizinski.todoiststore.TodoistStoreApi.updateTask]. */
    val recurrenceRule: String? = null,
)

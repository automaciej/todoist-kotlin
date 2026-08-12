package pl.blizinski.todoiststore.models

/** One Todoist project being synced as a task list. */
data class TaskList(
    val id: String,
    val title: String,
)

package pl.blizinski.todoiststore

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class TodoistStoreConfig(
    val minPollInterval: Duration = 1.minutes,
    val maxPollInterval: Duration = 30.minutes,
    val dbName: String = "todoist_store",
    val maxRecentErrors: Int = 50,
)

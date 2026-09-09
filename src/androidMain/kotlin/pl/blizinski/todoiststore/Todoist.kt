package pl.blizinski.todoiststore

import pl.blizinski.tasksync.model.RecurrenceStyle
import pl.blizinski.tasksync.model.StoreCapabilities

/**
 * Static facts about the Todoist source, available before any account is connected.
 * [todoistStore] builds a [pl.blizinski.tasksync.store.TaskStore] for a connected account.
 */
object Todoist {

    /**
     * Todoist projects behave like real lists (create/rename/delete), tasks carry a real due
     * time, native priority (1–4), first-class labels, subtasks, and a native cross-project
     * move. Recurrence is a server-parsed natural-language string — [RecurrenceStyle.FUZZY].
     */
    val capabilities = StoreCapabilities(
        supportsDueTime = true,
        supportsPriority = true,
        supportsLabels = true,
        supportsManualOrdering = false,
        supportsSubtasks = true,
        supportsMultipleLists = true,
        supportsListCreation = true,
        supportsManualDelete = true,
        supportsNativeMove = true,
        recurrenceStyle = RecurrenceStyle.FUZZY,
    )
}

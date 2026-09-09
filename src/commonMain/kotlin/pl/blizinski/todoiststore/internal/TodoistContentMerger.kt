package pl.blizinski.todoiststore.internal

import pl.blizinski.tasksync.store.ContentMerger
import pl.blizinski.tasksync.store.contentMerger

/**
 * Three-way merge for [TodoistTask] content, passed to `buildAndroidTaskStore` /
 * `buildWasmTaskStore` so a title edited on one device and a due date edited on another both
 * survive instead of one overwriting the other.
 *
 * Only the fields this app can edit locally are picked: [TodoistTask.title], [TodoistTask.notes],
 * the [TodoistTask.dueDate] / [TodoistTask.dueHasTime] pair and [TodoistTask.recurrenceRule] (via
 * `applyDraft`), plus [TodoistTask.completedDate] (via `applyCompletion`). `dueDate` and
 * `dueHasTime` move together so a merge can't pair one side's date with the other side's
 * time-of-day flag. The result starts from the just-pulled `remote`, so `priority`, `labels`,
 * `isSubtask` and `createdDate` are carried through unchanged.
 */
internal val TodoistContentMerger: ContentMerger<TodoistTask> =
    contentMerger(emptyBase = TodoistTask(title = "")) {
        val due = sideForGroup({ it.dueDate }, { it.dueHasTime })
        remote.copy(
            title = pick { it.title },
            notes = pick { it.notes },
            dueDate = due.dueDate,
            dueHasTime = due.dueHasTime,
            recurrenceRule = pick { it.recurrenceRule },
            completedDate = pick { it.completedDate },
        )
    }

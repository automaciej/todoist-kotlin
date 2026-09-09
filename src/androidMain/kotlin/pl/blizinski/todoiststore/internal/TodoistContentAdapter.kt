package pl.blizinski.todoiststore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.RecurrenceRule
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList
import pl.blizinski.tasksync.model.TaskRef
import pl.blizinski.tasksync.store.ContentAdapter

/**
 * Maps between Todoist's opaque content types and the shared [Task]/[TaskList]. Todoist's
 * recurrence is a natural-language string ([RecurrenceRule.TextRule] — [RecurrenceStyle.FUZZY]);
 * anything but a `TextRule` in a [TaskDraft] is ignored, since the app only ever builds the
 * variant matching Todoist's declared style.
 */
internal object TodoistContentAdapter : ContentAdapter<TodoistTask, TodoistProject> {

    override fun toTask(record: SyncedRecord<TodoistTask>) = Task(
        id = TaskRef(localId = record.localId, remoteId = record.remoteId),
        listId = record.listLocalId,
        title = record.content.title,
        notes = record.content.notes,
        isCompleted = record.isCompleted,
        createdDate = record.content.createdDate,
        dueDate = record.content.dueDate,
        dueHasTime = record.content.dueHasTime,
        completedDate = record.content.completedDate,
        priority = record.content.priority,
        labels = record.content.labels,
        isSubtask = record.content.isSubtask,
        recurrenceRule = record.content.recurrenceRule?.let { RecurrenceRule.TextRule(it) },
    )

    override fun toTaskList(list: SyncedListRecord<TodoistProject>) = TaskList(
        id = list.localId,
        title = list.content.name,
    )

    override fun newContent(draft: TaskDraft, now: Long) = TodoistTask(
        title = draft.title,
        notes = draft.notes,
        createdDate = now,
        dueDate = draft.dueDate,
        dueHasTime = draft.dueHasTime,
        priority = draft.priority,
        labels = draft.labels,
        recurrenceRule = (draft.recurrenceRule as? RecurrenceRule.TextRule)?.text,
    )

    override fun applyDraft(existing: TodoistTask, draft: TaskDraft) = existing.copy(
        title = draft.title,
        notes = draft.notes,
        dueDate = draft.dueDate,
        dueHasTime = draft.dueHasTime,
        priority = draft.priority,
        labels = draft.labels,
        recurrenceRule = (draft.recurrenceRule as? RecurrenceRule.TextRule)?.text,
    )

    override fun applyCompletion(existing: TodoistTask, completed: Boolean, at: Long?) =
        existing.copy(completedDate = at)

    override fun newListContent(title: String) = TodoistProject(name = title)

    override fun applyListTitle(existing: TodoistProject, title: String) = TodoistProject(name = title)
}

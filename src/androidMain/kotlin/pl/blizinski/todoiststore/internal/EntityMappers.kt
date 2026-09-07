package pl.blizinski.todoiststore.internal

import pl.blizinski.todoiststore.models.Task
import pl.blizinski.todoiststore.models.TaskId
import pl.blizinski.todoiststore.models.TaskList
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord

internal fun SyncedRecord<TodoistTask>.toTask(): Task = Task(
    id = TaskId(localId = localId, remoteId = remoteId),
    listId = listLocalId,
    title = content.title,
    notes = content.notes,
    isCompleted = isCompleted,
    createdDate = content.createdDate,
    dueDate = content.dueDate,
    dueHasTime = content.dueHasTime,
    completedDate = content.completedDate,
    priority = content.priority,
    labels = content.labels,
    isSubtask = content.isSubtask,
    recurrenceRule = content.recurrenceRule,
)

internal fun SyncedListRecord<TodoistProject>.toTaskList(): TaskList = TaskList(
    id = localId,
    title = content.name,
)

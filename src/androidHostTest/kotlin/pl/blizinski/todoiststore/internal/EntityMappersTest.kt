package pl.blizinski.todoiststore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityMappersTest {

    @Test
    fun toTaskMapsEnvelopeAndContentFields() {
        val record = SyncedRecord(
            localId = "local-1",
            remoteId = "remote-1",
            listLocalId = "list-1",
            content = TodoistTask(title = "Buy milk", notes = "Whole milk", labels = listOf("errands"), priority = 3),
            isCompleted = true,
        )

        val task = record.toTask()

        assertEquals("local-1", task.id.localId)
        assertEquals("remote-1", task.id.remoteId)
        assertEquals("list-1", task.listId)
        assertEquals("Buy milk", task.title)
        assertEquals("Whole milk", task.notes)
        assertEquals(true, task.isCompleted)
        assertEquals(listOf("errands"), task.labels)
        assertEquals(3, task.priority)
    }

    @Test
    fun toTaskMapsDueDateAndDueHasTime() {
        val record = SyncedRecord(
            localId = "l", remoteId = null, listLocalId = "list",
            content = TodoistTask(title = "x", dueDate = 1772891130000L, dueHasTime = true),
        )
        val task = record.toTask()
        assertEquals(1772891130000L, task.dueDate)
        assertTrue(task.dueHasTime)
    }

    @Test
    fun toTaskLeavesDueDateAndPriorityNullWhenUnset() {
        // Todoist maps closely to Reminder — but a task genuinely without a due date/priority
        // must still come through as null/false, not a fabricated default.
        val record = SyncedRecord(localId = "l", remoteId = null, listLocalId = "list", content = TodoistTask(title = "x"))
        val task = record.toTask()
        assertNull(task.dueDate)
        assertFalse(task.dueHasTime)
        assertNull(task.priority)
    }

    @Test
    fun toTaskMapsIsSubtask() {
        val record = SyncedRecord(localId = "l", remoteId = null, listLocalId = "list", content = TodoistTask(title = "x", isSubtask = true))
        assertTrue(record.toTask().isSubtask)
    }

    @Test
    fun toTaskListMapsProjectName() {
        val record = SyncedListRecord(localId = "local-1", remoteId = "remote-1", content = TodoistProject(name = "Shopping"))
        val list = record.toTaskList()
        assertEquals("local-1", list.id)
        assertEquals("Shopping", list.title)
    }
}

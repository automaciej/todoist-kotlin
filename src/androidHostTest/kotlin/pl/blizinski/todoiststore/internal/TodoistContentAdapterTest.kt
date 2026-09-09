package pl.blizinski.todoiststore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.RecurrenceRule
import pl.blizinski.tasksync.model.TaskDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoistContentAdapterTest {

    private val adapter = TodoistContentAdapter

    @Test
    fun toTask_mapsEnvelopeAndAllContentFields() {
        val record = SyncedRecord(
            localId = "local-1",
            remoteId = "remote-1",
            listLocalId = "list-1",
            content = TodoistTask(
                title = "Buy milk", notes = "Whole milk", createdDate = 10L,
                dueDate = 1_772_891_130_000L, dueHasTime = true, priority = 3,
                labels = listOf("errands"), isSubtask = true, recurrenceRule = "every day",
            ),
            isCompleted = true,
        )

        val task = adapter.toTask(record)

        assertEquals("local-1", task.id.localId)
        assertEquals("remote-1", task.id.remoteId)
        assertEquals("list-1", task.listId)
        assertEquals("Buy milk", task.title)
        assertEquals("Whole milk", task.notes)
        assertTrue(task.isCompleted)
        assertEquals(1_772_891_130_000L, task.dueDate)
        assertTrue(task.dueHasTime)
        assertEquals(3, task.priority)
        assertEquals(listOf("errands"), task.labels)
        assertTrue(task.isSubtask)
        assertEquals(RecurrenceRule.TextRule("every day"), task.recurrenceRule)
    }

    @Test
    fun toTask_nullRecurrence_mapsToNull() {
        val record = SyncedRecord(localId = "l", remoteId = null, listLocalId = "list", content = TodoistTask(title = "x"))
        assertNull(adapter.toTask(record).recurrenceRule)
    }

    @Test
    fun toTaskList_usesProjectName() {
        val record = SyncedListRecord(localId = "local-1", remoteId = "r", content = TodoistProject(name = "Groceries"))
        assertEquals("Groceries", adapter.toTaskList(record).title)
        assertEquals("local-1", adapter.toTaskList(record).id)
    }

    @Test
    fun newContent_carriesDraftFields_andCreatedDate() {
        val content = adapter.newContent(
            TaskDraft(
                title = "t", notes = "n", dueDate = 5L, dueHasTime = true, priority = 2,
                labels = listOf("a"), recurrenceRule = RecurrenceRule.TextRule("every week"),
            ),
            now = 99L,
        )
        assertEquals("t", content.title)
        assertEquals(99L, content.createdDate)
        assertEquals(5L, content.dueDate)
        assertTrue(content.dueHasTime)
        assertEquals(2, content.priority)
        assertEquals(listOf("a"), content.labels)
        assertEquals("every week", content.recurrenceRule)
    }

    @Test
    fun applyDraft_updatesFields_keepsIsSubtask() {
        val existing = TodoistTask(title = "old", createdDate = 1L, isSubtask = true)
        val updated = adapter.applyDraft(existing, TaskDraft(title = "new", priority = 4))
        assertEquals("new", updated.title)
        assertEquals(4, updated.priority)
        assertTrue(updated.isSubtask)
        assertEquals(1L, updated.createdDate)
    }

    @Test
    fun applyDraft_nonTextRecurrence_isIgnored() {
        val existing = TodoistTask(title = "t", recurrenceRule = "every day")
        val updated = adapter.applyDraft(
            existing,
            TaskDraft(title = "t", recurrenceRule = RecurrenceRule.StructuredRule(
                frequency = pl.blizinski.tasksync.model.RecurrenceFrequency.DAILY, interval = 1,
            )),
        )
        assertNull(updated.recurrenceRule)
    }

    @Test
    fun listContent_roundTrips() {
        assertEquals("P", adapter.newListContent("P").name)
        assertEquals("Q", adapter.applyListTitle(TodoistProject("P"), "Q").name)
    }
}

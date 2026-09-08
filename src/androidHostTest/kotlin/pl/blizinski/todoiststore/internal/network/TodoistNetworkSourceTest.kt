package pl.blizinski.todoiststore.internal.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import pl.blizinski.todoiststore.internal.TodoistTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoistNetworkSourceTest {

    // -----------------------------------------------------------------------
    // Date-only (yyyy-MM-dd) round trip — Todoist's due.date field.
    // -----------------------------------------------------------------------

    @Test
    fun dateOnlyRoundTrips() {
        // 2026-03-07 (UTC midnight)
        val epochMs = 1772841600000L
        assertEquals(epochMs, epochMs.toDateOnly().parseDateOnlyToEpochMs())
    }

    @Test
    fun dateOnlyMalformedParsesToNull() {
        assertNull("not-a-date".parseDateOnlyToEpochMs())
    }

    // -----------------------------------------------------------------------
    // Full RFC 3339 (due.datetime) round trip.
    // -----------------------------------------------------------------------

    @Test
    fun rfc3339RoundTrips() {
        // 2026-03-07T13:45:30Z
        val epochMs = 1772891130000L
        assertEquals(epochMs, epochMs.toRfc3339Utc().parseRfc3339ToEpochMs())
    }

    @Test
    fun rfc3339MalformedParsesToNull() {
        assertNull("not-a-date".parseRfc3339ToEpochMs())
    }

    // -----------------------------------------------------------------------
    // TodoistTaskDto <-> RemoteRecord<TodoistTask>
    // -----------------------------------------------------------------------

    @Test
    fun toRemoteRecordMapsCheckedStateAndCompletedDate() {
        val completedAt = 1772891130000L
        val dto = TodoistTaskDto(
            id = "6X7rM8997g3RQmvh",
            content = "Buy milk",
            description = "Whole milk, two liters",
            checked = true,
            labels = listOf("errands"),
            priority = 3,
            completedAt = completedAt.toRfc3339Utc(),
        )

        val record = dto.toRemoteRecord()

        assertEquals("6X7rM8997g3RQmvh", record.remoteId)
        assertTrue(record.isCompleted)
        assertFalse(record.isDeleted) // absence from GET /api/v1/tasks is the only delete signal; see class doc comment
        assertEquals("Buy milk", record.content.title)
        assertEquals("Whole milk, two liters", record.content.notes)
        assertEquals(listOf("errands"), record.content.labels)
        assertEquals(3, record.content.priority)
        assertEquals(completedAt, record.content.completedDate)
    }

    @Test
    fun toRemoteRecordMapsDateOnlyDueWithoutTime() {
        val dto = TodoistTaskDto(id = "1", content = "x", due = TodoistDueDto(date = "2026-03-07"))
        val record = dto.toRemoteRecord()
        assertEquals(1772841600000L, record.content.dueDate)
        assertFalse(record.content.dueHasTime)
    }

    @Test
    fun toRemoteRecordMapsDatetimeDueWithTime() {
        val dto = TodoistTaskDto(id = "1", content = "x", due = TodoistDueDto(date = "2026-03-07", datetime = "2026-03-07T13:45:30Z"))
        val record = dto.toRemoteRecord()
        assertEquals(1772891130000L, record.content.dueDate)
        assertTrue(record.content.dueHasTime)
    }

    @Test
    fun toRemoteRecordLeavesDueDateNullWhenNoDueSet() {
        val dto = TodoistTaskDto(id = "1", content = "x", due = null)
        val record = dto.toRemoteRecord()
        assertNull(record.content.dueDate)
        assertFalse(record.content.dueHasTime)
    }

    @Test
    fun toRemoteRecordMapsRecurringDueStringToRecurrenceRule() {
        val dto = TodoistTaskDto(id = "1", content = "x", due = TodoistDueDto(date = "2026-03-07", isRecurring = true, string = "every day"))
        assertEquals("every day", dto.toRemoteRecord().content.recurrenceRule)
    }

    @Test
    fun toRemoteRecordLeavesRecurrenceRuleNullWhenDueStringPresentButNotRecurring() {
        // Todoist's `due.string` is also populated for a plain, non-recurring due date
        // (e.g. "tomorrow") — only `is_recurring = true` means this is actually a recurrence
        // rule, not just a human-readable label for a one-off date.
        val dto = TodoistTaskDto(id = "1", content = "x", due = TodoistDueDto(date = "2026-03-07", isRecurring = false, string = "tomorrow"))
        assertNull(dto.toRemoteRecord().content.recurrenceRule)
    }

    @Test
    fun toRemoteRecordLeavesRecurrenceRuleNullWhenNoDueSet() {
        val dto = TodoistTaskDto(id = "1", content = "x", due = null)
        assertNull(dto.toRemoteRecord().content.recurrenceRule)
    }

    // -----------------------------------------------------------------------
    // Recurrence write path — verified against a live account, see
    // Docs/2026-09-07-recurrence-write-path-verification.md in the composeApp repo.
    // -----------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun updateRequestSendsDueStringAloneWhenRecurring() {
        val task = TodoistTask(title = "Water plants", dueDate = 1772841600000L, recurrenceRule = "every day")
        val encoded = json.encodeToString(JsonObject.serializer(), task.toUpdateRequestJson())
        assertTrue(encoded.contains(""""due_string":"every day""""), "expected due_string in body, got: $encoded")
        assertFalse(encoded.contains(""""due_date""""), "due_date must be omitted when a recurrence rule is set, got: $encoded")
        assertFalse(encoded.contains(""""due_datetime""""), "due_datetime must be omitted when a recurrence rule is set, got: $encoded")
    }

    @Test
    fun updateRequestKeepsDueDateWhenNotRecurring() {
        val task = TodoistTask(title = "Water plants", dueDate = 1772841600000L, dueHasTime = false, recurrenceRule = null)
        val encoded = json.encodeToString(JsonObject.serializer(), task.toUpdateRequestJson())
        assertTrue(encoded.contains(""""due_date":"${1772841600000L.toDateOnly()}""""), "expected due_date in body, got: $encoded")
    }

    /** See [TodoistTask.toUpdateRequestJson]'s own doc comment: this defensive explicit-null
     *  injection was not itself independently live-verified, unlike the due_string-alone
     *  behavior above. */
    @Test
    fun updateRequestExplicitlyClearsDueStringWhenRecurrenceRemoved() {
        val task = TodoistTask(title = "Water plants", dueDate = 1772841600000L, recurrenceRule = null)
        val encoded = json.encodeToString(JsonObject.serializer(), task.toUpdateRequestJson())
        assertTrue(encoded.contains(""""due_string":null"""), "clearing a recurrence rule must send an explicit null, got: $encoded")
    }

    @Test
    fun toRemoteRecordTreatsEmptyDescriptionAsNullNotes() {
        val dto = TodoistTaskDto(id = "1", content = "x", description = "")
        assertNull(dto.toRemoteRecord().content.notes)
    }

    @Test
    fun toRemoteRecordMapsParentIdPresenceToIsSubtask() {
        val withParent = TodoistTaskDto(id = "1", content = "x", parentId = "parent-1")
        val withoutParent = TodoistTaskDto(id = "2", content = "y", parentId = null)
        assertTrue(withParent.toRemoteRecord().content.isSubtask)
        assertFalse(withoutParent.toRemoteRecord().content.isSubtask)
    }

    /**
     * Pins the priority value through unchanged (no inversion) — see TodoistApiModels.kt's doc
     * comment on the Sync-API-vs-REST-API priority-meaning discrepancy in the reference
     * material. This test only proves the code doesn't silently invert the value; it does not
     * resolve which numeric convention is actually correct against a live account (Critical
     * Test Case 2, unresolved as of this test).
     */
    @Test
    fun toRemoteRecordPassesPriorityThroughUnchanged() {
        val dto = TodoistTaskDto(id = "1", content = "x", priority = 4)
        assertEquals(4, dto.toRemoteRecord().content.priority)
    }
}

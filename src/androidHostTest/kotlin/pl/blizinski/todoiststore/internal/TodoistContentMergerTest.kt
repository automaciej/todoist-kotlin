package pl.blizinski.todoiststore.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class TodoistContentMergerTest {

    private val merge = TodoistContentMerger

    private val base = TodoistTask(
        title = "Base title",
        notes = "base notes",
        createdDate = 500L,
        dueDate = 1_000L,
        dueHasTime = false,
        priority = 2,
        labels = listOf("home"),
    )

    @Test
    fun disjointFieldEdits_bothSurvive() {
        val local = base.copy(title = "Local title")
        val remote = base.copy(notes = "remote notes")

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals("Local title", merged.title)
        assertEquals("remote notes", merged.notes)
    }

    @Test
    fun sameFieldConflict_preferLocalDecides() {
        val local = base.copy(title = "Local")
        val remote = base.copy(title = "Remote")

        assertEquals("Local", merge.merge(base, local, remote, preferLocal = true).title)
        assertEquals("Remote", merge.merge(base, local, remote, preferLocal = false).title)
    }

    @Test
    fun nullBase_fillsUnsetLocalFieldsFromRemote_contestedUsesPreferLocal() {
        val local = TodoistTask(title = "Local title")           // notes/due never set locally
        val remote = TodoistTask(
            title = "Server title", notes = "server notes", dueDate = 4_000L, dueHasTime = true, priority = 3,
        )

        val merged = merge.merge(null, local, remote, preferLocal = true)
        assertEquals("Local title", merged.title, "contested title -> preferLocal")
        assertEquals("server notes", merged.notes, "unset local notes filled from server")
        assertEquals(4_000L, merged.dueDate)
        assertEquals(true, merged.dueHasTime)
        assertEquals(3, merged.priority)

        assertEquals("Server title", merge.merge(null, local, remote, preferLocal = false).title)
    }

    @Test
    fun dueDateAndHasTime_moveAsAUnit() {
        val local = base.copy(dueDate = 2_000L, dueHasTime = true)
        val remote = base.copy(title = "Remote title")

        val merged = merge.merge(base, local, remote, preferLocal = false)

        assertEquals(2_000L, merged.dueDate)
        assertEquals(true, merged.dueHasTime)
        assertEquals("Remote title", merged.title)
    }

    @Test
    fun dueConflict_takesOneSidePairIntact() {
        val local = base.copy(dueDate = 2_000L, dueHasTime = true)
        val remote = base.copy(dueDate = 3_000L, dueHasTime = false)

        merge.merge(base, local, remote, preferLocal = true).let {
            assertEquals(2_000L, it.dueDate); assertEquals(true, it.dueHasTime)
        }
        merge.merge(base, local, remote, preferLocal = false).let {
            assertEquals(3_000L, it.dueDate); assertEquals(false, it.dueHasTime)
        }
    }

    @Test
    fun nonEditorFields_takenFromRemote() {
        val local = base.copy(title = "Local title", priority = 1, labels = listOf("stale"))
        val remote = base.copy(priority = 4, labels = listOf("work"))

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals(4, merged.priority)
        assertEquals(listOf("work"), merged.labels)
        assertEquals("Local title", merged.title)
    }
}

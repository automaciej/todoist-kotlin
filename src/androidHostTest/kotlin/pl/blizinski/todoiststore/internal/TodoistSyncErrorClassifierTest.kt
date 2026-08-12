package pl.blizinski.todoiststore.internal

import pl.blizinski.todoiststore.internal.network.TodoistApiException
import pl.blizinski.tasksync.SyncErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodoistSyncErrorClassifierTest {

    private val classifier = TodoistSyncErrorClassifier()

    @Test
    fun classifySpecialReturnsAuthFailedFor401() {
        assertEquals(SyncErrorKind.AUTH_FAILED, classifier.classifySpecial(TodoistApiException(401, "Unauthorized")))
    }

    @Test
    fun classifySpecialReturnsNullForNon401() {
        assertNull(classifier.classifySpecial(TodoistApiException(500, "Server error")))
    }

    @Test
    fun classifySpecialReturnsNullForUnrelatedException() {
        assertNull(classifier.classifySpecial(RuntimeException("network blip")))
    }

    @Test
    fun httpStatusReadsStatusCodeFromTodoistApiException() {
        assertEquals(404, classifier.httpStatus(TodoistApiException(404, "Not Found")))
    }

    @Test
    fun httpStatusIsNullForUnrelatedException() {
        assertNull(classifier.httpStatus(RuntimeException("network blip")))
    }

    @Test
    fun extractConsentIntentIsAlwaysNull() {
        // By design — see TodoistSyncErrorClassifier's doc comment on the open question this
        // leaves for Stage T2's real AppAuth wiring.
        assertNull(classifier.extractConsentIntent(TodoistApiException(401, "Unauthorized")))
        assertNull(classifier.extractConsentIntent(RuntimeException("network blip")))
    }
}

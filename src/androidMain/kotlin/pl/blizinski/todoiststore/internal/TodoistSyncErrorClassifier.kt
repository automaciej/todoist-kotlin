package pl.blizinski.todoiststore.internal

import pl.blizinski.todoiststore.internal.network.TodoistApiException
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncErrorKind

internal class TodoistSyncErrorClassifier : SyncErrorClassifier {

    override fun classifySpecial(e: Exception): SyncErrorKind? = when {
        (e as? TodoistApiException)?.httpStatus == 401 -> SyncErrorKind.AUTH_FAILED
        else -> null
    }

    override fun httpStatus(e: Exception): Int? = (e as? TodoistApiException)?.httpStatus

    /**
     * Always null: an expired/revoked OAuth refresh token has no Android-mediated interactive
     * consent flow the way Google's UserRecoverableAuthIOException does — recovery here is
     * "reconnect the account" (re-running the AppAuth authorization flow), not a stored intent
     * this library could hand back. See the design doc's Critical Test Case 6 — this is a
     * genuine open question about AppAuth's actual failure shape, not yet resolved by live
     * testing at the time this class was written.
     */
    override fun extractConsentIntent(e: Exception): Any? = null
}

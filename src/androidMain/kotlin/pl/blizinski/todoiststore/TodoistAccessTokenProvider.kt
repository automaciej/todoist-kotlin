package pl.blizinski.todoiststore

/**
 * Supplies a valid Todoist OAuth access token. Implemented by TaskCompass itself, wrapping
 * whatever manages the AppAuth `AuthState`/refresh-token lifecycle (see the design doc's Stage
 * T2) — this library never sees a client id/secret, a refresh token, or the token endpoint.
 *
 * Unlike [pl.blizinski.githubissuesstore.GitHubAccessTokenProvider]'s PAT (valid until manually
 * revoked), a Todoist access token expires roughly hourly. [getToken] is expected to transparently
 * refresh an expiring token before returning — this library never triggers a refresh itself, it
 * only calls [getToken] once per request and reacts to a 401 via
 * [pl.blizinski.todoiststore.internal.TodoistSyncErrorClassifier] if the token turned out to be
 * invalid anyway (e.g. the refresh token itself was revoked).
 */
interface TodoistAccessTokenProvider {
    suspend fun getToken(): String
}

package pl.blizinski.todoiststore.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.todoiststore.internal.TodoistProject
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord

private const val TODOIST_API_BASE = "https://api.todoist.com/api/v1"
private const val PAGE_LIMIT = 200
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Android/JVM (OkHttp) [NetworkSource] for Todoist's REST-style `/api/v1` surface. The wire ⇄
 * [TodoistTask] mapping and date formats are shared with the wasmJs (Ktor) implementation — see
 * `TodoistMapping.kt` in commonMain; only the HTTP transport lives here.
 *
 * **Plain per-resource endpoints, not the batched Sync API** — so [getRecords] has no
 * incremental delta ([updatedMin] is accepted but unused; every call is a full pull of the
 * project's active tasks). A deliberate, documented v1 tradeoff. Todoist has a native
 * cross-project [moveRecord].
 */
internal class TodoistNetworkSource(
    private val tokenProvider: AccessTokenProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : NetworkSource<TodoistTask, TodoistProject> {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun request(method: String, url: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getToken()
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
                "DELETE" -> requestBuilder.delete()
                else -> error("Unsupported method $method")
            }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TodoistApiException(response.code, "Todoist API $method $url failed: ${response.code} $responseBody")
                }
                responseBody
            }
        }

    private suspend inline fun <reified T> requestPage(baseUrl: String, params: Map<String, String>): List<T> {
        val serializer = TodoistPage.serializer(serializer<T>())
        val result = mutableListOf<T>()
        var cursor: String? = null
        do {
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            for ((key, value) in params) urlBuilder.addQueryParameter(key, value)
            urlBuilder.addQueryParameter("limit", PAGE_LIMIT.toString())
            cursor?.let { urlBuilder.addQueryParameter("cursor", it) }
            val page = json.decodeFromString(serializer, request("GET", urlBuilder.build().toString()))
            result += page.results
            cursor = page.nextCursor
        } while (cursor != null)
        return result
    }

    // --- Lists (Todoist projects) ---

    override suspend fun getLists(): List<RemoteListRecord<TodoistProject>> =
        requestPage<TodoistProjectDto>("$TODOIST_API_BASE/projects", emptyMap())
            .map { RemoteListRecord(remoteId = it.id, content = TodoistProject(name = it.name)) }

    override suspend fun createList(content: TodoistProject): RemoteListRecord<TodoistProject> {
        val body = json.encodeToString(TodoistProjectCreateRequest.serializer(), TodoistProjectCreateRequest(name = content.name))
        val dto = json.decodeFromString(TodoistProjectDto.serializer(), request("POST", "$TODOIST_API_BASE/projects", body))
        return RemoteListRecord(remoteId = dto.id, content = TodoistProject(name = dto.name))
    }

    override suspend fun updateList(remoteListId: String, content: TodoistProject) {
        val body = json.encodeToString(TodoistProjectUpdateRequest.serializer(), TodoistProjectUpdateRequest(name = content.name))
        request("POST", "$TODOIST_API_BASE/projects/$remoteListId", body)
    }

    override suspend fun deleteList(remoteListId: String) {
        request("DELETE", "$TODOIST_API_BASE/projects/$remoteListId")
    }

    // --- Tasks ---

    /** [updatedMin] is unused — see this class's top doc comment. */
    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<TodoistTask>> =
        requestPage<TodoistTaskDto>("$TODOIST_API_BASE/tasks", mapOf("project_id" to remoteListId))
            .map { it.toRemoteRecord() }

    override suspend fun createRecord(remoteListId: String, content: TodoistTask): RemoteRecord<TodoistTask> {
        val body = json.encodeToString(TodoistTaskCreateRequest.serializer(), content.toCreateRequest(remoteListId))
        val dto = json.decodeFromString(TodoistTaskDto.serializer(), request("POST", "$TODOIST_API_BASE/tasks", body))
        return dto.toRemoteRecord()
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: TodoistTask) {
        val body = json.encodeToString(JsonObject.serializer(), content.toUpdateRequestJson())
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId", body)
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) {
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId/close")
    }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) {
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId/reopen")
    }

    override suspend fun deleteRecord(remoteListId: String, remoteId: String) {
        request("DELETE", "$TODOIST_API_BASE/tasks/$remoteId")
    }

    /** Native cross-project move — Todoist's move endpoint needs only the destination. */
    override suspend fun moveRecord(sourceRemoteListId: String, remoteId: String, destRemoteListId: String, previousRemoteId: String?) {
        val body = json.encodeToString(TodoistTaskMoveRequest.serializer(), TodoistTaskMoveRequest(projectId = destRemoteListId))
        request("POST", "$TODOIST_API_BASE/tasks/$remoteId/move", body)
    }
}

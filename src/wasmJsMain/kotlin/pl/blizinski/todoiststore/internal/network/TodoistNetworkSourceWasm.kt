package pl.blizinski.todoiststore.internal.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.todoiststore.internal.TodoistProject
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord

private const val TODOIST_API_BASE = "https://api.todoist.com/api/v1"
private const val PAGE_LIMIT = 200

/**
 * wasmJs (Ktor) [NetworkSource] for Todoist — the counterpart of the Android target's OkHttp
 * [TodoistNetworkSource]. Shares the wire DTOs and the ⇄ [TodoistTask] mapping / date formats
 * with it (`TodoistApiModels.kt` + `TodoistMapping.kt`, both commonMain); only the HTTP client
 * differs. See TaskCompass's `Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md`.
 */
internal class TodoistNetworkSourceWasm(
    private val tokenProvider: AccessTokenProvider,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
) : NetworkSource<TodoistTask, TodoistProject> {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: String, params: Map<String, String> = emptyMap(), cursor: String? = null): String {
        val token = tokenProvider.getToken()
        val response: HttpResponse = httpClient.get(url) {
            header("Authorization", "Bearer $token")
            params.forEach { (k, v) -> parameter(k, v) }
            parameter("limit", PAGE_LIMIT.toString())
            cursor?.let { parameter("cursor", it) }
        }
        return checkSuccess(response, "GET", url)
    }

    private suspend fun post(url: String, body: String? = null): String {
        val token = tokenProvider.getToken()
        val response: HttpResponse = httpClient.post(url) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body ?: "{}")
        }
        return checkSuccess(response, "POST", url)
    }

    private suspend fun delete(url: String): String {
        val token = tokenProvider.getToken()
        val response: HttpResponse = httpClient.delete(url) { header("Authorization", "Bearer $token") }
        return checkSuccess(response, "DELETE", url)
    }

    private suspend fun checkSuccess(response: HttpResponse, method: String, url: String): String {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw TodoistApiException(response.status.value, "Todoist API $method $url failed: ${response.status} $text")
        }
        return text
    }

    private suspend inline fun <reified T> requestPage(url: String, params: Map<String, String>): List<T> {
        val serializer = TodoistPage.serializer(kotlinx.serialization.serializer<T>())
        val result = mutableListOf<T>()
        var cursor: String? = null
        do {
            val page = json.decodeFromString(serializer, get(url, params, cursor))
            result += page.results
            cursor = page.nextCursor
        } while (cursor != null)
        return result
    }

    // --- Lists ---

    override suspend fun getLists(): List<RemoteListRecord<TodoistProject>> =
        requestPage<TodoistProjectDto>("$TODOIST_API_BASE/projects", emptyMap())
            .map { RemoteListRecord(remoteId = it.id, content = TodoistProject(name = it.name)) }

    override suspend fun createList(content: TodoistProject): RemoteListRecord<TodoistProject> {
        val body = json.encodeToString(TodoistProjectCreateRequest.serializer(), TodoistProjectCreateRequest(name = content.name))
        val dto = json.decodeFromString(TodoistProjectDto.serializer(), post("$TODOIST_API_BASE/projects", body))
        return RemoteListRecord(remoteId = dto.id, content = TodoistProject(name = dto.name))
    }

    override suspend fun updateList(remoteListId: String, content: TodoistProject) {
        val body = json.encodeToString(TodoistProjectUpdateRequest.serializer(), TodoistProjectUpdateRequest(name = content.name))
        post("$TODOIST_API_BASE/projects/$remoteListId", body)
    }

    override suspend fun deleteList(remoteListId: String) {
        delete("$TODOIST_API_BASE/projects/$remoteListId")
    }

    // --- Tasks ---

    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<TodoistTask>> =
        requestPage<TodoistTaskDto>("$TODOIST_API_BASE/tasks", mapOf("project_id" to remoteListId))
            .map { it.toRemoteRecord() }

    override suspend fun createRecord(remoteListId: String, content: TodoistTask): RemoteRecord<TodoistTask> {
        val body = json.encodeToString(TodoistTaskCreateRequest.serializer(), content.toCreateRequest(remoteListId))
        val dto = json.decodeFromString(TodoistTaskDto.serializer(), post("$TODOIST_API_BASE/tasks", body))
        return dto.toRemoteRecord()
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: TodoistTask) {
        val body = json.encodeToString(JsonObject.serializer(), content.toUpdateRequestJson())
        post("$TODOIST_API_BASE/tasks/$remoteId", body)
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) {
        post("$TODOIST_API_BASE/tasks/$remoteId/close")
    }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) {
        post("$TODOIST_API_BASE/tasks/$remoteId/reopen")
    }

    override suspend fun deleteRecord(remoteListId: String, remoteId: String) {
        delete("$TODOIST_API_BASE/tasks/$remoteId")
    }

    override suspend fun moveRecord(sourceRemoteListId: String, remoteId: String, destRemoteListId: String, previousRemoteId: String?) {
        val body = json.encodeToString(TodoistTaskMoveRequest.serializer(), TodoistTaskMoveRequest(projectId = destRemoteListId))
        post("$TODOIST_API_BASE/tasks/$remoteId/move", body)
    }
}

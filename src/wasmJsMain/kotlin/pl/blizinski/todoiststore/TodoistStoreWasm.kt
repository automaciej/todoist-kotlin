package pl.blizinski.todoiststore

import kotlinx.serialization.serializer
import pl.blizinski.todoiststore.internal.TodoistContentAdapter
import pl.blizinski.todoiststore.internal.TodoistProject
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.todoiststore.internal.network.TodoistApiException
import pl.blizinski.todoiststore.internal.network.TodoistNetworkSourceWasm
import pl.blizinski.tasksync.HttpStatusSyncErrorClassifier
import pl.blizinski.tasksync.NoStoredTokenException
import pl.blizinski.tasksync.SyncErrorKind
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.store.TaskStore
import pl.blizinski.tasksync.store.buildWasmTaskStore

/**
 * Builds an IndexedDB-backed [TaskStore] for Todoist on wasmJs, syncing on demand only — the
 * Ktor counterpart of the Android [todoistStore]. Same [Todoist.capabilities], same
 * [TodoistContentAdapter], same wire mapping; only the HTTP client and local store differ.
 */
fun todoistWasmStore(
    tokenProvider: AccessTokenProvider,
    config: StoreConfig,
): TaskStore = buildWasmTaskStore(
    config = config,
    capabilities = Todoist.capabilities,
    network = TodoistNetworkSourceWasm(tokenProvider),
    errorClassifier = HttpStatusSyncErrorClassifier(
        statusOf = { (it as? TodoistApiException)?.httpStatus },
        extraSpecial = { if (it is NoStoredTokenException) SyncErrorKind.AUTH_FAILED else null },
    ),
    recordSerializer = serializer<TodoistTask>(),
    listSerializer = serializer<TodoistProject>(),
    adapter = TodoistContentAdapter,
)

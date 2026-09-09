package pl.blizinski.todoiststore

import android.content.Context
import kotlinx.serialization.serializer
import pl.blizinski.todoiststore.internal.TodoistContentAdapter
import pl.blizinski.todoiststore.internal.TodoistProject
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.todoiststore.internal.network.TodoistApiException
import pl.blizinski.todoiststore.internal.network.TodoistNetworkSource
import pl.blizinski.tasksync.HttpStatusSyncErrorClassifier
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.store.TaskStore
import pl.blizinski.tasksync.store.buildAndroidTaskStore

/**
 * Builds a local-first [TaskStore] for Todoist on Android. Reads come from the Room cache;
 * writes are optimistic and synced in the background. Projects support real create/rename/
 * delete and tasks support a native cross-project move. One instance per connected account,
 * keyed by [config]`.dbName`. No legacy on-disk schema, so no Room migrations.
 */
fun todoistStore(
    context: Context,
    tokenProvider: AccessTokenProvider,
    config: StoreConfig,
): TaskStore = buildAndroidTaskStore(
    context = context,
    config = config,
    capabilities = Todoist.capabilities,
    network = TodoistNetworkSource(tokenProvider = tokenProvider),
    errorClassifier = HttpStatusSyncErrorClassifier(statusOf = { (it as? TodoistApiException)?.httpStatus }),
    recordSerializer = serializer<TodoistTask>(),
    listSerializer = serializer<TodoistProject>(),
    adapter = TodoistContentAdapter,
)

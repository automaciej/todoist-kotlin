package pl.blizinski.todoiststore

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.serializer
import pl.blizinski.todoiststore.internal.TodoistProject
import pl.blizinski.todoiststore.internal.TodoistSyncErrorClassifier
import pl.blizinski.todoiststore.internal.TodoistTask
import pl.blizinski.todoiststore.internal.network.TodoistNetworkSource
import pl.blizinski.todoiststore.internal.toPublic
import pl.blizinski.todoiststore.internal.toTask
import pl.blizinski.todoiststore.internal.toTaskList
import pl.blizinski.todoiststore.models.FatalStorageError
import pl.blizinski.todoiststore.models.SyncStatus
import pl.blizinski.todoiststore.models.Task
import pl.blizinski.todoiststore.models.TaskList
import pl.blizinski.tasksync.AdaptivePoller
import pl.blizinski.tasksync.OpType
import pl.blizinski.tasksync.PendingOp
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.RoomLocalStore
import pl.blizinski.tasksync.SyncConfig
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncWorkerDependencies
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.accumulateRecentErrors
import pl.blizinski.tasksync.db.TaskSyncDatabase
import pl.blizinski.tasksync.isNetworkAvailable
import java.io.Closeable
import java.util.UUID
import kotlinx.serialization.json.Json

private const val TAG = "TodoistStore"

/**
 * Local-first store for Todoist. Reads always come from the Room cache; writes are applied
 * locally and queued for background sync. The library manages all network interaction
 * internally, except access-token acquisition (see [tokenProvider]).
 *
 * Unlike `github-issues-kotlin`'s store, this one has a real [createList]/[updateList]/
 * [deleteList] surface (Todoist projects behave like Google/Microsoft lists) and a real
 * [moveTask] (native cross-project move — see [TodoistNetworkSource.moveRecord]). Only one
 * instance per connected Todoist account is supported per process — each account gets its own
 * [TodoistStore] instance with its own [config]-supplied database file name.
 */
class TodoistStore(
    context: Context,
    private val tokenProvider: TodoistAccessTokenProvider,
    private val config: TodoistStoreConfig = TodoistStoreConfig(),
) : TodoistStoreApi, Closeable {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val db: TaskSyncDatabase = Room.databaseBuilder(
        appContext,
        TaskSyncDatabase::class.java,
        config.dbName,
    ).build() // fresh schema, no legacy on-disk format to migrate from

    private val store = RoomLocalStore<TodoistTask, TodoistProject>(
        db.recordsDao(),
        db.listsDao(),
        db.pendingOpsDao(),
        serializer(),
        serializer(),
    )

    private val network = TodoistNetworkSource(tokenProvider = tokenProvider)
    private val errorClassifier = TodoistSyncErrorClassifier()
    private val pendingOpsProcessor = PendingOpsProcessor(store, network, serializer<TodoistTask>(), errorClassifier)
    private val syncEngine = SyncEngine(
        store, network, pendingOpsProcessor, errorClassifier,
        isOnline = { isNetworkAvailable(appContext) },
    )

    private val syncConfig = SyncConfig(config.minPollInterval, config.maxPollInterval)
    private val workManager = WorkManager.getInstance(appContext)
    private val poller = AdaptivePoller(workManager, syncConfig, instanceKey = config.dbName)

    private val _syncStatus = MutableStateFlow(SyncStatus())

    init {
        SyncWorkerDependencies.put(
            config.dbName,
            SyncWorkerDependencies.Deps(syncEngine, syncConfig, onSyncResult = ::applySyncResult),
        )
        poller.start()
    }

    private fun applySyncResult(result: SyncEngine.SyncResult) {
        val now = System.currentTimeMillis()
        _syncStatus.update { current ->
            current.copy(
                isSyncing = false,
                lastSyncedAt = now,
                recentErrors = accumulateRecentErrors(
                    previous = current.recentErrors,
                    new = result.errors.map { it.toPublic() },
                    max = config.maxRecentErrors,
                ),
                consentIntent = result.consentIntent,
            )
        }
    }

    private fun reportFatalStorageError(e: Throwable) {
        Log.e(TAG, "Local storage unusable", e)
        _syncStatus.update { current ->
            if (current.fatalStorageError != null) current
            else current.copy(
                fatalStorageError = FatalStorageError(
                    occurredAt = System.currentTimeMillis(),
                    summary = e.message ?: e::class.simpleName ?: "Unknown error",
                    details = e.stackTraceToString(),
                )
            )
        }
    }

    private fun <T> Flow<T>.guardStorage(default: T): Flow<T> = catch { e ->
        reportFatalStorageError(e)
        emit(default)
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> =
        store.lists().guardStorage(emptyList()).map { lists -> lists.map { it.toTaskList() } }

    override fun tasks(listLocalId: String): Flow<List<Task>> =
        store.records(listLocalId).guardStorage(emptyList()).map { records -> records.map { it.toTask() } }

    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount().guardStorage(0),
        store.failedOpCount().guardStorage(0),
    ) { status, pending, failed -> status.copy(pendingOpCount = pending, failedOpCount = failed) }

    /** See [SyncEngine.writeMutex]'s doc comment — every local write must hold it. */
    private suspend fun <T> guardWrite(onError: T, block: suspend () -> T): T = try {
        syncEngine.writeMutex.withLock { block() }
    } catch (e: Exception) {
        reportFatalStorageError(e)
        onError
    }

    // -----------------------------------------------------------------------
    // Public write API — optimistic local write + pending op + trigger sync
    // -----------------------------------------------------------------------

    override suspend fun createList(title: String): String = guardWrite(onError = "") {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        store.upsertList(
            SyncedListRecord(
                localId = localId,
                remoteId = null,
                content = TodoistProject(name = title),
                lastSyncedAt = null,
                position = Int.MAX_VALUE, // corrected to real position on next sync
            )
        )
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.CREATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = now)
        )
        poller.onLocalWrite()
        localId
    }

    override suspend fun updateList(localId: String, title: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getListByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertList(entity.copy(content = TodoistProject(name = title)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.UPDATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun deleteList(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getListByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        // Cancel pending ops for all tasks in this list; remove locally-created tasks immediately.
        for (task in store.getAllRecordsForList(localId)) {
            store.removeAllPendingOpsForEntity(task.localId)
            if (task.remoteId == null) store.hardDeleteRecord(task.localId)
        }
        // Soft-delete the list so it disappears from the UI immediately.
        store.upsertList(entity.copy(isDeleted = true))
        if (entity.remoteId != null) {
            // Save remoteId in contentJson — entity may be modified/cleaned before sync runs.
            store.enqueuePendingOp(
                PendingOp(id = UUID.randomUUID().toString(), type = OpType.DELETE_LIST, entityLocalId = localId, listLocalId = localId, contentJson = entity.remoteId, createdAt = now)
            )
            poller.onLocalWrite()
        } else {
            store.hardDeleteList(localId)
        }
    }

    override suspend fun createTask(
        listLocalId: String,
        title: String,
        notes: String?,
        dueDate: Long?,
        dueHasTime: Boolean,
        priority: Int?,
        labels: List<String>,
        recurrenceRule: String?,
    ): String = guardWrite(onError = "") {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val content = TodoistTask(
            title = title, notes = notes, createdDate = now,
            dueDate = dueDate, dueHasTime = dueHasTime, priority = priority, labels = labels,
            recurrenceRule = recurrenceRule,
        )
        store.upsertRecord(
            SyncedRecord(localId = localId, remoteId = null, listLocalId = listLocalId, content = content, isCompleted = false, lastSyncedAt = null)
        )
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.CREATE_RECORD, entityLocalId = localId, listLocalId = listLocalId,
                contentJson = json.encodeToString(serializer(), content), createdAt = now,
            )
        )
        poller.onLocalWrite()
        localId
    }

    override suspend fun updateTask(
        localId: String,
        title: String,
        notes: String?,
        dueDate: Long?,
        dueHasTime: Boolean,
        priority: Int?,
        labels: List<String>,
        recurrenceRule: String?,
    ): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        val newContent = entity.content.copy(
            title = title, notes = notes, dueDate = dueDate, dueHasTime = dueHasTime, priority = priority, labels = labels,
            recurrenceRule = recurrenceRule,
        )
        store.upsertRecord(entity.copy(content = newContent))
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.UPDATE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId,
                contentJson = json.encodeToString(serializer(), newContent), createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun completeTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertRecord(entity.copy(isCompleted = true, content = entity.content.copy(completedDate = now)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.COMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun uncompleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertRecord(entity.copy(isCompleted = false, content = entity.content.copy(completedDate = null)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.UNCOMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    /** A genuine delete — see [TodoistStoreApi.deleteTask]. */
    override suspend fun deleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.softDeleteRecord(localId)
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.DELETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    /** Moves a task to [destListLocalId] in place — Todoist's native move. See
     *  [TodoistStoreApi.moveTask]. */
    override suspend fun moveTask(localId: String, destListLocalId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val sourceListLocalId = entity.listLocalId
        if (sourceListLocalId == destListLocalId) return@guardWrite
        val now = System.currentTimeMillis()
        // reassignRecord also rewrites this entity's other pending ops' listLocalId (e.g. a
        // not-yet-pushed CREATE_RECORD), so a still-unsynced task simply ends up created
        // directly in the destination — see PendingOpsProcessor.merge()'s CREATE+MOVE folding.
        store.reassignRecord(localId, destListLocalId)
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.MOVE_RECORD, entityLocalId = localId,
                listLocalId = destListLocalId, contentJson = sourceListLocalId, createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun forceSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            applySyncResult(syncEngine.sync())
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    override suspend fun fullSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            applySyncResult(syncEngine.fullSync())
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun close() {
        poller.cancel()
        db.close()
        SyncWorkerDependencies.remove(config.dbName)
    }
}

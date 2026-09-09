# todoist-kotlin

[![](https://jitpack.io/v/automaciej/todoist-kotlin.svg)](https://jitpack.io/#automaciej/todoist-kotlin)

Kotlin Multiplatform library that wraps the [Todoist API](https://developer.todoist.com/)
with a local cache and exposes it through the shared
[`TaskStore`](https://github.com/automaciej/task-sync-kotlin) contract, built on
[task-sync-kotlin](https://github.com/automaciej/task-sync-kotlin)'s offline-first
sync engine.

Reads and writes go through a local database that is the source of truth for the
UI, reconciled with Todoist in the background, so the app works fully offline
between syncs. Todoist remains the ultimate source of truth for task data.

The library never handles Todoist OAuth — it takes an `AccessTokenProvider`
(`pl.blizinski.tasksync.model.AccessTokenProvider`) supplied by the consuming
app, which owns the OAuth/PKCE flow and token refresh.

## One contract, four sources

`todoist-kotlin`, `google-tasks-kotlin`, `microsoft-todo-kotlin` and
`github-issues-kotlin` are separate, independently-versioned libraries that
**all expose the same `pl.blizinski.tasksync.store.TaskStore` interface over the
same `pl.blizinski.tasksync.model.Task` / `TaskList` types**. A consuming app can
hold several side by side and treat them uniformly, branching only on each one's
`StoreCapabilities` (`Todoist.capabilities`). Todoist is the richest of the four:
projects with real create/rename/delete, a native cross-project move, a real due
*time*, native priority (1–4), first-class labels, subtasks, and recurrence as a
server-parsed natural-language string (`RecurrenceStyle.FUZZY` →
`RecurrenceRule.TextRule`).

## API

```kotlin
// Android
val store: TaskStore = todoistStore(
    context,
    tokenProvider,                     // AccessTokenProvider
    StoreConfig(dbName = "todoist_store_$accountId"),
)
// wasmJs
val store: TaskStore = todoistWasmStore(tokenProvider, StoreConfig(dbName = "todoist_store"))
```

`TaskStore` gives you `Flow`s of task lists (projects) and tasks per list, a
`Flow<SyncStatus>`, optimistic write methods, `moveTask` (native), and
`forceSync()`/`fullSync()`. Op-merging, tombstone handling, per-account polling
isolation and `SyncErrorKind` classification are inherited from `task-sync-kotlin`.

> **Incremental delta:** the plain REST `GET /api/v1/tasks` has no
> `updated_since` parameter, so every sync is a full pull of a project's active
> tasks. A deliberate v1 tradeoff.

## Targets

`androidTarget` (Room + OkHttp) and a `wasmJs` target (`todoistWasmStore`,
IndexedDB, Ktor, sync-on-demand). The wire DTOs and the wire ⇄ model mapping are
`commonMain`, shared by both; only the HTTP client differs. wasmJs is excluded
from JitPack builds (see `jitpack.yml`).

## Usage

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven { url = uri("https://jitpack.io") } }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.automaciej:todoist-kotlin:v0.2.1")
}
```

Implement `AccessTokenProvider` against your app's OAuth/PKCE flow, call
`todoistStore(...)`, and consume the returned `TaskStore`.

## Build

```
./build.sh build
```

# todoist-store

[![](https://jitpack.io/v/automaciej/todoist-kotlin.svg)](https://jitpack.io/#automaciej/todoist-kotlin)

Android library that wraps the [Todoist API](https://developer.todoist.com/)
with a local Room cache and exposes a reactive `TodoistStoreApi`, built on
top of [task-sync-kotlin](https://github.com/automaciej/task-sync-kotlin)'s
shared offline-first sync engine.

This is not a thin, stateless network wrapper: reads and writes go through a
local Room database that is the actual source of truth for the UI, kept in
sync with Todoist in the background. Todoist itself remains the ultimate
source of truth for task data; this library's cache is what lets the app
work fully offline in between syncs.

This library never handles Todoist OAuth itself — it takes a
`TodoistAccessTokenProvider` supplied by the consuming app, which owns the
actual OAuth/PKCE flow and token refresh. This keeps the library free of
any client ID or other app-specific credential.

## Features

- **`TodoistStoreApi`**: reactive `Flow`s of task lists (projects) and tasks
  per list, plus a `Flow<SyncStatus>` for surfacing sync errors/progress in
  the UI.
- **Adaptive background polling and pending-op merging** inherited from
  `task-sync-kotlin`: op-merging, tombstone detection, per-account polling
  isolation via `AdaptivePoller`, and structured `SyncErrorKind`
  classification specific to Todoist's auth/rate-limit errors.
- **`forceSync()` / `fullSync()`**: run a sync cycle synchronously on demand,
  with `fullSync()` re-pulling every list from scratch to repair local state
  that drifted in a way incremental sync can't catch.

## What it is *not*

- **Android-only.** Built on `task-sync-kotlin`, which currently declares
  only an `androidTarget` — see that repo's README for what a
  multiplatform port would require.
- **Not a general-purpose task-list abstraction.** `Task`/`TaskList` here
  are shaped around Todoist's own data model. It's not meant to be swapped
  for another source's schema — that's what `google-tasks-kotlin`/
  `microsoft-todo-kotlin`/`github-issues-kotlin` are, as separate,
  independently-versioned libraries sharing the same underlying engine.

## Usage

Add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.automaciej:todoist-kotlin:v0.1.0")
}
```

Implement `TodoistAccessTokenProvider` against your app's own OAuth/PKCE
flow, construct a `TodoistStore` with it, then consume it through
`TodoistStoreApi`.

## Build

```
./build.sh build
```

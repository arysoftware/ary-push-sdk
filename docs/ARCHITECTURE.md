# Architecture

## The shape of it

```
                        HOST APPLICATION
        UI, navigation, business logic, app-specific APIs
                               |
                    events in, commands out
                               |
        +----------------------+----------------------+
        |                ARY PUSH SDK                 |
        |                                             |
        |   Android SDK      iOS SDK      Flutter     |
        |   (Kotlin)         (Swift)      (bridge)    |
        |       |               |             |       |
        |      FCM            APNs      native SDK    |
        |       +-------+-------+                     |
        |                                             |
        |              PUSH ENGINE                    |
        |  Permission, Token, Installation,           |
        |  Notification, Dedup, Events                |
        |                  ||                         |
        |       (independent, not coupled)            |
        |                  ||                         |
        |            BACKEND SYNC                     |
        |  User, Tags, Topics, Storage,               |
        |  SyncManager, PushBackend, RestClient       |
        +----------------------+----------------------+
                               |
                     PRIVATE ARY PUSH API
        Applications, Users, Installations, Tags
                 Segment engine, Campaigns
                               |
                          FCM / APNs
```

## The one structural decision

The push engine and backend synchronisation are **independent halves that never await each
other**. Nothing in the receive-display-open path blocks on the network, and nothing in the
network path can prevent a notification from being handled.

The consequence is the property that matters in production: **a backend outage is invisible to
push**. Notifications still arrive, still render, still open, and the local token is still
readable. Synchronisation catches up later from a durable queue.

Everything else follows from this. `PushBackend` is an interface so the engine never sees HTTP.
`NoopPushBackend` exists so an application with no server exercises the same code path.
`SyncManager` owns all deferral, so no manager has to know whether the device is online.

## Components

### Push engine

| Component | Responsibility | Notable decision |
| --- | --- | --- |
| `PermissionManager` | Reads and requests notification permission | Combines the runtime permission with the app-level toggle, so `DENIED` really means "nothing will show" |
| `TokenManager` | Token issue, refresh, replacement, deletion | Treats the token as a cache of something the transport owns; ignores unchanged values |
| `InstallationManager` | The SDK's stable device identity | Independent of token and user; a refresh or logout must never change it |
| `NotificationParser` | Platform payload to `PushNotification` | Sender id beats transport id beats content hash, so resends deduplicate |
| `NotificationRenderer` (Android) | Builds and posts the notification | Falls back to the SDK channel when a payload names one that does not exist, rather than letting the system drop it |
| `NotificationEventDispatcher` | Delivers received and opened events | Persists an open nobody was listening for, and replays it |
| `DeduplicationManager` | Bounded persistent LRU of seen ids | Check-and-insert is atomic; duplicates arrive on two threads |
| `EventManager` | Push-related events only | Deliberately not an analytics SDK |

### Backend synchronisation

| Component | Responsibility | Notable decision |
| --- | --- | --- |
| `UserManager` | The installation-to-user association | Logout clears the user and nothing else |
| `TagManager` | Local tag state, debounced | A burst of `addTag` calls becomes one request |
| `TopicManager` | Topic subscriptions | Validated against FCM's grammar on both platforms, so a topic means the same thing everywhere |
| `StorageManager` | Namespaced isolated persistence | Every key under `ary_push.`, in the SDK's own store |
| `OperationQueue` | Durable, bounded, self-coalescing queue | Newer state supersedes older; tag writes merge; events are trimmed first |
| `SyncManager` | Ordering, retry, permanent-failure policy | Stops on failure rather than skipping ahead, so nothing overtakes its dependency |
| `RetryManager` | Exponential backoff with jitter | Jitter prevents a synchronised retry storm across every device that failed at once |
| `PushBackend` | The wire contract, without HTTP | The seam that makes the core testable and the transport replaceable |
| `RestClient` | HTTP only | No push semantics; one single-shot 401 refresh |

## Why the queue looks like this

Three properties, each forced by a real failure:

**Durable.** A `login()` in aeroplane mode must still be pending after the process is killed and
the device rebooted. So mutations are committed synchronously, not applied lazily.

**Coalescing.** Collapse redundant work at the source instead of sending it and hoping the
backend copes. Three `addTag` calls become one PATCH; a newer token replaces an older unsent one
rather than queueing behind it; a logout supersedes an unsent identify.

**Bounded.** A device offline for a week must not accumulate unbounded state. Past 100 entries
the oldest low-value entries (events) are dropped first.

And one policy: **permanent failures are dropped, not retried.** A queue that retries a 422
forever is a queue that never drains again.

## Ordering

```
Register installation -> Update token -> Identify user -> Update tags
```

Encoded in the operation type's ordinal and enforced twice: the queue sorts by it, and each
dependent operation re-checks registration before running. Belt and braces, because an
installation can be created by a background message long before the queue is touched.

## Threading

| Platform | Rule |
| --- | --- |
| Android | SDK work on `Dispatchers.IO` under a `SupervisorJob`; listener callbacks posted to the main thread |
| iOS | SDK work in structured tasks; listener callbacks and permission results on the main queue |
| Flutter | Channel messages on the platform thread; no work on the UI isolate |

Public APIs are safe to call from any thread. Host callbacks are wrapped: a listener that throws
is logged and cannot break the others, or the system callback the SDK is running inside.

## Battery

No permanent service. No polling. No timers. No persistent connection.

The SDK is dormant between messages. FCM and APNs wake the process; a connectivity callback
drains the queue; that is all. Android registers a `NetworkCallback`, iOS an `NWPathMonitor`.
Both are OS-managed observers, not processes.

## Compatibility matrix

| | Minimum | Compiled against |
| --- | --- | --- |
| Android `minSdk` | 21 | `compileSdk` 36 |
| Kotlin | 2.3.20 | JVM target 17 |
| Android Gradle Plugin | 9.0.1 | Gradle 9.1.0 |
| Firebase Messaging | 24.0.0 | Overridable by the host's BoM |
| OkHttp | 4.12.0 | |
| iOS | 13.0 | Swift 5.9 |
| Flutter (Dart package) | 3.10.0 | Dart 3.0.0 |
| Flutter (Android build) | 3.44 | AGP 9.0.1 and Kotlin 2.3.20 by default, both overridable |

Kept current for every release. A change here is at minimum a MINOR version.

## What is deliberately absent

| Absent | Why |
| --- | --- |
| A UI layer | An SDK that renders screens ties itself to one app's design |
| Navigation | The SDK cannot know what `order_id` means |
| Segment evaluation | Segment rules change far more often than the app is released |
| A dashboard | Not client-side work |
| Analytics beyond push | Applications already have an analytics product |
| A JSON library | `org.json` and `JSONSerialization` ship with the platforms |
| A background service | Push does not need one, and it costs the user battery |

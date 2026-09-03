# Changelog

All notable changes to the ARY Push SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Version numbers are shared across Android, iOS and Flutter: a single tag `v1.0.0` releases all
three artifacts, so host applications only ever reason about one SDK version.

## [Unreleased]

## [1.0.0] - 2026-09-03

### Added

**Android SDK** (`com.ary:ary-push`)

- `ARYPush` facade: initialize, permission, token, installation, user, tags, topics, events.
- Thread-safe, idempotent, crash-safe initialization; optional AndroidX Startup auto-initialization.
- `ARYPushFirebaseMessagingService` plus the `ARYPushMessaging` host bridge for applications
  that already own a `FirebaseMessagingService`.
- Notification rendering with channel management, big-text and big-picture styles, action buttons
  and an Android 12+ safe activity trampoline for click handling.
- Terminated-state notification-open recovery through persisted pending-open state.

**iOS SDK** (`ARYPush` Swift package)

- Non-destructive `UNUserNotificationCenterDelegate` and `UIApplicationDelegate` proxying.
- APNs registration, APNs to FCM token reconciliation, permission state mapping.
- Cold-launch initial-notification recovery.

**Flutter plugin** (`ary_push`)

- Thin `MethodChannel` and `EventChannel` bridge over the native SDKs; no second engine.
- Native-side event queue so terminated-state opens and early token refreshes survive until Dart
  listeners attach; hot-restart safe.

**Shared core**

- `PushBackend` abstraction with `RestPushBackend` and `NoopPushBackend` implementations.
- Production REST client: GET, POST, PUT, PATCH, DELETE, timeouts, request IDs, cancellation.
- `SyncManager` with a durable offline queue, dependency ordering, batching and debounce,
  exponential backoff with jitter, `Retry-After` support and single-retry 401 refresh.
- Bounded LRU deduplication cache.
- Namespaced isolated storage and a masking logger.
- Full documentation set, sample host applications, unit tests and CI workflows.

[Unreleased]: https://github.com/ary/ary-push-sdk/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/ary/ary-push-sdk/releases/tag/v1.0.0

# Changelog

All notable changes to the ARY Push SDK are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Version numbers are shared across Android, iOS and Flutter: a single tag such as `v1.1.0` releases all
three artifacts, so host applications only ever reason about one SDK version.

## [1.1.0] - 2026-09-21

### Changed — backend contract (breaking for the server)

The SDK now talks to exactly five project-scoped endpoints. Every request carries
`Authorization: Bearer <authToken>` and `?projectId=<projectId>`.

| Operation | Request |
| --- | --- |
| Register Device | `POST /api/notifications/devices/register` |
| Device Token Update | `PUT /api/notifications/devices/update` |
| Subscribe / Unsubscribe | `PUT /api/notifications/devices/toggle` |
| Add Segment Subscriber | `POST /api/segments/{segmentId}/subscribers` |
| Segment List | `GET /api/segments/list` |

These replace the previous `/v1/installations/...` and `/v1/events` endpoints, which the SDK no
longer calls. User identity, tags, topics and events have no endpoint in this contract: they keep
working on the device and are no longer sent to the server. On iOS this means topic subscriptions
no longer affect delivery, since APNs has no topics and the backend is not told about them.

The full contract is in `docs/ARYPush-Technical-Specification.md`, with a matching Postman
collection in `postman/`.

### Added

- **Automatic link handling on notification tap.** A payload carrying `url`, `deep_link` or
  `link` is now opened by the SDK itself, in the foreground, the background and from terminated,
  on Android and iOS. The first key present wins, in that order. Android tries the host
  application before any other handler, so a link the app declares a filter for opens in the app
  with no chooser; anything else goes wherever the system sends it. Only a tap on the notification
  body opens it — action buttons stay host-handled. `onNotificationOpened` is unchanged and still
  fires, and the value is exposed as `PushNotification.launchUrl` (`launchURL` on iOS) for hosts
  that would rather route it themselves.
- **`projectId` and `authToken`** on `PushBackendConfig`, on Android, iOS and Flutter. The token is
  masked whenever a configuration is printed, and is deliberately not read from `Info.plist`.
  When an `AuthProvider` is also configured it wins; the static token is the fallback.
- **`com.ary.push.project_id`** manifest meta-data on Android, matching iOS's `ProjectId` in
  `Info.plist`. Neither reads the bearer token, which would ship in the app as plain text.
- **`subscribeToSegment(segmentId)`** on all three platforms. A direct call, not queued, that
  reports whether the server accepted it.
- **`setFCMToken(token)`** in Flutter, so an app that sends to iOS through Firebase can supply the
  FCM registration token. Accepted and ignored on Android.
- `aryPush.sdkVersion`: the version devices report is now a plain semantic version, independent of
  the Maven version, so a branch or commit build no longer reports `main-SNAPSHOT` or a hash.
- **Flutter integration reduced to a pubspec entry.** The plugin declares the Maven repository on
  every project in the host build and names the SDK coordinate itself; its podspec vendors the
  Swift SDK into its own pod.
- `scripts/set_repository.sh` to point the whole repository at a different GitHub home in one pass.

### Changed

- **`getSegments()`** now lists the project's segments rather than this installation's membership.
- **Default Android coordinate** is `com.github.arysoftware:ary-push-sdk:main-SNAPSHOT`, which
  works before any release is tagged. Pin a tag for production.
- **Distribution is JitPack**, from the now-public repository. No token or account is needed on
  any platform; GitHub Packages, which requires a token even for public repositories, is removed.

### Deprecated

- **`isInSegment(name)`** on all three platforms. With no membership read it can only report
  whether a segment of that name exists in the project.

### Fixed

- **iOS segment lookups never worked.** The request path was a string literal missing its
  interpolation backslashes, so every iOS lookup requested a URL containing
  `(Path.installations)` and failed. Replaced along with the rest of the network layer.
- Android devices would have reported `sdkVersion: "main-SNAPSHOT"` to the server.

### Removed

- **`apiVersion`** from `PushBackendConfig` on Android, iOS and Flutter, from the native
  configuration maps, the Android manifest reader (`backend_api_version`) and the iOS
  `Info.plist` reader (`BackendApiVersion`). The base URL is now used exactly as the app passes it
  — e.g. `https://easypanel.host` — with no `/v1` or any other version segment added. Callers that
  passed `apiVersion` must drop the argument.
- Documentation for the retired API: `REST_API.md`, `BACKEND.md`, `BACKEND_IMPLEMENTATION.md`, and
  the overlapping `QUICK_START.md` and `INTEGRATION.md`, all superseded by the specification.

## [1.0.0] - 2026-09-03

### Added

**Android SDK** (`com.github.arysoftware:ary-push-sdk`)

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

[Unreleased]: https://github.com/arysoftware/ary-push-sdk/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/arysoftware/ary-push-sdk/releases/tag/v1.0.0

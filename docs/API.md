# API reference

The same capabilities on all three platforms, in each one's idiom.

## Initialization

| | |
| --- | --- |
| Android | `ARYPush.initialize(context)` / `initialize(context, config)` |
| iOS | `ARYPush.initialize()` / `initialize(config)` |
| Flutter | `await ARYPush.initialize()` / `initialize(config)` |

Idempotent, thread-safe, crash-safe and non-blocking. Repeated calls reuse the existing instance;
a configuration passed later reconfigures in place. `isInitialized` reports the state.

## Permission

| | |
| --- | --- |
| Android | `requestPermission { status -> }`, `getPermissionStatus()`, `openNotificationSettings(context)` |
| iOS | `requestPermission { status in }`, `getPermissionStatus { }` or `await`, `openNotificationSettings()` |
| Flutter | `await requestPermission()`, `await getPermissionStatus()`, `await openNotificationSettings()` |

`PushPermissionStatus`: `notDetermined`, `granted`, `denied`, `provisional`, `ephemeral`,
`restricted`. `isAuthorized` covers `granted`, `provisional` and `ephemeral`.

Android only ever reports the first three. The rest exist so the model describes iOS too and host
code stays portable.

## Identity and token

| | |
| --- | --- |
| Android | `getInstallationId()`, `getPushToken()` (suspend or callback), `addTokenRefreshListener(l)` |
| iOS | `getInstallationId()`, `getPushToken()`, `getAPNsToken()`, `getPushProvider()`, `addTokenRefreshListener { }` |
| Flutter | `await getInstallationId()`, `await getPushToken()`, `await getPushProvider()`, `onTokenRefresh` |

The installation id is stable across token refreshes, logins and logouts. You never need to send
the token to a server; the SDK does.

## User

| | |
| --- | --- |
| Android | `login(userId)`, `logout()`, `getUserId()` |
| iOS | `login(_:)`, `logout()`, `getUserId()` |
| Flutter | `await login(userId)`, `await logout()`, `await getUserId()` |

Local state changes immediately, so an offline login is true straight away. Logout clears the user
association and nothing else: the installation, the token and the device registration all survive.

## Tags

| | |
| --- | --- |
| Android | `addTag(k, v)`, `addTags(map)`, `removeTag(k)`, `removeTags(set)`, `removeAllTags()`, `getTags()` |
| iOS | same names |
| Flutter | same names, all `Future`s |

Coalesced: consecutive writes become one request. `getTags()` reads local storage, so it is
correct offline and immediately after a write.

## Topics

| | |
| --- | --- |
| Android | `subscribeToTopic(t) { ok -> }`, `unsubscribeFromTopic(t) { ok -> }`, `getSubscribedTopics()` |
| iOS | `subscribeToTopic(_:) -> Bool`, `unsubscribeFromTopic(_:) -> Bool`, `getSubscribedTopics()` |
| Flutter | `await subscribeToTopic(t)`, `await unsubscribeFromTopic(t)`, `await getSubscribedTopics()` |

Names are validated against FCM's grammar (`[a-zA-Z0-9-_.~%]{1,900}`) on both platforms, so an
invalid name fails locally instead of being silently dropped by the server. A leading `/topics/`
is stripped.

## Notification events

| | |
| --- | --- |
| Android | `addNotificationReceivedListener(l)`, `addNotificationOpenedListener(l)`, matching `remove*`, `consumeInitialNotification()` |
| iOS | same, returning a `UUID` to pass to `remove*` |
| Flutter | `onNotificationReceived`, `onNotificationOpened`, `getInitialNotification()` |

Callbacks run on the main thread. A terminated-state open is persisted and replayed to the first
listener that attaches.

## Events and maintenance

| | |
| --- | --- |
| Android | `trackEvent(name, properties)`, `flush()` |
| iOS | `trackEvent(_:properties:)`, `flush()` |
| Flutter | `await trackEvent(name, properties)`, `await flush()` |

`flush()` sends debounced tag writes and drains the queue immediately.

---

## `PushNotification`

| Field | Type | Notes |
| --- | --- | --- |
| `id` | String | Stable identity, used for deduplication |
| `title`, `body` | String? | |
| `imageUrl` | String? | |
| `data` | Map | Delivered verbatim; the SDK never interprets it |
| `receivedAt` | timestamp | |
| `actionId` | String? | Set on opens when an action button was used |
| `wasForeground` | Bool | |
| `channelId` | String? | Android |
| `collapseKey` | String? | Android |
| `threadId`, `categoryId` | String? | iOS |

`action` is a convenience accessor for `data["action"]`.

## `ARYPushConfig`

| Option | Default | Effect |
| --- | --- | --- |
| `enableLogging` | `false` | Emit SDK logs |
| `logLevel` | `info` | Minimum level |
| `autoRequestPermission` | `false` | Prompt during initialization |
| `foregroundDisplay` | `show` | `show`, `eventOnly`, `suppress` |
| `displayNotifications` | `true` | Android: whether the SDK renders at all |
| `defaultChannelId` / `Name` | SDK default | Android channel |
| `smallIconResId`, `accentColor` | app icon, none | Android appearance |
| `backend` | none | `PushBackendConfig`; omit to run server-less |
| `network`, `retry` | sensible | Timeouts and backoff |
| `authProvider` | none | Supplies the host's access token |
| `customBackend` | none | Replaces the REST backend entirely |
| `collectDeviceInfo` | `true` | Send model, OS, locale, timezone |
| `deduplicationCacheSize` | 200 | Bounded by design |
| `tagSyncDebounce` | 750 ms | Coalescing window |
| `proxyNotificationCenterDelegate` | `true` | iOS delegate forwarding |
| `proxyApplicationDelegate` | `true` | iOS remote-notification observation |

## Extension points

**`AuthProvider`** supplies the host's access token. `getAccessToken()` per request;
`refreshAccessToken()` once on a 401.

**`PushBackend`** replaces the wire protocol entirely, without touching the notification engine.
Implement it and pass it as `customBackend` for a bespoke transport, a gateway SDK, or a fake in
tests.

**`RestClient`** replaces only the HTTP transport, keeping the ARY push API contract.

## Calling before initialization

Every public API is safe to call before `initialize()`. The call is logged and ignored, and
readers return a null or empty value. A push SDK must never be the reason an application crashes.

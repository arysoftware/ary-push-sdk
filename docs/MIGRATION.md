# Migration

Moving an application that already handles push onto this SDK. The theme throughout: **you
probably have to remove less than you think.**

## What the SDK takes over

| | |
| --- | --- |
| Notification permission | Prompt, status, the Android 13 split |
| Push token | Fetch, refresh, replacement, invalidation |
| Token upload to a server | Automatic, durable, retried |
| Installation identity | A stable id independent of token and user |
| Notification display | Channels, styles, actions, images |
| Notification taps | Including the terminated-state case |
| Deduplication | Bounded, persistent |
| Offline queue and retries | Durable, ordered, jittered backoff |

## What stays yours

| | |
| --- | --- |
| Navigation and routing | The SDK never navigates |
| Your own analytics | The SDK tracks push events only |
| Your own non-push messages | `handleMessage` returns false for them |
| Firebase configuration | Yours, per application |
| Your own backend calls | Untouched |
| Your notification icons and colours | Configured, not replaced |

---

## From `firebase_messaging` (Flutter)

### Keep

`firebase_core`, `firebase_messaging`, `Firebase.initializeApp()`, and your background handler.
The SDK coexists with all of them; see [FIREBASE.md](FIREBASE.md).

### Replace

```dart
// Before
final token = await FirebaseMessaging.instance.getToken();
await myApi.registerToken(token);
FirebaseMessaging.instance.onTokenRefresh.listen(myApi.registerToken);

// After: delete it. The SDK registers the token and every refresh.
```

```dart
// Before
FirebaseMessaging.onMessageOpenedApp.listen(_route);
final initial = await FirebaseMessaging.instance.getInitialMessage();
if (initial != null) _route(initial);

// After: one stream, and the terminated case is included.
ARYPush.onNotificationOpened.listen(_route);
```

```dart
// Before
await FirebaseMessaging.instance.requestPermission();

// After
await ARYPush.requestPermission();
```

### Then decide about foreground display

If you show your own in-app banner from `FirebaseMessaging.onMessage`, set
`foregroundDisplay: ForegroundDisplayPolicy.eventOnly` or the user sees each message twice.

### Route from one place

`FirebaseMessaging.onMessageOpenedApp` and `ARYPush.onNotificationOpened` both fire for a
message the SDK recognises. Pick one, or your user gets navigated twice.

---

## From `flutter_local_notifications`

Keep it if you also show **local** notifications: scheduled reminders, in-app alerts, anything
not triggered by a push. The SDK does not do local notifications and does not want to.

Remove only the part that renders **incoming push messages**, which is what now duplicates.

```dart
// Before
FirebaseMessaging.onMessage.listen((message) {
  flutterLocalNotificationsPlugin.show(id, title, body, details);
});

// After: the SDK renders it. Keep flutterLocalNotificationsPlugin for your own local
// notifications only.
```

Your channel definitions can stay: point the SDK at one with `defaultChannelId`.

---

## From a custom `FirebaseMessagingService` (Android)

**Keep your service.** This is the documented path, not a workaround; see
[FIREBASE.md](FIREBASE.md#existing-firebasemessagingservice).

```kotlin
class AppMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (ARYPushMessaging.handleMessage(this, message)) return
        handleYourOwnMessage(message)          // unchanged
    }

    override fun onNewToken(token: String) {
        ARYPushMessaging.handleNewToken(this, token)
        registerTokenWithYourBackend(token)    // keep during a phased migration
    }
}
```

Plus one manifest line removing the SDK's service.

**Remove** your rendering, channel creation and click `PendingIntent` **for messages the SDK
handles**. Everything for your own messages stays.

**Phasing.** Running both token registrations for a release is fine and is the safe way to
migrate: compare the two backends' records, then delete yours once they agree.

---

## From a custom `UNUserNotificationCenterDelegate` (iOS)

**Keep your delegate.** The SDK wraps it and forwards every callback. Your deep links, analytics
and action handling keep working with no changes; see [IOS.md](IOS.md#delegate-safety).

Order does not matter: install yours before or after `ARYPush.initialize()`, and the SDK
catches it either way.

**Remove** only your own token upload, once you trust the SDK's.

```swift
func application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
) {
    // Remove after the migration:
    // myApi.registerToken(deviceToken.hexString)

    // Nothing to add. The SDK observes this callback already.
}
```

---

## From a custom notification-click handler

The most common thing to delete, and the most common thing to get wrong when writing it yourself.

```kotlin
// Before: a PendingIntent to a routing Activity, extras carried by hand, a lost tap whenever
// the app was terminated.

// After
ARYPush.addNotificationOpenedListener { notification ->
    router.handle(notification.data)
}
```

The terminated-state case is the reason to switch: the SDK persists an open that arrives before
any listener exists and replays it. Hand-written handlers usually drop it, and the symptom is a
user tapping an order notification and landing on the home screen.

---

## Migration checklist

1. Add the dependency and `initialize()`. Change nothing else. Ship to internal testers.
2. Confirm both your existing pipeline and the SDK see messages, and the user sees one
   notification.
3. Move tap routing to `onNotificationOpened`. Delete the old click handling.
4. Set `foregroundDisplay` to match what you already show.
5. Add `login()` and `logout()` at your existing session boundaries.
6. Add tags where you were previously sending user attributes to your own push service.
7. Once the backend records agree, delete your own token registration.
8. Delete your rendering code for messages the SDK now handles.

Nothing in this list requires a rewrite, and every step is independently shippable.

## Things that will bite you

| Symptom | Cause |
| --- | --- |
| Two notifications per message | Your rendering plus the SDK's. Set `foregroundDisplay` or remove yours |
| Navigated twice on tap | Routing from both the old handler and `onNotificationOpened` |
| Notifications stop after adding the SDK | Two `FirebaseMessagingService` declarations. Remove the SDK's and forward |
| Tap does nothing from terminated | Listener attached too late; move it to `Application.onCreate` or before `runApp` |
| Token differs from your old one | On iOS these are genuinely different values. See [FIREBASE.md](FIREBASE.md) |

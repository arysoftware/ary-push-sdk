# Quick start

**How do I add this SDK to my existing application in under five minutes?**

That is the question this page answers. Pick your platform, do the three steps, done.

Every step below assumes an application that already exists, already has its own Firebase or
APNs configuration, and possibly already handles notifications. None of that has to be removed.

---

## Android

### 1. Add the dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
implementation("com.github.arysoftware:ary-push-sdk:main-SNAPSHOT")
```

### 2. Initialize

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ARYPush.initialize(this)
    }
}
```

### 3. Handle taps

```kotlin
ARYPush.addNotificationOpenedListener { notification ->
    when (notification.action) {
        "open_order" -> router.openOrder(notification.data["orderId"])
        else -> router.openHome()
    }
}
```

Register this in `Application.onCreate`, not in an Activity: a tap that cold-started the process
is replayed to the first listener that attaches, and an Activity may not exist yet.

**That is the whole integration.** The messaging service, the notification permission, the click
trampoline and the manifest entries all arrive through manifest merging.

> Already have a `FirebaseMessagingService`? Two extra lines, in
> [FIREBASE.md](FIREBASE.md#existing-firebasemessagingservice).

---

## iOS

### 1. Add the package

Xcode: **File > Add Package Dependencies**, then
`https://github.com/arysoftware/ary-push-sdk`. No GitHub account needed — the repository is
public. Pin to `Up to Next Major Version` from `1.0.0` once that tag exists, or to `main` before
then.

Enable **Push Notifications** under Signing & Capabilities. For silent messages, also enable
**Background Modes > Remote notifications**.

### 2. Initialize

```swift
import ARYPush

func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {
    ARYPush.initialize()
    return true
}
```

### 3. Handle taps

```swift
ARYPush.addNotificationOpenedListener { notification in
    switch notification.action {
    case "open_order": router.openOrder(notification.data["orderId"])
    default: router.openHome()
    }
}
```

**That is the whole integration.** APNs registration, the device token and the notification
delegate are handled for you, and an existing `UNUserNotificationCenterDelegate` keeps working:
the SDK forwards to it rather than replacing it. See [IOS.md](IOS.md#delegate-safety).

---

## Flutter

### 1. Add the dependency

```yaml
dependencies:
  ary_push:
    git:
      url: https://github.com/arysoftware/ary-push-sdk.git
      path: flutter
```

### 2. Initialize

```dart
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await ARYPush.initialize();
  runApp(const MyApp());
}
```

### 3. Handle taps

```dart
ARYPush.onNotificationOpened.listen((notification) {
  if (notification.action == 'open_order') {
    navigatorKey.currentState?.pushNamed('/order', arguments: notification.data['orderId']);
  }
});
```

Subscribe before `runApp` so a terminated-state tap is replayed to you.

---

## Then, when you need them

```kotlin
ARYPush.requestPermission { status -> /* ... */ }   // at a moment the user understands
ARYPush.login("USER_123")                            // after your own sign-in
ARYPush.addTags(mapOf("subscription" to "premium"))  // attributes the backend segments on
ARYPush.subscribeToTopic("sports")
ARYPush.logout()                                     // clears the user, keeps the device
```

The same calls exist on all three platforms. See [API.md](API.md).

### Pointing at a backend

Everything above works with no server at all. To synchronise installations, users and tags:

```kotlin
ARYPush.initialize(
    this,
    ARYPushConfig(
        backend = PushBackendConfig(
            baseUrl = "https://push-api.ary.com",
            applicationId = "wallet_android"
        )
    )
)
```

`applicationId` is a public label, not a credential. See [SECURITY.md](SECURITY.md).

---

## What you do not have to do

| Not your job | Why |
| --- | --- |
| Request `POST_NOTIFICATIONS` yourself | The SDK hosts the prompt; no `ActivityResultLauncher` needed |
| Implement `UNUserNotificationCenterDelegate` | The SDK proxies it and forwards to yours |
| Send the push token to a server | The SDK registers and re-registers it |
| Handle token refresh | `TokenManager` does, durably |
| Create a notification channel | Created on first use, overridable |
| Deduplicate messages | Bounded persistent cache |
| Retry failed registrations | Durable offline queue with jittered backoff |
| Recover a terminated-state tap | Persisted and replayed |

## Next

- Integrating as a local module, before the SDK is published? [INTEGRATION.md](INTEGRATION.md)
- Something not working? [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- Coming from `firebase_messaging` or your own code? [MIGRATION.md](MIGRATION.md)
- Want to know what actually happens? [NOTIFICATION_LIFECYCLE.md](NOTIFICATION_LIFECYCLE.md)

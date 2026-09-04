# Android integration

## Dependency

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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

No credentials: the repository is public and JitPack builds it on demand. The version is a git
reference — `main-SNAPSHOT` for the tip of `main`, or a tag such as `v1.0.0` to pin a release,
which is what a shipping application should do.

## Initialization

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ARYPush.initialize(this)
    }
}
```

With configuration:

```kotlin
ARYPush.initialize(
    this,
    ARYPushConfig(
        enableLogging = BuildConfig.DEBUG,
        logLevel = PushLogLevel.DEBUG,
        defaultChannelId = "general",
        defaultChannelName = "General",
        smallIconResId = R.drawable.ic_notification,
        accentColor = ContextCompat.getColor(this, R.color.brand),
        foregroundDisplay = ForegroundDisplayPolicy.SHOW,
        backend = PushBackendConfig(
            baseUrl = BuildConfig.PUSH_API_URL,
            applicationId = "wallet_android",
        ),
    ),
)
```

Initialization is **thread-safe, idempotent, crash-safe and fast**. Calling it repeatedly reuses
the same instance. Calling it with a new configuration after the SDK is already running
reconfigures in place, which is what lets automatic initialization start the SDK and your code
supply the environment later. Nothing blocking happens on the calling thread, and a failure
during initialization is logged rather than thrown: the SDK degrades, the app still launches.

Only `applicationContext` is retained. No Activity reference is ever held.

## Configuring from the manifest

Everything above can also be declared as `meta-data`, which is useful when the SDK is dropped
into a large app whose startup path is contested, and necessary when a background message starts
the process before any host code runs.

```xml
<application>
    <meta-data android:name="com.ary.push.auto_init" android:value="true" />
    <meta-data android:name="com.ary.push.logging_enabled" android:value="false" />
    <meta-data android:name="com.ary.push.log_level" android:value="INFO" />
    <meta-data android:name="com.ary.push.default_channel_id" android:value="general" />
    <meta-data android:name="com.ary.push.default_channel_name" android:value="General" />
    <meta-data android:name="com.ary.push.notification_icon" android:resource="@drawable/ic_notification" />
    <meta-data android:name="com.ary.push.notification_color" android:resource="@color/brand" />
    <meta-data android:name="com.ary.push.backend_base_url" android:value="https://push-api.ary.com" />
    <meta-data android:name="com.ary.push.application_id" android:value="wallet_android" />
    <meta-data android:name="com.ary.push.foreground_display" android:value="SHOW" />
    <meta-data android:name="com.ary.push.display_notifications" android:value="true" />
    <meta-data android:name="com.ary.push.auto_request_permission" android:value="false" />
    <meta-data android:name="com.ary.push.collect_device_info" android:value="true" />
</application>
```

Values passed to `initialize(context, config)` always win over these.

### Automatic initialization

`com.ary.push.auto_init` is **off by default**. Turning it on lets AndroidX Startup
initialize the SDK during application creation, saving one line of code.

It is opt-in because the cost is running SDK initialization at a point in startup you do not
control, and reliability is worth more than that line. Explicit `initialize()` is always
supported and always wins.

Note that this is separate from the SDK working in a cold-started process: a background message
or a notification tap initializes the SDK regardless of this setting, from manifest
configuration, because those paths cannot assume host code has run.

## What arrives through manifest merging

Nothing below needs copying into your manifest. It merges automatically.

| Entry | Why |
| --- | --- |
| `INTERNET` | Backend synchronisation |
| `ACCESS_NETWORK_STATE` | Defer queued work until connectivity exists |
| `POST_NOTIFICATIONS` | Android 13+ runtime permission |
| `ARYPushFirebaseMessagingService` | Receives FCM messages and token refreshes |
| `NotificationOpenActivity` | Android 12+ safe click trampoline; invisible, `noHistory`, excluded from recents |
| `PermissionRequestActivity` | Hosts the permission prompt so you need no `ActivityResultLauncher` |
| `InitializationProvider` meta-data | AndroidX Startup entry, inert unless `auto_init` is true |

## Permission

```kotlin
ARYPush.requestPermission { status ->
    when {
        status.isAuthorized -> enableNotificationFeatures()
        status == PushPermissionStatus.DENIED -> ARYPush.openNotificationSettings(this)
        else -> Unit
    }
}
```

No `ActivityResultLauncher`, no `onRequestPermissionsResult` override: the SDK brings its own
invisible Activity to host the prompt. The callback runs on the main thread exactly once,
whatever the user does, including dismissing the prompt.

`getPermissionStatus()` reflects **both** the runtime permission and the application-level
notification toggle, so `DENIED` genuinely means nothing will be shown. On Android 12 and below
there is no runtime permission and the toggle is the whole story.

`autoRequestPermission` is off by default: prompting on first launch, out of context, is the most
common cause of a permanent denial.

## Notification icons

Supply a white-on-transparent silhouette via `smallIconResId`. Without one the SDK falls back to
your launcher icon, which Android renders as a grey square if it is not a silhouette. This is the
most common "why does my notification look broken" report, and it is not something the SDK can
fix for you.

## ProGuard and R8

Nothing to add. The SDK ships `consumer-rules.pro`, which keeps the public API, the components
the framework resolves by name from the merged manifest, and silences OkHttp's optional-dependency
warnings.

## Testing without a backend

Omit `backend` entirely. The SDK runs against `NoopPushBackend`: every push feature works, the
sync queue stays empty and no network call is made. Then send a test message from the Firebase
console with `notification_id` in the data payload so deduplication has something stable to work
with.

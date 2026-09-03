# Integrating the ARY Push SDK as a local module

Complete, copy-pasteable steps for adding the SDK to an existing **Android**, **iOS** or
**Flutter** application while the SDK still lives in a folder on disk, before it is published to
Git or a Maven repository.

Every section ends with how to switch to a published artifact later. That switch is one or two
lines and touches nothing else, so nothing you do now has to be undone.

## What you are integrating

| Platform | Module | You reference | Public entry point |
| --- | --- | --- | --- |
| Android | `android/sdk` | A Gradle module | `com.ary.push.ARYPush` |
| iOS | `ios/` | A Swift package or a CocoaPods pod | `ARYPush` |
| Flutter | `flutter/` | A path dependency | `ARYPush` from `package:ary_push/ary_push.dart` |

Throughout, `SDK_ROOT` means wherever you put this repository, for example
`D:\Underdev\Push Client`. Put it somewhere stable, next to your app rather than inside it:

```
projects/
  ary-push-sdk/      <- SDK_ROOT, this repository
  wallet-android/    <- your app
  wallet-ios/
  wallet-flutter/
```

## Before you start

| Requirement | Android | iOS | Flutter |
| --- | --- | --- | --- |
| JDK 17 or newer | yes | | yes |
| Android Gradle Plugin 9.0.1, Kotlin 2.3.20, Gradle 9.1 | yes | | yes |
| `compileSdk` 36, `minSdk` 21 or higher | yes | | yes |
| Xcode 15, iOS 13 deployment target | | yes | yes |
| Flutter 3.44 or newer | | | yes |
| Your own Firebase project and `google-services.json` | yes | optional | yes |
| Your own APNs key or certificate | | yes | yes |

The SDK ships **no** Firebase configuration, no APNs key and no credentials of any kind. Each
application supplies its own. See [SECURITY.md](SECURITY.md).

---

# Part 1: Android application

## Step 1. Point your build at the module

`settings.gradle.kts` in your app:

```kotlin
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("../ary-push-sdk/android/sdk")
```

That is the whole wiring. The module is self-contained: it declares its own dependency versions
and requests its Gradle plugins without a version, so it does not need a version catalog from
you and cannot collide with the `libs` catalog you may already have.

Two things your build must already provide, which almost every Android build does:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
```

And the Android and Kotlin plugins on the classpath, declared once at the root:

```kotlin
// build.gradle.kts (root)
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
```

> If your app declares AGP in `settings.gradle.kts` instead (the Flutter style), that works too.
> The SDK module takes whatever is on the classpath.

## Step 2. Depend on it

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":ary-push-sdk"))
}
```

Your `minSdk` must be 21 or higher. Nothing else in your Gradle files changes: the SDK's
`AndroidManifest.xml` is merged into yours automatically, bringing the FCM service, the
notification-tap trampoline, the permission-prompt host and the `INTERNET`,
`ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS` permissions. You do not copy any of it.

## Step 3. Add your Firebase configuration

The SDK uses FCM as its transport on Android but does not own your Firebase project.

1. In the [Firebase console](https://console.firebase.google.com), add an Android app whose
   package name matches your `applicationId`.
2. Download `google-services.json` into `app/`.
3. Apply the plugin:

```kotlin
// build.gradle.kts (root)
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}
```

```kotlin
// app/build.gradle.kts
plugins {
    id("com.google.gms.google-services")
}
```

Skip this step and the app still builds and runs; no push token is issued and the SDK logs one
clear line explaining why.

## Step 4. Initialize

```kotlin
// App.kt
import android.app.Application
import com.ary.push.ARYPush
import com.ary.push.ARYPushConfig
import com.ary.push.PushBackendConfig
import com.ary.push.PushLogLevel

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        ARYPush.initialize(
            this,
            ARYPushConfig(
                enableLogging = BuildConfig.DEBUG,
                logLevel = PushLogLevel.DEBUG,
                smallIconResId = R.drawable.ic_notification,
                // Omit `backend` entirely to run with no server at all.
                backend = PushBackendConfig(
                    baseUrl = BuildConfig.PUSH_API_URL,
                    applicationId = "wallet_android",
                ),
            ),
        )

        // Attach here, not in an Activity. A tap that cold-started the process is replayed to
        // the first listener that attaches, and an Activity may not exist yet.
        ARYPush.addNotificationOpenedListener { notification ->
            Router.handle(this, notification.data)
        }
    }
}
```

Register the class in your manifest if it is new:

```xml
<application android:name=".App" ... >
```

`initialize` is thread-safe, idempotent, non-blocking and crash-safe. Calling it twice reuses the
same instance.

## Step 5. Ask for permission, when it makes sense

```kotlin
ARYPush.requestPermission { status ->
    if (!status.isAuthorized && status == PushPermissionStatus.DENIED) {
        ARYPush.openNotificationSettings(this)
    }
}
```

No `ActivityResultLauncher`, no `onRequestPermissionsResult` override: the SDK hosts the prompt.
Call it at a moment the user understands, not on first launch.

## Step 6. Verify

1. Run the app and filter logcat for `ARYPush`. You should see `Initialization started`,
   `Installation ID loaded`, `Permission status`, and `Push token received`.
2. Send a test message from the Firebase console with a `notification_id` in the data payload.
3. Tap it and confirm your `addNotificationOpenedListener` fires with the payload.

## Later: switching to the published artifact

Delete the two lines from `settings.gradle.kts`, add the private repository, and change the
dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri(providers.gradleProperty("aryMavenUrl").get()) }
    }
}
```

```kotlin
// app/build.gradle.kts
implementation("com.ary:ary-push:1.0.0")
```

No application code changes.

---

# Part 2: iOS application

Two ways in. Use Swift Package Manager unless your project is already CocoaPods-based.

## Option A: local Swift package (recommended)

1. In Xcode: **File > Add Package Dependencies…**
2. Click **Add Local…**
3. Select `SDK_ROOT/ios` (the folder containing `Package.swift`).
4. Add the `ARYPush` library product to your app target.

Xcode records a relative path, so the package moves with your workspace.

## Option B: local CocoaPods pod

`Podfile`:

```ruby
platform :ios, '13.0'

target 'Runner' do
  use_frameworks!
  pod 'ARYPush', :path => '../../ary-push-sdk/ios'
end
```

```bash
pod install
```

Open the `.xcworkspace` from now on, not the `.xcodeproj`.

## Step 2. Enable the capabilities

Target > **Signing & Capabilities** > **+ Capability**:

- **Push Notifications** — adds the `aps-environment` entitlement. Without it, APNs registration
  fails and no token is issued.
- **Background Modes** > **Remote notifications** — only if you send silent,
  `content-available` messages.

Then upload your APNs authentication key (`.p8`) or certificate to whatever sends your pushes.
The SDK never contains either.

## Step 3. Initialize

```swift
import ARYPush
import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        ARYPush.initialize(
            ARYPushConfig(
                enableLogging: true,
                logLevel: .debug,
                backend: PushBackendConfig(
                    baseURL: "https://push-api.ary.com",
                    applicationId: "wallet_ios"
                )
            )
        )

        // Attach during launch so a tap that started the app from terminated is replayed here.
        ARYPush.addNotificationOpenedListener { notification in
            Router.handle(notification.data)
        }

        return true
    }
}
```

**That is all the app delegate code you write.** You do *not* set
`UNUserNotificationCenter.current().delegate`, you do *not* implement
`didRegisterForRemoteNotificationsWithDeviceToken`, and you do *not* upload the token anywhere.

### If you already have a notification delegate

Keep it. The SDK finds it, wraps it, and forwards every callback, so your existing deep links,
analytics and action handling keep working. Presentation options are the union of yours and the
SDK's, so neither side can silence the other. Nothing to change.

### If your policy forbids runtime method manipulation

Turn the proxying off and forward explicitly. Full code in
[IOS.md](IOS.md#if-your-policy-forbids-runtime-method-manipulation).

## Step 4. Ask for permission, when it makes sense

```swift
ARYPush.requestPermission { status in
    if status == .denied {
        Task { @MainActor in ARYPush.openNotificationSettings() }
    }
}
```

iOS shows this prompt **once per install, ever**. There is no second chance, so do not call it on
first launch. Once granted, the SDK registers with APNs itself.

## Step 5. Verify

1. Run on a **real device**. The Simulator issues no APNs token without a paired Mac.
2. Open Console.app, filter the subsystem `com.ary.push`, and look for
   `Initialization started`, `Installation ID loaded` and `APNs token received`.
3. Send a test push and confirm your opened-listener fires.

## Later: switching to the published package

Swift Package Manager: remove the local package, then **Add Package Dependencies…** with the
private repository URL, pinned to *Up to Next Major Version* from `1.0.0`.

CocoaPods: replace `:path =>` with

```ruby
pod 'ARYPush', :git => 'git@github.com:ary/ary-push-sdk.git', :tag => 'v1.0.0'
```

No application code changes.

---

# Part 3: Flutter application

A Flutter integration is three wirings: the Dart package, the Android native SDK and the iOS
native SDK. The plugin is only a bridge, so the native SDKs still have to be reachable.

## Step 1. The Dart package

`pubspec.yaml`:

```yaml
dependencies:
  ary_push:
    path: ../ary-push-sdk/flutter
```

```bash
flutter pub get
```

## Step 2. The Android native SDK

`android/settings.gradle.kts` in your Flutter app:

```kotlin
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("../../ary-push-sdk/android/sdk")
```

The path is relative to your app's `android/` folder, so it has one more `../` than you might
expect. The plugin looks for a project at exactly `:ary-push-sdk` and uses it when present.

Then your Firebase configuration, exactly as in Part 1: `android/app/google-services.json` plus
the `com.google.gms.google-services` plugin.

## Step 3. The iOS native SDK

`ios/Podfile`:

```ruby
target 'Runner' do
  use_frameworks!
  pod 'ARYPush', :path => '../../ary-push-sdk/ios'
  flutter_install_all_ios_pods File.dirname(File.realpath(__FILE__))
end
```

```bash
cd ios && pod install
```

Then enable **Push Notifications** (and **Background Modes > Remote notifications** if you send
silent messages) in `ios/Runner.xcworkspace`.

## Step 4. Initialize

```dart
import 'package:ary_push/ary_push.dart';
import 'package:flutter/material.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await ARYPush.initialize(
    const ARYPushConfig(
      enableLogging: true,
      logLevel: PushLogLevel.debug,
      backend: PushBackendConfig(
        baseUrl: 'https://push-api.ary.com',
        applicationId: 'wallet_flutter',
      ),
    ),
  );

  runApp(const MyApp());
}
```

`ensureInitialized()` first: the plugin talks over platform channels, which need the binding.

## Step 5. Route taps

```dart
final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

class _MyAppState extends State<MyApp> {
  late final StreamSubscription<PushNotification> _opened;

  @override
  void initState() {
    super.initState();
    _opened = ARYPush.onNotificationOpened.listen((notification) {
      if (notification.action == 'open_order') {
        navigatorKey.currentState?.pushNamed(
          '/order',
          arguments: notification.data['orderId'],
        );
      }
    });
  }

  @override
  void dispose() {
    _opened.cancel();
    super.dispose();
  }
}
```

Subscribe as early as possible. A tap that launched the app from terminated is persisted
natively and replayed to the **first** listener that attaches.

## Step 6. Verify

```bash
flutter run
```

Look for `ARYPush` lines in the console. Then:

```dart
debugPrint(await ARYPush.getInstallationId());
debugPrint(await ARYPush.getPushToken());
```

Send a test push and confirm `onNotificationOpened` fires.

## Later: switching to Git

```yaml
dependencies:
  ary_push:
    git:
      url: git@github.com:ary/ary-push-sdk.git
      ref: v1.0.0
      path: flutter
```

Then delete the `include(":ary-push-sdk")` lines from `android/settings.gradle.kts`, set
`aryMavenUrl` in `android/gradle.properties`, and change the Podfile's `:path =>` to
`:git =>`. No Dart changes.

---

# The rest of the API, identical on all three platforms

Once initialized, the same calls exist everywhere. Kotlin shown; Swift and Dart are the same
names.

```kotlin
ARYPush.login("USER_123")                                  // after your own sign-in
ARYPush.logout()                                           // clears the user, keeps the device
ARYPush.addTags(mapOf("subscription" to "premium"))        // attributes the backend segments on
ARYPush.subscribeToTopic("sports")
ARYPush.trackEvent("promo_seen", mapOf("id" to "spring"))
ARYPush.getInstallationId()
ARYPush.getPushToken()
```

Full reference: [API.md](API.md).

## Connecting a backend

Everything above works with no server. To synchronise installations, users and tags, set
`backend`:

```kotlin
PushBackendConfig(
    baseUrl = "https://push-api.ary.com",
    applicationId = "wallet_android",
)
```

`applicationId` is a **public label**, not a credential. If your API requires authentication,
implement `AuthProvider` and pass it in the config; the SDK asks for a token per request and
refreshes once on a 401. The contract every endpoint must satisfy is in
[REST_API.md](REST_API.md).

## What you never have to do

| Not your job | Why |
| --- | --- |
| Request `POST_NOTIFICATIONS` yourself | The SDK hosts the prompt |
| Implement `UNUserNotificationCenterDelegate` | The SDK proxies it and forwards to yours |
| Send the push token to a server | Registered and re-registered automatically |
| Handle token refresh | Durably, through the offline queue |
| Create a notification channel | Created on first use, overridable |
| Deduplicate messages | Bounded persistent cache |
| Retry failed registrations | Jittered exponential backoff |
| Recover a terminated-state tap | Persisted and replayed |

---

# If something goes wrong

| Symptom | Most likely cause |
| --- | --- |
| `Could not find com.ary:ary-push` | The `include(":ary-push-sdk")` lines are missing or the path is wrong. The SDK prints the fix in the build log |
| `plugin is already on the classpath with an unknown version` | You added a `version` to the SDK module's plugins block. It must stay version-less |
| `Firebase is not initialized` | No `google-services.json`, or the `google-services` plugin is not applied |
| `APNs registration failed` | Simulator without a paired Mac, or the Push Notifications capability is missing |
| No token, no error | It is fetched asynchronously; use `addTokenRefreshListener` rather than reading it immediately after `initialize` |
| Notifications never appear | Work down the checklist in [TROUBLESHOOTING.md](TROUBLESHOOTING.md#notifications-do-not-appear) |
| Two notifications per message | Your app renders foreground messages too. Set `foregroundDisplay = EVENT_ONLY` |
| Tap does nothing from terminated | Listener attached too late. Move it to `Application.onCreate`, `didFinishLaunching`, or before `runApp` |

Turn logging on first — it answers most of these:

```kotlin
ARYPushConfig(enableLogging = true, logLevel = PushLogLevel.DEBUG)
```

Then filter logcat for `ARYPush`, or the `com.ary.push` subsystem in Console.app.

# Where to go next

| Document | For |
| --- | --- |
| [QUICK_START.md](QUICK_START.md) | The five-minute version of this page |
| [ANDROID.md](ANDROID.md) | Manifest entries, channels, icons, manifest-based configuration |
| [IOS.md](IOS.md) | Delegate proxying, APNs, the FCM-on-iOS token distinction |
| [FLUTTER.md](FLUTTER.md) | Event streams, engine lifecycle, hot restart |
| [FIREBASE.md](FIREBASE.md) | Keeping an existing `FirebaseMessagingService` or Firebase Messaging setup |
| [MIGRATION.md](MIGRATION.md) | Moving off `firebase_messaging` or your own notification code |
| [NOTIFICATION_LIFECYCLE.md](NOTIFICATION_LIFECYCLE.md) | What actually happens in foreground, background and terminated |
| [SECURITY.md](SECURITY.md) | Exactly what is collected, and what never leaves the device |

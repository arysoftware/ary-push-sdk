# Integrating the ARY Push SDK

Everything is fetched from `github.com/arysoftware/ary-push-sdk`. No folder paths, no copying,
no submodules.

| Platform | How you add it | One-time setup |
| --- | --- | --- |
| **Android** | JitPack, from a tag | Add the JitPack repository |
| **iOS** | Swift Package Manager, from the URL | None |
| **Flutter** | `pubspec.yaml` | **None at all** |

Flutter is the shortest because the plugin carries the native SDKs with it: the Android side
pulls the AAR from JitPack itself, and the iOS side vendors the Swift sources into its own pod.
An app adds one dependency and writes no build configuration.

## First, a release has to exist

JitPack and Swift Package Manager both build from a Git tag. Nothing resolves until one is
pushed:

```bash
git tag -a v1.0.0 -m "ARY Push SDK v1.0.0"
git push origin v1.0.0
```

Then open `https://jitpack.io/#arysoftware/ary-push-sdk` once and click **Get it** on the tag.
That triggers the first build; later tags build on demand.

> **If the repository is private**, JitPack needs a paid plan and an auth token, and every
> consuming project needs that token too. If you would rather not, use the GitHub Packages route
> in [Android, alternative](#alternative-github-packages) — it authenticates with an ordinary
> `read:packages` token. Public repository plus JitPack is the only genuinely zero-configuration
> combination.

## Before you start

| Requirement | Android | iOS | Flutter |
| --- | --- | --- | --- |
| JDK 17 or newer | yes | | yes |
| AGP 9.0.1, Kotlin 2.3.20, Gradle 9.1 | yes | | yes |
| `compileSdk` 36, `minSdk` 21 or higher | yes | | yes |
| Xcode 15, iOS 13 deployment target | | yes | yes |
| Flutter 3.44 or newer | | | yes |
| Your own Firebase project and `google-services.json` | yes | optional | yes |
| Your own APNs key or certificate | | yes | yes |

The SDK ships **no** Firebase configuration, no APNs key and no credentials. Each application
supplies its own. See [SECURITY.md](SECURITY.md).

---

# Android

## Step 1. Add JitPack and the dependency

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.arysoftware:ary-push-sdk:v1.0.0")
}
```

JitPack builds the AAR from the tag the first time anyone asks for it, then caches it.

Your `minSdk` must be 21 or higher. Nothing else in your Gradle files changes: the SDK's
`AndroidManifest.xml` merges into yours automatically, bringing the FCM service, the
notification-tap trampoline, the permission-prompt host and the `INTERNET`,
`ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS` permissions.

### Alternative: GitHub Packages

Better for a private repository, because a `read:packages` token is free.

Publish once, from the SDK repository:

```bash
cd android
./gradlew :sdk:publishReleasePublicationToGithubPackagesRepository \
    -ParyGithubUser=YOUR_USERNAME -ParyGithubToken=TOKEN_WITH_write:packages
```

Consume it:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/arysoftware/ary-push-sdk")
    credentials {
        username = providers.gradleProperty("aryGithubUser").orNull
        password = providers.gradleProperty("aryGithubToken").orNull
    }
}
```

```kotlin
implementation("com.ary:ary-push:1.0.0")
```

Put the two properties in `~/.gradle/gradle.properties`, never in the repository.

## Step 2. Add your Firebase configuration

FCM is the transport on Android, but the SDK does not own your Firebase project. Add an Android
app in the Firebase console matching your `applicationId`, download `google-services.json` into
`app/`, then:

```kotlin
// build.gradle.kts (root)
plugins { id("com.google.gms.google-services") version "4.4.2" apply false }

// app/build.gradle.kts
plugins { id("com.google.gms.google-services") }
```

Skip this and the app still builds and runs; no push token is issued and the SDK logs one clear
line explaining why.

## Step 3. Initialize

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        ARYPush.initialize(
            this,
            ARYPushConfig(
                enableLogging = BuildConfig.DEBUG,
                smallIconResId = R.drawable.ic_notification,
                backend = PushBackendConfig(
                    baseUrl = BuildConfig.PUSH_API_URL,
                    applicationId = "wallet_android",
                ),
            ),
        )

        ARYPush.addNotificationOpenedListener { notification ->
            Router.handle(this, notification.data)
        }
    }
}
```

Register the class in your manifest if it is new: `<application android:name=".App">`.

> **Attach the listener here, not in an Activity.** A tap that cold-started the process is
> replayed to the first listener that attaches, and at that moment no Activity exists. Get this
> wrong and taps from a killed app land on the home screen.

## Step 4. Ask for permission, when it makes sense

```kotlin
ARYPush.requestPermission { status ->
    if (status == PushPermissionStatus.DENIED) {
        ARYPush.openNotificationSettings(this)
    }
}
```

No `ActivityResultLauncher`, no `onRequestPermissionsResult` override: the SDK hosts the prompt.

## Step 5. Verify

Filter logcat for `ARYPush`:

```
[ARYPush] Initialization started
[ARYPush] Installation ID loaded
[ARYPush] Permission status: GRANTED
[ARYPush] Push token received: dGhp***4n(163) (fcm)
```

---

# iOS

Swift Package Manager resolves from Git directly, so there is nothing to publish and no
repository to configure.

## Step 1. Add the package

Xcode: **File › Add Package Dependencies…**, enter

```
https://github.com/arysoftware/ary-push-sdk
```

Rule **Up to Next Major Version** from `1.0.0`. Add the `ARYPush` library to your app target.

Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/arysoftware/ary-push-sdk.git", from: "1.0.0")
]
```

For a private repository, add your GitHub account once under **Xcode › Settings › Accounts**.

CocoaPods works too:

```ruby
pod 'ARYPush',
    :git => 'https://github.com/arysoftware/ary-push-sdk.git',
    :tag => 'v1.0.0'
```

## Step 2. Enable the capabilities

Target › **Signing & Capabilities** › **+ Capability**:

- **Push Notifications** — adds the `aps-environment` entitlement. Without it APNs registration
  fails and no token is ever issued.
- **Background Modes › Remote notifications** — only for silent, `content-available` messages.

## Step 3. Initialize

```swift
import ARYPush

func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {

    ARYPush.initialize(
        ARYPushConfig(
            enableLogging: true,
            backend: PushBackendConfig(
                baseURL: "https://push-api.ary.com",
                applicationId: "wallet_ios"
            )
        )
    )

    ARYPush.addNotificationOpenedListener { notification in
        Router.handle(notification.data)
    }

    return true
}
```

That is all the app delegate code you write. You do **not** set
`UNUserNotificationCenter.current().delegate`, you do **not** implement
`didRegisterForRemoteNotificationsWithDeviceToken`, and you do **not** upload the token anywhere.

**Already have a notification delegate?** Keep it. The SDK finds it, wraps it and forwards every
callback, so your existing deep links, analytics and action handling go on working.

## Step 4. Ask for permission, when it makes sense

```swift
ARYPush.requestPermission { status in
    if status == .denied {
        Task { @MainActor in ARYPush.openNotificationSettings() }
    }
}
```

iOS shows this prompt **once per install, ever**. Do not spend it on first launch.

## Step 5. Verify

Run on a **real device** — the Simulator issues no APNs token without a paired Mac. In
Console.app, filter the subsystem `com.ary.push`.

---

# Flutter

One dependency. No Gradle edits, no Podfile edits.

## Step 1. Add the package

```yaml
dependencies:
  ary_push:
    git:
      url: https://github.com/arysoftware/ary-push-sdk.git
      ref: v1.0.0
      path: flutter
```

```bash
flutter pub get
```

**That is the entire setup.** The plugin declares the JitPack repository and the SDK coordinate
in its own Gradle build, and its podspec copies the Swift SDK into its pod at `pod install`
time. Your `android/` and `ios/` folders are untouched.

You still need the platform prerequisites every push app needs: `android/app/google-services.json`
with the `com.google.gms.google-services` plugin, and the **Push Notifications** capability in
Xcode.

## Step 2. Initialize

```dart
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await ARYPush.initialize(
    const ARYPushConfig(
      enableLogging: true,
      backend: PushBackendConfig(
        baseUrl: 'https://push-api.ary.com',
        applicationId: 'wallet_flutter',
      ),
    ),
  );

  runApp(const MyApp());
}
```

## Step 3. Route taps

```dart
_opened = ARYPush.onNotificationOpened.listen((notification) {
  if (notification.action == 'open_order') {
    navigatorKey.currentState?.pushNamed(
      '/order',
      arguments: notification.data['orderId'],
    );
  }
});
```

Subscribe before `runApp`. A tap that launched the app from terminated is persisted natively and
replayed to the **first** listener that attaches.

### If your repository is private

The Android side needs a JitPack token, which is the one thing that breaks zero-configuration.
Add to `android/gradle.properties`:

```properties
aryJitpackToken=<token from jitpack.io/private>
```

Or switch to GitHub Packages:

```properties
arySdkCoordinate=com.ary:ary-push:1.0.0
aryGithubUser=<username>
aryGithubToken=<token with read:packages>
```

---

# Tags and segments

This is the part that behaves like OneSignal, and the part worth understanding before you build
campaigns on it.

**You set tags. The backend computes segments. The SDK reads them back.**

```
addTags({subscription: premium, country: PK})
        |
        v
backend recomputes membership
        |
        v
getSegments()  ->  [ "Premium Pakistan Users", "Active Subscribers" ]
```

## Setting tags

```kotlin
ARYPush.addTag("subscription", "premium")
ARYPush.addTags(mapOf("country" to "PK", "language" to "en"))
ARYPush.removeTag("trial_expiry")
ARYPush.removeAllTags()
ARYPush.getTags()
```

Consecutive writes are coalesced into one request, and a write made offline is applied locally
straight away and synchronised later from a durable queue.

## Reading segments

```kotlin
ARYPush.getSegments { segments ->
    segments.forEach { println("${it.id}  ${it.name}") }
}

if (ARYPush.isInSegment("Premium Pakistan Users")) { /* ... */ }
```

```swift
let segments = await ARYPush.getSegments()
let isPremium = await ARYPush.isInSegment("Premium Pakistan Users")
```

```dart
final List<Segment> segments = await ARYPush.getSegments();
final bool isPremium = await ARYPush.isInSegment('Premium Pakistan Users');
```

## Why segments are read-only

A segment is a rule — `subscription == premium AND country == PK` — and rules change far more
often than an app ships. If the SDK evaluated them, last quarter's marketing logic would be
frozen into every installed binary, and changing it would mean a release plus months of adoption.

So the rule lives on the server and is defined once, in the push backend. The device reports
attributes; the backend decides membership; the app can read the answer. To change which
segments a device lands in, change its tags.

An unreachable backend, or no backend configured, returns an empty list rather than an error.

Backend-side rule definition and the campaign model: [BACKEND.md](BACKEND.md).

---

# The rest of the API

Identical on all three platforms. Kotlin shown.

```kotlin
ARYPush.login("USER_123")     // after your own sign-in
ARYPush.logout()              // clears the user, keeps the device registered
ARYPush.subscribeToTopic("sports")
ARYPush.trackEvent("promo_seen", mapOf("id" to "spring"))
ARYPush.getInstallationId()
ARYPush.getPushToken()
```

Full reference: [API.md](API.md).

## What you never have to build

| Not your job | Because |
| --- | --- |
| Requesting `POST_NOTIFICATIONS` | The SDK hosts the prompt |
| A notification-centre delegate | The SDK proxies it and forwards to yours |
| Uploading the push token | Registered and re-registered automatically |
| Token refresh handling | Durable, through the offline queue |
| Creating a notification channel | Created on first use, overridable |
| Deduplicating messages | Bounded, persistent seen-cache |
| Retrying failed registrations | Exponential backoff with jitter |
| Recovering a terminated-state tap | Persisted and replayed to your listener |
| Evaluating segments | Backend work, by design |

---

# When it breaks

| What you see | What it means |
| --- | --- |
| `Could not find com.github.arysoftware:ary-push-sdk` | No tag pushed, or JitPack has not built it yet. Open `jitpack.io/#arysoftware/ary-push-sdk` and click Get it |
| Same, on a private repository | JitPack needs a paid plan and `aryJitpackToken`. Use GitHub Packages instead |
| `plugin is already on the classpath with an unknown version` | A `version` was added to a plugins block that must stay version-less |
| `Firebase is not initialized` | No `google-services.json`, or the plugin is not applied |
| `APNs registration failed` | Simulator without a paired Mac, or the Push Notifications capability is missing |
| Token is null, no error | It arrives asynchronously. Use `addTokenRefreshListener` |
| `getSegments()` returns empty | No backend configured, the backend is unreachable, or membership genuinely is empty |
| Two notifications per message | Your app renders foreground messages too. Set `foregroundDisplay = EVENT_ONLY` |
| Tap does nothing from a killed app | The listener attached too late |

Turn logging on first: `ARYPushConfig(enableLogging = true, logLevel = PushLogLevel.DEBUG)`.

Deeper detail: [ANDROID.md](ANDROID.md), [IOS.md](IOS.md), [FLUTTER.md](FLUTTER.md),
[FIREBASE.md](FIREBASE.md), [NOTIFICATION_LIFECYCLE.md](NOTIFICATION_LIFECYCLE.md),
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

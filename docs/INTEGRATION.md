# Integrating the ARY Push SDK

Everything is fetched from `github.com/arysoftware/ary-push-sdk`. No folder paths, no copying.

| Platform | How you add it | Setup in your app |
| --- | --- | --- |
| **Android** | JitPack | One repository line |
| **iOS** | Swift Package Manager, from the URL | None |
| **Flutter** | `pubspec.yaml` | None |

## No credentials, on any platform

The repository is public, so nothing here asks for a token, an account, or a credential helper.
Each platform's own package manager fetches the SDK the way it fetches any open dependency:

| Platform | Source | What authenticates |
| --- | --- | --- |
| **Android** | JitPack, which builds the tag on demand | Nothing |
| **iOS** | Swift Package Manager, straight from the git tag | Nothing |
| **Flutter** | `pub`, as a git dependency | Nothing |

> **GitHub Packages is deliberately not used.** It requires a personal access token from every
> consumer *even when the repository is public* — GitHub has never supported anonymous Maven
> reads. Making the repository public removes the need for a token only if distribution moves off
> GitHub Packages, which is what JitPack does. An SDK should not make every application that
> embeds it manage a credential.

Two consequences worth knowing:

- Nothing is uploaded to a Maven host. JitPack compiles the SDK the first time someone asks for a
  given version, then caches it, so a release is a **git tag** and nothing more.
- JitPack's coordinate is `com.github.<owner>:<repo>:<version>`, where the version is a git
  reference: a tag verbatim (`v1.0.0`), a commit (`9b152ce`), or `<branch>-SNAPSHOT`.

### Which version to ask for

| Version | Resolves to | Use it when |
| --- | --- | --- |
| `main-SNAPSHOT` | The tip of `main`, rebuilt as it moves | The default. Nothing to keep in step |
| `v1.0.0` | That tag, forever | A production app that wants a fixed, reviewable build |
| `9b152ce` | That commit, forever | Pinning to something specific with no tag for it |

The default is `main-SNAPSHOT` so an application needs no version to manage and works before any
tag exists. Gradle treats a `-SNAPSHOT` version as a changing module and re-checks it rather than
caching it forever, so a push to `main` reaches applications on the next build. That is
convenient during development and is exactly what you do **not** want in a shipping app — pin a
tag before you release.

### Still: never commit a credential

Nothing in this SDK needs one, but if you add a dependency that does, it goes in
`~/.gradle/gradle.properties`, a CI secret, or a git credential helper (`gh auth login`) — never
in `pubspec.yaml`, a tracked `gradle.properties`, or a URL like
`https://ghp_xxxx@github.com/...`. `.github/workflows/security.yml` fails the build on any
committed token or credential-bearing URL. If one does reach a commit, **revoke it first**: the
old commit still contains it, and on a pushed branch it has already left your machine.

### Building the examples in this repository

The examples resolve the SDK exactly as an application does, so they need a version JitPack has
built. To build them from the working tree instead, with no network at all:

```bash
scripts/dev_offline_examples.sh
```

That publishes the Android SDK to `android/build/local-maven` — which the samples pick up
automatically, while still preferring a genuinely published artifact — and points the Flutter
examples at the working tree through a git-ignored `pubspec_overrides.yaml`. Undo it with
`scripts/dev_offline_examples.sh --undo`.

The `pubspec_overrides.yaml` part matters more than it looks. The Flutter plugin finds that local
Maven repository by a path relative to itself, so it only resolves when the plugin is the working
tree. Consumed as a git dependency the plugin lives in the pub cache, where that path does not
exist. To use a local build from an application **outside** this repository, give the absolute
path in that application's `android/gradle.properties`:

```properties
aryPushLocalRepo=/absolute/path/to/ary-push-sdk/android/build/local-maven
```

Open `android/` in Android Studio, never `android/sample-basic`. The samples are modules of that
build; opening one directly makes Gradle treat it as the default project and IDE sync fails with
`Task 'prepareKotlinBuildScriptModel' not found in project ':sample-basic'`.

## Publishing (maintainers, once per release)

Releasing is one command, because there is no artifact to upload:

```bash
git tag -a v1.0.0 -m "ARY Push SDK v1.0.0" && git push origin v1.0.0
```

That is the whole release. All three platforms resolve from the tag: JitPack builds the Android
AAR on first request, Swift Package Manager reads the tag directly, and `pub` clones it.

`.github/workflows/release.yml` runs the tests for all three platforms on that tag, warms the
JitPack build so the first application to ask does not wait for a cold build, and publishes the
release notes from `CHANGELOG.md`. It needs no `packages: write` permission and no secrets.

Publishing to a self-hosted Maven repository as well is optional, and the only route here that
involves credentials at all:

```bash
cd android
./gradlew :sdk:publishReleasePublicationToAryPrivateRepository \
    -ParyPush.group=com.ary -ParyPush.artifact=ary-push -ParyPush.version=1.0.0 \
    -ParyMavenUrl=... -ParyMavenUser=... -ParyMavenPassword=...
```

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

## Step 1. Make the SDK resolvable

### Route A — JitPack (recommended)

One repository, no credentials.

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
    implementation("com.github.arysoftware:ary-push-sdk:main-SNAPSHOT")
}
```

`main-SNAPSHOT` tracks the tip of `main`; swap in a tag such as `v1.0.0` to pin a release. The
first build of any version takes a minute or two while JitPack compiles it; after that it is
cached and served like any other artifact. If it fails, the build log is at
`https://jitpack.io/com/github/arysoftware/ary-push-sdk/<version>/build.log`.

### Route B — Git submodule

Your app carries a pinned checkout and builds the SDK from source, so it depends on no package
host at all. Worth it if you need to patch the SDK, or if your build must work offline.

```bash
git submodule add https://github.com/arysoftware/ary-push-sdk.git third_party/ary-push-sdk
git -C third_party/ary-push-sdk checkout v1.0.0
```

```kotlin
// settings.gradle.kts
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("third_party/ary-push-sdk/android/sdk")

// app/build.gradle.kts
implementation(project(":ary-push-sdk"))
```

The module is self-contained: it declares its own dependency versions and requests its Gradle
plugins without a version, so it needs no version catalog from you.

> Teammates need `git submodule update --init`, and CI needs `submodules: recursive`.

### Either route

`minSdk` 21 or higher, and the Android and Kotlin plugins on the classpath at the root:

```kotlin
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
```

Nothing else changes. The SDK's manifest merges into yours, bringing the FCM service, the
notification-tap trampoline, the permission-prompt host and the permissions it needs.

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

The only platform that genuinely needs nothing in the project: Swift Package Manager resolves
from Git and authenticates with the GitHub account Xcode already knows.

## Step 1. Add the package

Xcode: **File › Add Package Dependencies…**, enter

```
https://github.com/arysoftware/ary-push-sdk
```

Rule **Up to Next Major Version** from `1.0.0`. Add the `ARYPush` library to your app target.
No GitHub account is needed in Xcode: the repository is public.

Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/arysoftware/ary-push-sdk.git", from: "1.0.0")
]
```

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

One Dart dependency. No repository block, no `implementation` line, no Podfile entry, no
credentials.

## Step 1. Add the package

```yaml
dependencies:
  ary_push:
    git:
      url: https://github.com/arysoftware/ary-push-sdk.git
      path: flutter
```

No `ref` tracks the default branch. Add `ref: v1.0.0` to pin a release.

```bash
flutter pub get
```

## Step 2. There is no step 2

The plugin declares the JitPack repository **for every project in your build** and names the SDK
coordinate itself, so you write neither, and neither needs a credential.

> It has to reach into the root project to do that, because Gradle resolves a dependency graph
> using the repositories of the application module rather than those of the plugin that declared
> the dependency. A repository declared only inside the plugin is never consulted, and the
> failure is a `Could not find` that lists every repository except the right one.

iOS needs nothing either: the plugin's podspec copies the Swift SDK into its own pod during
`pod install`.

### Building the native SDK from source instead

Two lines in `android/settings.gradle.kts`. The plugin uses a local module in preference to any
artifact, so nothing else changes:

```kotlin
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("../../ary-push-sdk/android/sdk")
```

### Pointing at your own Maven repository instead

```properties
arySdkCoordinate=com.ary:ary-push:1.0.0
aryMavenUrl=https://maven.example.internal/releases
aryMavenUser=...
aryMavenPassword=...
```

## Step 3. The prerequisites every push app needs

`android/app/google-services.json` with the `com.google.gms.google-services` plugin, and the
**Push Notifications** capability in `ios/Runner.xcworkspace`.

## Step 4. Initialize

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

## Step 5. Route taps

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

---

# Tags and segments

The part that behaves like OneSignal. **You set tags. The backend computes segments. The SDK
reads them back.**

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
straight away then synchronised from a durable queue.

## Reading segments

```kotlin
ARYPush.getSegments { segments -> segments.forEach { println(it.name) } }
ARYPush.isInSegment("Premium Pakistan Users")
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

So the rule lives on the server. The device reports attributes, the backend decides membership,
the app can read the answer. To change which segments a device lands in, change its tags.

An unreachable backend, or no backend configured, returns an empty list rather than an error.
Rule definition and the campaign model: [BACKEND.md](BACKEND.md).

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
| `Could not find com.github.arysoftware:ary-push-sdk`, and the searched list has no `jitpack.io` entry | The JitPack repository is missing from `settings.gradle.kts` |
| `Could not find`, and the list *does* include `jitpack.io` | The tag does not exist, or JitPack's build of it failed. Check `https://jitpack.io/com/github/arysoftware/ary-push-sdk/<tag>/build.log` |
| `Could not find com.ary:ary-push` | That coordinate only exists on a self-hosted repository. Use `com.github.arysoftware:ary-push-sdk:<tag>` for JitPack |
| `plugin is already on the classpath with an unknown version` | A `version` was added to a plugins block that must stay version-less |
| `Firebase is not initialized` | No `google-services.json`, or the plugin is not applied |
| `APNs registration failed` | Simulator without a paired Mac, or the Push Notifications capability is missing |
| Token is null, no error | It arrives asynchronously. Use `addTokenRefreshListener` |
| `getSegments()` returns empty | No backend configured, unreachable, or membership genuinely is empty |
| Two notifications per message | Your app renders foreground messages too. Set `foregroundDisplay = EVENT_ONLY` |
| Tap does nothing from a killed app | The listener attached too late |

Turn logging on first: `ARYPushConfig(enableLogging = true, logLevel = PushLogLevel.DEBUG)`.

Deeper detail: [ANDROID.md](ANDROID.md), [IOS.md](IOS.md), [FLUTTER.md](FLUTTER.md),
[FIREBASE.md](FIREBASE.md), [NOTIFICATION_LIFECYCLE.md](NOTIFICATION_LIFECYCLE.md),
[TROUBLESHOOTING.md](TROUBLESHOOTING.md).

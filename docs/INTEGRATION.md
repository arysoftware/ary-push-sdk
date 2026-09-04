# Integrating the ARY Push SDK

Everything is fetched from `github.com/arysoftware/ary-push-sdk`. No folder paths, no copying.

| Platform | How you add it | Setup in your app |
| --- | --- | --- |
| **Android** | GitHub Packages | A repository block and two properties |
| **iOS** | Swift Package Manager, from the URL | None |
| **Flutter** | `pubspec.yaml` | Two properties |

## A private repository always needs authentication

There is no way around that, so decide where the credential lives:

| Route | Cost | Credential |
| --- | --- | --- |
| **GitHub Packages** | Free | A classic token with `read:packages` |
| **Git submodule** | Free | None. Git handles access |
| **JitPack** | **Paid** for private repositories | A JitPack token |

GitHub Packages is the default throughout this guide: it is the closest thing to consuming the
repository directly, and the token it needs is free and read-only.

> **Correcting an earlier version of this guide,** which claimed a Flutter integration needed no
> native configuration. That was wrong. Gradle resolves a dependency graph using the repositories
> of the **application** module, not those of the plugin that declared the dependency, so a
> plugin cannot supply a credentialed repository entirely on your behalf. The plugin does declare
> the repository for you; you still supply the two credential properties.

iOS is the exception. Swift Package Manager authenticates with your GitHub account in Xcode, so
the iOS side genuinely needs nothing in the project. Sign in once under
**Xcode › Settings › Accounts**.

### Never put the token in a tracked file

Whichever route you pick, the credential goes somewhere git does not track. The two places that
look like configuration but are not are the ones to avoid:

```yaml
# WRONG — pubspec.yaml is committed, so this publishes the token on the next push.
ary_push:
  git:
    url: https://ghp_xxxxxxxxxxxx@github.com/arysoftware/ary-push-sdk.git
```

```properties
# WRONG — the repository's own gradle.properties is committed too.
aryGithubToken=ghp_xxxxxxxxxxxx
```

Put it in one of these instead:

| What needs it | Where it goes |
| --- | --- |
| Gradle | `~/.gradle/gradle.properties` — outside every repository |
| Gradle on CI | `ORG_GRADLE_PROJECT_aryGithubToken` from a secret |
| `git` and `flutter pub get` | A credential helper: `gh auth login`, or `git config --global credential.helper manager` on Windows |

If a token does reach a commit, **revoke it first** at
[github.com/settings/tokens](https://github.com/settings/tokens). Removing it in a later commit
does not help: the old commit still contains it, and on a pushed branch it has already left your
machine. Rewriting history is worth doing afterwards, but revocation is what actually closes the
hole.

`.github/workflows/security.yml` fails the build on any committed token or credential-bearing
URL, so this is caught on the branch rather than discovered later.

### Building the examples in this repository

The examples resolve the SDK exactly as an application does, so until it is published they need
the same access an application would. To build them with no GitHub access at all:

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

When the plugin can find no source for the native SDK at all, it now fails during configuration
with these options spelled out, rather than letting the build run on to
`:app:checkDebugAarMetadata` and report `Could not find com.ary:ary-push` against a repository
list that never contained it. An application that gets the SDK from a repository declared in its
own `settings.gradle.kts` sets `aryPushSkipRepositoryCheck=true` to bypass the check.

Open `android/` in Android Studio, never `android/sample-basic`. The samples are modules of that
build; opening one directly makes Gradle treat it as the default project and IDE sync fails with
`Task 'prepareKotlinBuildScriptModel' not found in project ':sample-basic'`.

## Publishing (maintainers, once per release)

Nothing resolves until the artifact exists and the tag is pushed.

```bash
cd android
./gradlew :sdk:publishReleasePublicationToGithubPackagesRepository \
    -ParyGithubUser=YOUR_USERNAME -ParyGithubToken=TOKEN_WITH_write:packages
```

```bash
git tag -a v1.0.0 -m "ARY Push SDK v1.0.0" && git push origin v1.0.0
```

CI does both on a tag push using the workflow's own `GITHUB_TOKEN`; see
`.github/workflows/release.yml`.

| Who | Token scope | Where it lives |
| --- | --- | --- |
| Whoever publishes | `write:packages` | CI secret, or a local Gradle property |
| Every consuming app | `read:packages` | `~/.gradle/gradle.properties` |

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

### Route A — GitHub Packages (recommended)

`~/.gradle/gradle.properties`, so the token never reaches a repository:

```properties
aryGithubUser=your-github-username
aryGithubToken=ghp_yourTokenWith_read_packages
```

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/arysoftware/ary-push-sdk")
            credentials {
                username = providers.gradleProperty("aryGithubUser").orNull
                password = providers.gradleProperty("aryGithubToken").orNull
            }
        }
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.ary:ary-push:1.0.0")
}
```

### Route B — Git submodule (no package token)

Your app carries a pinned checkout. Access is governed by Git, so there is no second credential
to manage.

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
For a private repository, add your GitHub account once under **Xcode › Settings › Accounts**.

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

One Dart dependency plus two credential properties. No repository block, no `implementation`
line, no Podfile entry.

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

## Step 2. Give Gradle a way to fetch the native SDK

`~/.gradle/gradle.properties`, to keep the token out of your app repository:

```properties
aryGithubUser=your-github-username
aryGithubToken=ghp_yourTokenWith_read_packages
```

That is all. The plugin declares the GitHub Packages repository **for every project in your
build** and names the SDK coordinate itself, so you write neither.

> It has to reach into the root project to do that, because Gradle resolves a dependency graph
> using the repositories of the application module rather than those of the plugin that declared
> the dependency. A repository declared only inside the plugin is never consulted, and the
> failure is a `Could not find` that lists every repository except the right one.

iOS needs nothing: the plugin's podspec copies the Swift SDK into its own pod during
`pod install`.

### Prefer no package token?

Use a submodule. Two lines in `android/settings.gradle.kts`, and the plugin uses the local
module in preference to any artifact:

```kotlin
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("../../ary-push-sdk/android/sdk")
```

### Prefer JitPack?

Free only for public repositories, paid for private ones.

```properties
arySdkCoordinate=com.github.arysoftware:ary-push-sdk:v1.0.0
aryJitpackToken=<token from jitpack.io/private>
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
| `Could not find com.ary:ary-push`, and the searched list has no `maven.pkg.github.com` entry | No credentials, so the repository was skipped. Set `aryGithubUser` and `aryGithubToken` |
| `Could not find`, and the list *does* include GitHub Packages | The artifact was never published, or the token lacks `read:packages` |
| `Could not find com.github.arysoftware:...` | JitPack route: no tag pushed, or the repository is private and JitPack needs a paid plan |
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

# Integrating the ARY Push SDK

How to add the SDK to an existing **Android**, **iOS** or **Flutter** application, as a library
you consume from `github.com/arysoftware/ary-push-sdk` rather than a folder you copy around.

## Pick how you consume it

iOS resolves straight from Git. Android needs either a published artifact or the source on disk,
because Gradle cannot fetch a module from a Git URL on its own.

| Route | Android | iOS | Flutter | Needs |
| --- | --- | --- | --- | --- |
| **GitHub Packages** | yes | n/a | yes | A token with `read:packages` |
| **Git submodule** | yes | yes | yes | Nothing beyond Git |
| **Local path** | yes | yes | yes | The SDK checked out beside your app |

**Use GitHub Packages** unless your team would rather not deal with package tokens, in which case
use a submodule. Local paths are for working on the SDK itself.

Whichever you pick, the application code is identical. Switching later changes two or three build
lines and nothing else.

| Platform | You reference | Public entry point |
| --- | --- | --- |
| Android | `com.ary:ary-push:1.0.0` | `com.ary.push.ARYPush` |
| iOS | The Git URL, via SPM or CocoaPods | `ARYPush` |
| Flutter | A `git:` dependency | `ARYPush` from `package:ary_push/ary_push.dart` |

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
| Read access to the private repository | yes | yes | yes |

The SDK ships **no** Firebase configuration, no APNs key and no credentials. Each application
supplies its own. See [SECURITY.md](SECURITY.md).

---

# Publishing the SDK (maintainers, once per release)

Consuming projects can only resolve what has been published. Do this first.

```bash
cd android
./gradlew :sdk:publishReleasePublicationToGithubPackagesRepository \
    -ParyGithubUser=YOUR_GITHUB_USERNAME \
    -ParyGithubToken=YOUR_TOKEN_WITH_write:packages
```

That uploads `com.ary:ary-push:1.0.0` to
`maven.pkg.github.com/arysoftware/ary-push-sdk`.

Then tag the release, so applications pin a version instead of tracking a branch:

```bash
git tag -a v1.0.0 -m "ARY Push SDK v1.0.0" && git push origin v1.0.0
```

iOS and Flutter consume the tag directly; nothing else to upload for them.

> CI does both automatically when you push a tag. See `.github/workflows/release.yml`.

---

# Part 1: Android application

## Step 1. Make the SDK resolvable

### Route A — GitHub Packages (recommended)

`android/gradle.properties`, or better `~/.gradle/gradle.properties` so it never reaches your
repository:

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

> GitHub Packages requires authentication even for packages you can already see, which is why a
> token is needed here where a public Maven repository would need none. A classic token with
> `read:packages` is enough; it needs no repository write access.

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.ary:ary-push:1.0.0")
}
```

### Route B — Git submodule

No tokens, no publishing. Your app carries a pinned checkout of the SDK.

```bash
git submodule add https://github.com/arysoftware/ary-push-sdk.git third_party/ary-push-sdk
git -C third_party/ary-push-sdk checkout v1.0.0
git commit -am "Add ARY Push SDK v1.0.0"
```

`settings.gradle.kts`:

```kotlin
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("third_party/ary-push-sdk/android/sdk")
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":ary-push-sdk"))
}
```

The module is self-contained: it declares its own dependency versions and requests its Gradle
plugins without a version, so it needs no version catalog from you and cannot collide with a
`libs` catalog you already have.

> Teammates cloning your app need `git submodule update --init --recursive`, and CI needs
> `submodules: recursive` on its checkout step.

### Either route

Your `minSdk` must be 21 or higher, and the Android and Kotlin plugins must be on the classpath
at the root:

```kotlin
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}
```

Nothing else in your Gradle files changes. The SDK's `AndroidManifest.xml` merges into yours
automatically, bringing the FCM service, the notification-tap trampoline, the permission-prompt
host and the `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS` permissions. You copy
none of it.

## Step 2. Add your Firebase configuration

FCM is the transport on Android, but the SDK does not own your Firebase project.

1. In the Firebase console, add an Android app whose package name matches your `applicationId`.
2. Download `google-services.json` into `app/`.
3. Apply the plugin:

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
                logLevel = PushLogLevel.DEBUG,
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
Call it at a moment the user understands, not on first launch.

## Step 5. Verify

Filter logcat for `ARYPush`. A healthy launch reads:

```
[ARYPush] Initialization started
[ARYPush] Installation ID loaded
[ARYPush] Permission status: GRANTED
[ARYPush] Push token received: dGhp***4n(163) (fcm)
```

Send a test message from the Firebase console with a `notification_id` in the data payload, tap
it, and confirm your opened-listener fires.

---

# Part 2: iOS application

iOS is the easy one: Swift Package Manager and CocoaPods both resolve straight from Git, so
there is nothing to publish.

## Step 1. Add the package

### Swift Package Manager (recommended)

Xcode: **File › Add Package Dependencies…**, enter

```
https://github.com/arysoftware/ary-push-sdk
```

Set the rule to **Up to Next Major Version** from `1.0.0`, and add the `ARYPush` library to your
app target.

Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/arysoftware/ary-push-sdk.git", from: "1.0.0")
]
```

> Xcode will ask you to sign in to GitHub the first time it resolves a private repository. Use
> **Settings › Accounts** to add your GitHub account once.

### CocoaPods

```ruby
platform :ios, '13.0'

target 'YourApp' do
  use_frameworks!
  pod 'ARYPush',
      :git => 'https://github.com/arysoftware/ary-push-sdk.git',
      :tag => 'v1.0.0'
end
```

Run `pod install` and open the `.xcworkspace` from then on.

## Step 2. Enable the capabilities

Target › **Signing & Capabilities** › **+ Capability**:

- **Push Notifications** — adds the `aps-environment` entitlement. Without it APNs registration
  fails and no token is ever issued.
- **Background Modes › Remote notifications** — only if you send silent, `content-available`
  messages.

Then upload your APNs key or certificate to whatever sends your pushes. The SDK contains neither.

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
            logLevel: .debug,
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
callback, so your existing deep links, analytics and action handling go on working. Presentation
options are the union of yours and the SDK's, so neither side can silence the other.

If your policy forbids runtime method manipulation, turn the proxying off and forward explicitly:
[IOS.md](IOS.md#if-your-policy-forbids-runtime-method-manipulation).

## Step 4. Ask for permission, when it makes sense

```swift
ARYPush.requestPermission { status in
    if status == .denied {
        Task { @MainActor in ARYPush.openNotificationSettings() }
    }
}
```

iOS shows this prompt **once per install, ever**. There is no second chance, so do not spend it
on first launch. Once granted, the SDK registers with APNs itself.

## Step 5. Verify

Run on a **real device** — the Simulator issues no APNs token without a paired Mac. Open
Console.app, filter the subsystem `com.ary.push`, and look for `Initialization started`,
`Installation ID loaded` and `APNs token received`. Then send a test push and confirm your
opened-listener fires.

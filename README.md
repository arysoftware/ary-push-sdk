# ARY Push SDK

A **private**, ARY-owned push notification SDK for native Android, native iOS and Flutter
applications. It owns the entire client-side push lifecycle so that host applications only have to
care about UI, navigation and business logic.

> ARY-owned infrastructure. It is **not** published to Maven Central, the CocoaPods trunk or
> pub.dev. Distribution is straight from this repository: JitPack builds the Android artifact on
> demand, Swift Package Manager and `pub` read the git tag directly. No account or token is
> needed on any platform.

---

## What the SDK owns

Permission, push token, token refresh, installation identity, notification receiving, notification
display, notification click, notification actions, channels, topics, tags, user identity, local
storage, REST communication, offline queue, retry, deduplication, logging and error handling.

## What the host application owns

UI, navigation, routing, business logic, application-specific APIs and application-specific data.

The SDK **never navigates**. It emits events; the host application decides what a notification means.

---

## Repository layout

```
ary-push-sdk/
  android/                       Android SDK (Kotlin) + sample host apps
    sdk/                         com.github.arysoftware:ary-push-sdk
    sample-basic/
    sample-existing-firebase/
  ios/                           iOS SDK (Swift package "ARYPush")
    Sources/ARYPush/
    Tests/ARYPushTests/
    sample-basic/
    sample-existing-notification-delegate/
  flutter/                       Flutter plugin "ary_push" (thin bridge)
    lib/  android/  ios/  test/
    example/                     Minimal host app
    example-firebase/            Host app already using firebase_messaging
  docs/                          Full documentation set
  scripts/                       Release and namespace tooling
  .github/workflows/             CI/CD
```

## Quick start

On every platform, with no account, token or credential of any kind. `main-SNAPSHOT` tracks the
tip of `main`, so nothing needs a version until you want to pin a release:

**Android** — one repository line in `settings.gradle.kts` plus the dependency:

```kotlin
maven { url = uri("https://jitpack.io") }
implementation("com.github.arysoftware:ary-push-sdk:main-SNAPSHOT")
```

```kotlin
ARYPush.initialize(this)
```

**iOS** — Xcode › Add Package Dependencies › `https://github.com/arysoftware/ary-push-sdk`.
Nothing else.

```swift
ARYPush.initialize()
```

**Flutter** — `pubspec.yaml`, and nothing else:

```yaml
dependencies:
  ary_push:
    git:
      url: https://github.com/arysoftware/ary-push-sdk.git
      path: flutter
```

```dart
await ARYPush.initialize();
```

The plugin declares the Maven repository and the SDK coordinate for you, so no Gradle or Podfile
edits are needed. The repository is public and JitPack builds it on demand, so that really is the
whole Flutter integration.

Complete steps: [docs/INTEGRATION.md](docs/INTEGRATION.md).

## Documentation

| Document | Contents |
| --- | --- |
| [QUICK_START.md](docs/QUICK_START.md) | Add the SDK to an existing app in under 5 minutes |
| [INTEGRATION.md](docs/INTEGRATION.md) | Complete step-by-step integration as a local module, on all three platforms |
| [ANDROID.md](docs/ANDROID.md) | Android integration, manifest, channels, permissions |
| [IOS.md](docs/IOS.md) | iOS integration, APNs, delegate forwarding |
| [FLUTTER.md](docs/FLUTTER.md) | Flutter plugin, event queue, engine lifecycle |
| [FIREBASE.md](docs/FIREBASE.md) | Coexistence with existing Firebase and FCM integrations |
| [NOTIFICATION_LIFECYCLE.md](docs/NOTIFICATION_LIFECYCLE.md) | Foreground, background and terminated behaviour |
| [REST_API.md](docs/REST_API.md) | Versioned backend contract, every endpoint |
| [BACKEND.md](docs/BACKEND.md) | Backend data model, segments, campaigns |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Internal architecture and component responsibilities |
| [API.md](docs/API.md) | Public API reference for all three platforms |
| [SECURITY.md](docs/SECURITY.md) | Credential rules, TLS, logging, privacy |
| [MIGRATION.md](docs/MIGRATION.md) | Migrating from firebase_messaging or custom notification code |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Symptom, cause and fix |

## Identifiers

| Identifier | Meaning |
| --- | --- |
| `com.ary.push` | Android package |
| `com.github.arysoftware:ary-push-sdk` | Maven coordinate, as JitPack serves it |
| `ARYPush` | iOS module and public type prefix |
| `ary_push` | Flutter package, platform channels and storage key namespace |
| `push-api.ary.com` | Example backend host, never hardcoded in SDK code |

These were applied from the repository's neutral placeholders with
[scripts/rename_namespace.sh](scripts/rename_namespace.sh), which stays in the tree so the same
pass can be re-run if the naming changes:

```bash
scripts/rename_namespace.sh ary com.ary.push ARYPush ary_push
```

## Compatibility

See the compatibility matrix in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Licence

Proprietary and confidential. See [LICENSE](LICENSE).

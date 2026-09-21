# ARY Push SDK

An ARY-owned push notification SDK for native Android, native iOS and Flutter
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

The full specification — API reference, integration code for every platform and a Postman
collection — is **[docs/ARYPush-Technical-Specification.md](docs/ARYPush-Technical-Specification.md)**.

The host application supplies four values at initialization: `baseUrl`, `applicationId`,
`projectId` and `authToken`. The SDK then talks to exactly five endpoints:

| Operation | Request |
| --- | --- |
| Register Device | `POST /api/notifications/devices/register` |
| Device Token Update | `PUT /api/notifications/devices/update` |
| Subscribe / Unsubscribe | `PUT /api/notifications/devices/toggle` |
| Add Segment Subscriber | `POST /api/segments/{segmentId}/subscribers` |
| Segment List | `GET /api/segments/list` |

Every request carries `Authorization: Bearer <authToken>` and `?projectId=<projectId>`.

**Android** — `settings.gradle.kts` and `app/build.gradle.kts`:

```kotlin
maven { url = uri("https://jitpack.io") }
implementation("com.github.arysoftware:ary-push-sdk:main-SNAPSHOT") // or a release tag, e.g. v1.1.0
```

```kotlin
ARYPush.initialize(
    this,
    ARYPushConfig(
        backend = PushBackendConfig(
            baseUrl = "https://push-api.ary.com",
            applicationId = "wallet_android",
            projectId = "YOUR_PROJECT_ID",
            authToken = "YOUR_BEARER_TOKEN"
        )
    )
)
```

**iOS** — Xcode › Add Package Dependencies › `https://github.com/arysoftware/ary-push-sdk`,
then enable the **Push Notifications** capability.

```swift
ARYPush.initialize(
    ARYPushConfig(
        backend: PushBackendConfig(
            baseURL: "https://push-api.ary.com",
            applicationId: "wallet_ios",
            projectId: "YOUR_PROJECT_ID",
            authToken: "YOUR_BEARER_TOKEN"
        )
    )
)
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
await ARYPush.initialize(
  const ARYPushConfig(
    backend: PushBackendConfig(
      baseUrl: 'https://push-api.ary.com',
      applicationId: 'wallet_flutter',
      projectId: 'YOUR_PROJECT_ID',
      authToken: 'YOUR_BEARER_TOKEN',
    ),
  ),
);
```

No account or token is needed to *download* the SDK on any platform: the repository is public.

## Documentation

| Document | Contents |
| --- | --- |
| [ARYPush-Technical-Specification.md](docs/ARYPush-Technical-Specification.md) | **Start here.** Configuration, the 5 endpoints, integration code for all 3 platforms, Postman collection |
| [ANDROID.md](docs/ANDROID.md) | Android specifics: manifest, channels, permissions |
| [IOS.md](docs/IOS.md) | iOS specifics: APNs, capabilities, delegate forwarding, tokens |
| [FLUTTER.md](docs/FLUTTER.md) | Flutter plugin, event streams, platform prerequisites |
| [FIREBASE.md](docs/FIREBASE.md) | Coexistence with an existing Firebase Messaging integration |
| [NOTIFICATION_LIFECYCLE.md](docs/NOTIFICATION_LIFECYCLE.md) | Foreground, background and terminated behaviour |
| [API.md](docs/API.md) | Public SDK API reference for all three platforms |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Internal architecture and component responsibilities |
| [SECURITY.md](docs/SECURITY.md) | Credential rules, TLS, logging, privacy |
| [MIGRATION.md](docs/MIGRATION.md) | Migrating from firebase_messaging or custom notification code |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Symptom, cause and fix |
| [postman/ARYPush.postman_collection.json](postman/ARYPush.postman_collection.json) | The 5 requests, ready to import into Postman |

## Working on this repository

**Releasing** is a git tag. JitPack builds the Android artifact on first request, Swift Package
Manager and `pub` read the tag directly, and nothing is uploaded anywhere:

```bash
git tag -a v1.1.0 -m "ARY Push SDK v1.1.0" && git push origin v1.1.0
```

**Running the examples before a release exists.** `scripts/dev_offline_examples.sh` builds the
Android SDK into `android/build/local-maven` and points the Flutter examples at the working tree
through a git-ignored `pubspec_overrides.yaml`; `--undo` reverses it.

**Open `android/` in Android Studio**, never a sample folder inside it. The samples are modules of
that build, and opening one directly fails sync with
`Task 'prepareKotlinBuildScriptModel' not found`.

**Never commit a credential.** `.github/workflows/security.yml` fails the build on any committed
access token or credential-bearing URL.


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

# ARY Push SDK for Android

Kotlin implementation of the ARY Push SDK. Published privately as
`com.ary:ary-push`.

## Integration

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri(PRIVATE_MAVEN_URL) }
    }
}
```

```kotlin
// app/build.gradle.kts
implementation("com.ary:ary-push:1.0.0")
```

```kotlin
ARYPush.initialize(this)
```

Full documentation: [../../docs/ANDROID.md](../../docs/ANDROID.md).

## Module layout

| Package | Contents |
| --- | --- |
| `com.ary.push` | Public facade and configuration |
| `com.ary.push.model` | `PushNotification`, `Installation`, permission and provider enums |
| `com.ary.push.api` | `RestClient`, `ApiResult`, `AuthProvider` and the OkHttp implementation |
| `com.ary.push.backend` | `PushBackend`, `RestPushBackend`, `NoopPushBackend` |
| `com.ary.push.messaging` | FCM service and the `ARYPushMessaging` host bridge |
| `com.ary.push.startup` | Optional AndroidX Startup initializer |
| `com.ary.push.internal` | Managers and engine internals; not public API |

## Building and testing

```bash
./gradlew :sdk:test :sdk:lint
```

## Publishing

```bash
./gradlew :sdk:publishReleasePublicationToARYPrivateRepository \
    -ParyPush.version=1.0.0
```

Credentials come from `~/.gradle/gradle.properties` or CI secrets and are never committed. A
local staging repository is always available for integration testing:

```bash
./gradlew :sdk:publishReleasePublicationToLocalStagingRepository
```

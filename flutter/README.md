# ary_push

Flutter plugin for the private ARY Push SDK.

A thin bridge, not an implementation. FCM and APNs handling, the token lifecycle, notification
storage, rendering and deduplication all live in the native SDKs; this package forwards to them
over a `MethodChannel` and a single `EventChannel`. There is deliberately no second notification
engine in Dart: a Dart engine cannot run while the app is terminated, which is exactly when a
notification tap has to be captured.

## Integration

```yaml
dependencies:
  ary_push:
    git:
      url: https://github.com/arysoftware/ary-push-sdk.git
      path: flutter
```

The repository is public, so no key or token is involved. With no `ref` this tracks the default
branch; add `ref: v1.0.0` to pin a release before shipping.

```dart
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await ARYPush.initialize();
  runApp(const MyApp());
}
```

Full walkthrough: [../docs/FLUTTER.md](../docs/FLUTTER.md).

## What the application still owns

The SDK never navigates. It emits events; routing is yours:

```dart
ARYPush.onNotificationOpened.listen((notification) {
  if (notification.action == 'open_order') {
    navigator.pushNamed('/order', arguments: notification.data['orderId']);
  }
});
```

Subscribe during startup, before `runApp`. A tap that launched the application from a terminated
state is persisted natively and replayed to the first listener that attaches, so an early
subscription always sees it.

## Package layout

| Path | Contents |
| --- | --- |
| `lib/ary_push.dart` | Public exports |
| `lib/src/ary_push.dart` | The API surface |
| `lib/src/models.dart` | `PushNotification`, permission and provider enums |
| `lib/src/config.dart` | `ARYPushConfig`, `PushBackendConfig` |
| `lib/src/platform_channel.dart` | The single seam between Dart and native |
| `android/` | Kotlin bridge over `com.ary:ary-push` |
| `ios/` | Swift bridge over the `ARYPush` pod |

## Examples

| Example | What it shows |
| --- | --- |
| `example/` | A new application: one line of integration and a router |
| `example-firebase/` | An application already using `firebase_core` and `firebase_messaging`, with both still working |

Both carry committed `android/` and `ios/` folders, so they run without a `flutter create` step.

### Running them from this repository

Both examples declare the published GitHub package, exactly as a real application does, so
reading them tells you the truth about integration. That also means they resolve
`ary_push v1.0.0` from GitHub, and the native Android SDK from JitPack.

While working **on** the SDK you want the working tree instead. Drop a `pubspec_overrides.yaml`
beside the example's `pubspec.yaml`:

```yaml
dependency_overrides:
  ary_push:
    path: ../
```

and, for the Android build, add the SDK module to `example/android/settings.gradle.kts`:

```kotlin
include(":ary-push-sdk")
project(":ary-push-sdk").projectDir = file("../../../android/sdk")
```

`pubspec_overrides.yaml` is git-ignored and never ships. CI writes both automatically, so a
breaking SDK change fails the example build rather than surfacing after a tag is cut.

Without a `google-services.json` the examples still build and run. No push token is issued and
the SDK logs one clear error explaining why.

## Testing

```bash
flutter test
flutter analyze
```

The Dart tests cover payload parsing and its failure modes, permission mapping (including the
rule that an unknown status never reads as authorized), the method channel contract, error
translation, and event-stream demultiplexing over the shared channel.

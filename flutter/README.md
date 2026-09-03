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
      url: git@github.com:arysoftware/ary-push-sdk.git
      ref: v1.0.0
      path: flutter
```

Production projects reference a release tag, never a branch.

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

No publishing step and no private repository needed. Both examples resolve the native SDK
directly from this checkout:

```bash
cd flutter/example
flutter run
```

Android resolves it through `include(":ary-push-sdk")` in the example's
`settings.gradle.kts`; iOS through `pod 'ARYPush', :path => '../../../ios'` in its `Podfile`.
Edit SDK source, re-run, done.

A consuming application drops that `include` block and sets `aryMavenUrl` to ARY's private
Maven repository instead. The plugin supports both and prefers whichever is present, so nothing
in it changes.

Without a `google-services.json` the examples still build and run. No push token is issued and
the SDK logs one clear error explaining why. Drop your own file into `example/android/app/` and
the Gradle plugin is applied automatically; for iOS, enable the Push Notifications capability in
`ios/Runner.xcworkspace`.

## Testing

```bash
flutter test
flutter analyze
```

The Dart tests cover payload parsing and its failure modes, permission mapping (including the
rule that an unknown status never reads as authorized), the method channel contract, error
translation, and event-stream demultiplexing over the shared channel.

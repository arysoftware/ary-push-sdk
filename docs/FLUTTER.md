| Source | When |
| --- | --- |
| JitPack, `com.github.arysoftware:ary-push-sdk` | The default. Nothing to configure |
| GitHub Packages, `com.ary:ary-push` | Set `arySdkCoordinate` plus a `read:packages` token |
| Gradle project `:ary-push-sdk` | A local checkout, while working on the SDK |
# Flutter integration

## Dependency

```yaml
dependencies:
  ary_push:
    git:
      url: git@github.com:arysoftware/ary-push-sdk.git
      ref: v1.0.0
      path: flutter
```

Production projects reference a release tag, never a branch. Developers need SSH access to the
private repository; CI needs a deploy key.

## Native prerequisites

The plugin is a bridge. The native SDKs still need what they always need:

| Platform | Requirement |
| --- | --- |
| Android | `google-services.json` and the `com.google.gms.google-services` plugin in `android/app` |
| Android | The private Maven repository declared in `android/settings.gradle` so `com.ary:ary-push` resolves |
| iOS | Push Notifications capability and, for silent messages, Background Modes > Remote notifications |
| iOS | `pod 'ARYPush', :git => '...', :tag => 'v1.0.0'` in `ios/Podfile`, unless your private spec repo carries it |

### Android toolchain

The plugin's `android/build.gradle.kts` defaults to AGP 9.0.1 and Kotlin 2.3.20, matching
what `flutter create` generates on Flutter 3.44. An application on an older Flutter can
override both from its `android/gradle.properties` rather than forking the plugin:

```properties
aryPushAgpVersion=8.7.3
aryPushKotlinVersion=2.1.0
```

## Running the examples in this repository

Both examples declare the published GitHub package, exactly as a consuming application does, so
reading them tells you the truth about integration:

```bash
cd flutter/example
flutter run
```

While working **on** the SDK you want the working tree instead. Add a `pubspec_overrides.yaml`
beside the example pubspec pointing `ary_push` at `path: ../`, and add the SDK module to the
example `android/settings.gradle.kts`. Both are git-ignored or CI-generated, so the committed
example keeps showing the real thing. CI does exactly this, which is why a breaking SDK change
fails the example build.

The plugin resolves the native Android SDK from whichever of these is present, in this order:

| Source | When |
| --- | --- |
| Gradle project `:ary-push-sdk` | A local checkout, while working on the SDK |
| JitPack, `com.github.arysoftware:ary-push-sdk` | The default. Nothing for an application to configure |
| GitHub Packages, `com.ary:ary-push` | Set `arySdkCoordinate` plus a `read:packages` token |

A real consuming application configures none of this: the plugin declares the JitPack repository
and the SDK coordinate in its own Gradle build, so a Flutter integration is one pubspec entry.

The examples build and run without Firebase configuration, but no push token is issued and the
SDK logs one clear error saying so. To get real delivery, add your own `google-services.json` to
`flutter/example/android/app/` (the Gradle plugin applies itself once the file is present) and
enable the Push Notifications capability in `ios/Runner.xcworkspace`.

## Initialization

```dart
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

## Routing taps

```dart
class _MyAppState extends State<MyApp> {
  final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();
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

Subscribe as early as possible. A tap that launched the app from a terminated state is persisted
natively and replayed to the first listener that attaches; subscribing in a late-built widget
means a different listener gets there first.

## Why the plugin is thin

The native SDKs own FCM and APNs handling, the token lifecycle, notification storage, rendering
and deduplication. Dart owns none of it, and that is deliberate:

- **A Dart isolate cannot run while the app is terminated**, which is exactly when a notification
  tap has to be captured. Only native code is there.
- Two engines drift. One implementation, two thin bridges, no divergence between what Android
  does and what iOS does.
- Background message handling in Dart requires a second isolate with its own plugin registration,
  a well-known source of subtle failures.

## Event streams and the engine lifecycle

All three streams come from **one** `EventChannel`, demultiplexed in Dart:

```dart
ARYPush.onNotificationReceived  // a message arrived while the app was running
ARYPush.onNotificationOpened    // the user tapped
ARYPush.onTokenRefresh          // the push token changed
```

One native listener exists however many Dart streams are subscribed. That is what keeps hot
restart and engine recreation from accumulating duplicate listeners.

**Hot restart and hot reload** are handled: the native plugin attaches listeners when Dart starts
listening and detaches them when it stops, so a restart cannot leave the previous engine's
listeners behind emitting duplicates.

**Events that occur before Dart is listening** are queued natively (bounded at 50) and delivered
in order once a listener attaches. Notification opens are not in that queue: the native SDK
persists those to disk, which is what makes them survive a terminated launch rather than merely
a slow one.

Applications that prefer to pull once instead of subscribing:

```dart
final PushNotification? initial = await ARYPush.getInitialNotification();
```

Use one or the other, not both: the event is delivered exactly once.

## Working with Firebase already

Set `foregroundDisplay: ForegroundDisplayPolicy.eventOnly` if you already show your own in-app
banner from `FirebaseMessaging.onMessage`, and route from either
`FirebaseMessaging.onMessageOpenedApp` or `ARYPush.onNotificationOpened` but not both. Full
detail and a working sample: [FIREBASE.md](FIREBASE.md).

## Errors

The plugin is reluctant to throw: push and network failures are logged and absorbed natively,
because a notification problem must not crash an application. What does reach Dart is programmer
error:

```dart
try {
  await ARYPush.login('USER_123');
} on ARYPushException catch (e) {
  debugPrint('${e.code}: ${e.message}');
}
```

| Code | Meaning |
| --- | --- |
| `not_initialized` | An API was called before `initialize()` |
| `invalid_argument` | A blank user id, an invalid topic name |
| `unsupported_platform` | Running somewhere other than Android or iOS |

## Testing

```bash
cd flutter
flutter test
flutter analyze
```

The Dart tests use a mocked platform channel, so they run without a device. Delivery itself needs
a real device; the `example/` and `example-firebase/` applications exist for that.

import 'dart:async';

import 'package:ary_push/ary_push.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';

/// A Flutter application that was already using Firebase before the SDK arrived.
///
/// This is the case the SDK exists to survive. The application initializes its own
/// `FirebaseApp`, keeps its own `FirebaseMessaging` handlers, and keeps its own background
/// message handler. Adding push changes none of that.
///
/// Three things are worth noticing:
///
///  * The SDK does **not** initialize Firebase. It attaches to whatever the host has set up, so
///    the host keeps full control of which Firebase project it talks to.
///  * There is one FCM token, not two. Both `FirebaseMessaging.instance.getToken()` and
///    `ARYPush.getPushToken()` return the same value; the SDK adds an installation identity
///    on top of it rather than a second registration.
///  * `foregroundDisplay` is set to `eventOnly` because this application already shows its own
///    in-app banner. Without that the user would see the same message twice.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // The application's own Firebase setup, unchanged and still first.
  await Firebase.initializeApp();
  FirebaseMessaging.onBackgroundMessage(_appBackgroundHandler);

  await ARYPush.initialize(
    const ARYPushConfig(
      enableLogging: true,
      logLevel: PushLogLevel.debug,
      foregroundDisplay: ForegroundDisplayPolicy.eventOnly,
      defaultChannelId: 'ary_push_campaigns',
      defaultChannelName: 'Offers and updates',
      backend: PushBackendConfig(
        baseUrl: 'https://push-api-dev.ary.com',
        applicationId: 'legacy_flutter',
      ),
    ),
  );

  runApp(const CoexistenceApp());
}

/// The application's pre-existing background handler, still registered and still called.
@pragma('vm:entry-point')
Future<void> _appBackgroundHandler(RemoteMessage message) async {
  debugPrint('Application background handler still runs: ${message.messageId}');
}

/// Shows the SDK and the application's own Firebase usage side by side.
class CoexistenceApp extends StatefulWidget {
  /// Creates the application.
  const CoexistenceApp({super.key});

  @override
  State<CoexistenceApp> createState() => _CoexistenceAppState();
}

class _CoexistenceAppState extends State<CoexistenceApp> {
  final List<StreamSubscription<Object?>> _subscriptions =
      <StreamSubscription<Object?>>[];

  String? _appToken;
  String? _sdkToken;
  String? _installationId;

  @override
  void initState() {
    super.initState();

    // The application's own message handlers, unchanged.
    _subscriptions.add(
      FirebaseMessaging.onMessage.listen((RemoteMessage message) {
        debugPrint(
            'Application foreground handler still runs: ${message.messageId}');
      }),
    );
    _subscriptions.add(
      FirebaseMessaging.onMessageOpenedApp.listen((RemoteMessage message) {
        debugPrint('Application deep-link handler still runs: ${message.data}');
      }),
    );

    // The SDK's events, alongside them.
    _subscriptions.add(
      ARYPush.onNotificationOpened.listen((PushNotification notification) {
        debugPrint('ARY Push notification opened: ${notification.data}');
      }),
    );

    unawaited(_loadTokens());
  }

  Future<void> _loadTokens() async {
    final String? appToken = await FirebaseMessaging.instance.getToken();
    final String? sdkToken = await ARYPush.getPushToken();
    final String? installationId = await ARYPush.getInstallationId();

    if (!mounted) {
      return;
    }
    setState(() {
      _appToken = appToken;
      _sdkToken = sdkToken;
      _installationId = installationId;
    });
  }

  @override
  void dispose() {
    for (final StreamSubscription<Object?> subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bool sameToken = _appToken != null && _appToken == _sdkToken;

    return MaterialApp(
      title: 'Firebase coexistence',
      theme: ThemeData(useMaterial3: true),
      home: Scaffold(
        appBar: AppBar(title: const Text('Firebase coexistence')),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text("Application's own FCM token: ${_short(_appToken)}"),
              Text('Token as seen by the SDK:    ${_short(_sdkToken)}'),
              Text('Same token: $sameToken'),
              const SizedBox(height: 16),
              Text('SDK installation: ${_installationId ?? '-'}'),
            ],
          ),
        ),
      ),
    );
  }

  String _short(String? token) =>
      token == null ? '-' : '${token.substring(0, 12)}...';
}

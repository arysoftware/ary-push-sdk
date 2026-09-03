import 'dart:async';

import 'package:ary_push/ary_push.dart';
import 'package:flutter/material.dart';

/// The whole push integration for a brand-new Flutter application.
///
/// Two things happen before `runApp` and nothing else: the SDK is initialized, and listeners are
/// attached so the application can route notification taps. Permission, tokens, token refreshes,
/// the installation identity, rendering, channels, deduplication, backend registration, the
/// offline queue and retries are all handled natively.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await ARYPush.initialize(
    const ARYPushConfig(
      enableLogging: true,
      logLevel: PushLogLevel.debug,
      // Omit `backend` entirely and everything below still works, with no server.
      backend: PushBackendConfig(
        baseUrl: 'https://push-api-dev.ary.com',
        applicationId: 'example_flutter',
      ),
    ),
  );

  runApp(const ExampleApp());
}

/// Root widget, and the owner of the application's navigation.
class ExampleApp extends StatefulWidget {
  /// Creates the example application.
  const ExampleApp({super.key});

  @override
  State<ExampleApp> createState() => _ExampleAppState();
}

class _ExampleAppState extends State<ExampleApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final List<StreamSubscription<Object?>> _subscriptions =
      <StreamSubscription<Object?>>[];

  @override
  void initState() {
    super.initState();

    // Subscribed as early as possible: a tap that cold-started the process is replayed to the
    // first listener that attaches, so this sees it rather than losing it.
    _subscriptions.add(ARYPush.onNotificationOpened.listen(_route));
    _subscriptions.add(
      ARYPush.onNotificationReceived.listen((PushNotification notification) {
        debugPrint('Received while running: ${notification.id}');
      }),
    );
    _subscriptions.add(
      ARYPush.onTokenRefresh.listen((String token) {
        debugPrint('Token refreshed');
      }),
    );
  }

  @override
  void dispose() {
    for (final StreamSubscription<Object?> subscription in _subscriptions) {
      unawaited(subscription.cancel());
    }
    super.dispose();
  }

  /// Where the application, not the SDK, decides what a notification means.
  ///
  /// The SDK delivers `data` verbatim and stops. Deciding that `action=open_order` leads to the
  /// order screen is application knowledge, and putting it in the SDK would tie one push
  /// implementation to one application's navigation graph.
  void _route(PushNotification notification) {
    final NavigatorState? navigator = _navigatorKey.currentState;
    if (navigator == null) {
      return;
    }
    if (notification.action == 'open_order') {
      navigator.pushNamed('/order', arguments: notification.data['orderId']);
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      title: 'ARY Push Example',
      theme: ThemeData(useMaterial3: true),
      home: const HomePage(),
      routes: <String, WidgetBuilder>{
        '/order': (BuildContext context) => const OrderPage(),
      },
    );
  }
}

/// Exercises the public API surface a host application actually uses.
class HomePage extends StatefulWidget {
  /// Creates the home page.
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  PushPermissionStatus _permission = PushPermissionStatus.notDetermined;
  String? _installationId;
  String? _token;
  String? _userId;
  Map<String, String> _tags = <String, String>{};

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  Future<void> _refresh() async {
    final PushPermissionStatus permission = await ARYPush.getPermissionStatus();
    final String? installationId = await ARYPush.getInstallationId();
    final String? token = await ARYPush.getPushToken();
    final String? userId = await ARYPush.getUserId();
    final Map<String, String> tags = await ARYPush.getTags();

    if (!mounted) {
      return;
    }
    setState(() {
      _permission = permission;
      _installationId = installationId;
      _token = token;
      _userId = userId;
      _tags = tags;
    });
  }

  @override
  Widget build(BuildContext context) {
    final String tokenLabel =
        _token == null ? '-' : '${_token!.substring(0, 12)}...';

    return Scaffold(
      appBar: AppBar(title: const Text('ARY Push')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: <Widget>[
          Text('Permission: ${_permission.name}'),
          Text('Installation: ${_installationId ?? '-'}'),
          Text('Token: $tokenLabel'),
          Text('User: ${_userId ?? 'logged out'}'),
          Text('Tags: $_tags'),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: () async {
              await ARYPush.requestPermission();
              await _refresh();
            },
            child: const Text('Request permission'),
          ),
          FilledButton(
            onPressed: () async {
              await ARYPush.login('USER_123');
              await _refresh();
            },
            child: const Text('Log in as USER_123'),
          ),
          FilledButton(
            onPressed: () async {
              await ARYPush.logout();
              await _refresh();
            },
            child: const Text('Log out'),
          ),
          FilledButton(
            onPressed: () async {
              await ARYPush.addTags(<String, String>{
                'subscription': 'premium',
                'language': 'en',
              });
              await _refresh();
            },
            child: const Text('Tag: premium, English'),
          ),
          FilledButton(
            onPressed: () async {
              await ARYPush.removeAllTags();
              await _refresh();
            },
            child: const Text('Clear tags'),
          ),
          FilledButton(
            onPressed: () async {
              await ARYPush.subscribeToTopic('sports');
            },
            child: const Text('Subscribe to sports'),
          ),
        ],
      ),
    );
  }
}

/// The screen a notification tap routes to.
class OrderPage extends StatelessWidget {
  /// Creates the order page.
  const OrderPage({super.key});

  @override
  Widget build(BuildContext context) {
    final Object? orderId = ModalRoute.of(context)?.settings.arguments;
    return Scaffold(
      appBar: AppBar(title: const Text('Order')),
      body: Center(child: Text('Order ${orderId ?? 'unknown'}')),
    );
  }
}

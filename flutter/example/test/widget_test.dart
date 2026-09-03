import 'package:ary_push_example/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// A smoke test for the example's own UI.
///
/// The platform channel is mocked, so this runs without a device and without the native SDK.
/// It is here to catch the example rotting: it is the first thing a developer integrating the
/// SDK reads, and a broken example is worse than no example.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel methodChannel = MethodChannel('ary_push/methods');
  const MethodChannel eventChannel = MethodChannel('ary_push/events');

  final TestDefaultBinaryMessenger messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(methodChannel, (MethodCall call) async {
      switch (call.method) {
        case 'getPermissionStatus':
          return 'granted';
        case 'getInstallationId':
          return '8f14e45f-ea1e-4f7a-b2c1-5b2d7a1c9e33';
        case 'getPushToken':
          return 'a-fake-push-token-long-enough-to-truncate';
        case 'getUserId':
          return 'USER_123';
        case 'getTags':
          return <Object?, Object?>{'subscription': 'premium'};
        default:
          return null;
      }
    });
    messenger.setMockMethodCallHandler(
        eventChannel, (MethodCall call) async => null);
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(methodChannel, null);
    messenger.setMockMethodCallHandler(eventChannel, null);
  });

  testWidgets('the home page shows the SDK state it reads',
      (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: HomePage()));
    await tester.pumpAndSettle();

    expect(find.textContaining('Permission: granted'), findsOneWidget);
    expect(find.textContaining('User: USER_123'), findsOneWidget);
    expect(find.textContaining('subscription'), findsOneWidget);
    expect(find.text('Request permission'), findsOneWidget);
  });

  testWidgets('the order page renders the id a notification routed to it', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        onGenerateRoute: (RouteSettings settings) => MaterialPageRoute<void>(
          builder: (_) => const OrderPage(),
          settings: const RouteSettings(arguments: '12345'),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Order 12345'), findsOneWidget);
  });
}

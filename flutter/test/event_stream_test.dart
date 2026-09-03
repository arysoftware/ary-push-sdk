import 'dart:async';

import 'package:ary_push/ary_push.dart';
import 'package:ary_push/src/platform_channel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel eventControlChannel =
      MethodChannel(ARYPushPlatform.eventChannelName);
  const StandardMethodCodec codec = StandardMethodCodec();

  final TestDefaultBinaryMessenger messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<String> controlCalls;

  /// Pushes one event up the event channel, exactly as the native plugins do.
  Future<void> emit(String type, Map<String, Object?> payload) async {
    await messenger.handlePlatformMessage(
      ARYPushPlatform.eventChannelName,
      codec.encodeSuccessEnvelope(<String, Object?>{
        'type': type,
        'payload': payload,
      }),
      (ByteData? _) {},
    );
  }

  setUp(() {
    ARYPushPlatform.instance = ARYPushPlatform.forTesting();
    controlCalls = <String>[];
    messenger.setMockMethodCallHandler(eventControlChannel,
        (MethodCall call) async {
      controlCalls.add(call.method);
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(eventControlChannel, null);
  });

  test('received and opened events are demultiplexed from one channel',
      () async {
    final List<String> received = <String>[];
    final List<String> opened = <String>[];

    final StreamSubscription<PushNotification> receivedSub = ARYPush
        .onNotificationReceived
        .listen((PushNotification n) => received.add(n.id));
    final StreamSubscription<PushNotification> openedSub = ARYPush
        .onNotificationOpened
        .listen((PushNotification n) => opened.add(n.id));

    await emit('received', <String, Object?>{'id': 'r1'});
    await emit('opened', <String, Object?>{'id': 'o1'});
    await emit('received', <String, Object?>{'id': 'r2'});

    expect(received, <String>['r1', 'r2']);
    expect(opened, <String>['o1']);

    await receivedSub.cancel();
    await openedSub.cancel();
  });

  test('token events reach only the token stream', () async {
    final List<String> tokens = <String>[];
    final List<String> received = <String>[];

    final StreamSubscription<String> tokenSub =
        ARYPush.onTokenRefresh.listen(tokens.add);
    final StreamSubscription<PushNotification> receivedSub = ARYPush
        .onNotificationReceived
        .listen((PushNotification n) => received.add(n.id));

    await emit('token', <String, Object?>{'token': 'abc123'});

    expect(tokens, <String>['abc123']);
    expect(received, isEmpty);

    await tokenSub.cancel();
    await receivedSub.cancel();
  });

  test('a blank token is not emitted', () async {
    final List<String> tokens = <String>[];
    final StreamSubscription<String> sub =
        ARYPush.onTokenRefresh.listen(tokens.add);

    await emit('token', <String, Object?>{'token': ''});
    await emit('token', <String, Object?>{});

    expect(tokens, isEmpty);
    await sub.cancel();
  });

  test('several listeners share one native subscription', () async {
    // One native listener however many Dart streams are subscribed. This is what stops a hot
    // restart, or a second screen listening, from producing duplicate events.
    final StreamSubscription<PushNotification> first =
        ARYPush.onNotificationReceived.listen((_) {});
    final StreamSubscription<PushNotification> second =
        ARYPush.onNotificationOpened.listen((_) {});
    final StreamSubscription<String> third =
        ARYPush.onTokenRefresh.listen((_) {});

    expect(controlCalls.where((String call) => call == 'listen').length, 1);

    await first.cancel();
    await second.cancel();
    await third.cancel();
  });

  test('a malformed event does not break the stream', () async {
    final List<String> received = <String>[];
    final StreamSubscription<PushNotification> sub = ARYPush
        .onNotificationReceived
        .listen((PushNotification n) => received.add(n.id));

    // A native SDK sending something unexpected must not tear down the application's listener.
    await messenger.handlePlatformMessage(
      ARYPushPlatform.eventChannelName,
      codec.encodeSuccessEnvelope('not a map'),
      (ByteData? _) {},
    );
    await emit('received', <String, Object?>{'id': 'r1'});

    expect(received, <String>['r1']);
    await sub.cancel();
  });
}

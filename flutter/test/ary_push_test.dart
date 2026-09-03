import 'package:ary_push/ary_push.dart';
import 'package:ary_push/src/platform_channel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const MethodChannel methodChannel =
      MethodChannel(ARYPushPlatform.methodChannelName);
  const MethodChannel eventControlChannel =
      MethodChannel(ARYPushPlatform.eventChannelName);

  final TestDefaultBinaryMessenger messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<MethodCall> calls;
  late Map<String, Object?> responses;

  setUp(() {
    ARYPushPlatform.instance = ARYPushPlatform.forTesting();
    calls = <MethodCall>[];
    responses = <String, Object?>{};

    messenger.setMockMethodCallHandler(methodChannel, (MethodCall call) async {
      calls.add(call);
      return responses[call.method];
    });
    // The event channel's listen and cancel calls travel over a method channel of the same name.
    messenger.setMockMethodCallHandler(eventControlChannel,
        (MethodCall call) async {
      calls.add(call);
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(methodChannel, null);
    messenger.setMockMethodCallHandler(eventControlChannel, null);
  });

  MethodCall callTo(String method) =>
      calls.firstWhere((MethodCall call) => call.method == method);

  group('initialization', () {
    test('initialize sends no configuration when none is given', () async {
      await ARYPush.initialize();

      expect(callTo('initialize').arguments, isNull);
    });

    test('initialize forwards the whole configuration', () async {
      await ARYPush.initialize(
        const ARYPushConfig(
          enableLogging: true,
          logLevel: PushLogLevel.debug,
          defaultChannelId: 'general',
          foregroundDisplay: ForegroundDisplayPolicy.eventOnly,
          backend: PushBackendConfig(
            baseUrl: 'https://push-api.ary.com',
            applicationId: 'wallet_flutter',
          ),
        ),
      );

      final Map<Object?, Object?> arguments =
          callTo('initialize').arguments as Map<Object?, Object?>;
      expect(arguments['enableLogging'], isTrue);
      expect(arguments['logLevel'], 'debug');
      expect(arguments['defaultChannelId'], 'general');
      expect(arguments['foregroundDisplay'], 'eventOnly');

      final Map<Object?, Object?> backend =
          arguments['backend']! as Map<Object?, Object?>;
      expect(backend['baseUrl'], 'https://push-api.ary.com');
      expect(backend['applicationId'], 'wallet_flutter');
      expect(backend['apiVersion'], 'v1');
    });
  });

  group('permission', () {
    test('requestPermission parses the native status', () async {
      responses['requestPermission'] = 'granted';

      expect(await ARYPush.requestPermission(), PushPermissionStatus.granted);
    });

    test('an unrecognised status never reads as authorized', () async {
      responses['getPermissionStatus'] = 'something_new';

      final PushPermissionStatus status = await ARYPush.getPermissionStatus();
      expect(status, PushPermissionStatus.notDetermined);
      expect(status.isAuthorized, isFalse);
    });
  });

  group('identity and tags', () {
    test('login forwards the user id', () async {
      await ARYPush.login('USER_123');

      expect(
          callTo('login').arguments, <String, dynamic>{'userId': 'USER_123'});
    });

    test('addTag is expressed as a one-entry addTags', () async {
      await ARYPush.addTag('subscription', 'premium');

      expect(
        callTo('addTags').arguments,
        <String, dynamic>{
          'tags': <String, String>{'subscription': 'premium'},
        },
      );
    });

    test('removeTag is expressed as a one-entry removeTags', () async {
      await ARYPush.removeTag('subscription');

      expect(
        callTo('removeTags').arguments,
        <String, dynamic>{
          'keys': <String>['subscription'],
        },
      );
    });

    test('getTags coerces the native map to strings', () async {
      responses['getTags'] = <Object?, Object?>{
        'subscription': 'premium',
        'count': 3
      };

      expect(
        await ARYPush.getTags(),
        <String, String>{'subscription': 'premium', 'count': '3'},
      );
    });

    test('getTags survives a null response', () async {
      expect(await ARYPush.getTags(), isEmpty);
    });
  });

  group('topics', () {
    test('subscribeToTopic reports the native result', () async {
      responses['subscribeToTopic'] = true;

      expect(await ARYPush.subscribeToTopic('sports'), isTrue);
      expect(callTo('subscribeToTopic').arguments,
          <String, dynamic>{'topic': 'sports'});
    });

    test('a null result is treated as failure rather than success', () async {
      expect(await ARYPush.subscribeToTopic('sports'), isFalse);
    });

    test('getSubscribedTopics returns a set', () async {
      responses['getSubscribedTopics'] = <Object?>['sports', 'news'];

      expect(await ARYPush.getSubscribedTopics(), <String>{'sports', 'news'});
    });
  });

  group('errors', () {
    test('a platform error becomes a typed ARYPushException', () async {
      messenger.setMockMethodCallHandler(methodChannel,
          (MethodCall call) async {
        throw PlatformException(
            code: 'not_initialized', message: 'call initialize() first');
      });

      expect(
        () => ARYPush.login('USER_1'),
        throwsA(
          isA<ARYPushException>().having(
              (ARYPushException e) => e.code, 'code', 'not_initialized'),
        ),
      );
    });

    test('an unregistered plugin reports an actionable error', () async {
      messenger.setMockMethodCallHandler(methodChannel, null);

      expect(
        () => ARYPush.getInstallationId(),
        throwsA(
          isA<ARYPushException>().having(
              (ARYPushException e) => e.code, 'code', 'unsupported_platform'),
        ),
      );
    });
  });
}

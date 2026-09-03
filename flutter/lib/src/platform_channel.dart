import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'exceptions.dart';
import 'models.dart';

/// The single seam between Dart and the native SDKs.
///
/// Everything above this class is a typed convenience; everything below it is the real
/// implementation, in Kotlin and Swift. Keeping the bridge this thin is the point: a second
/// notification engine written in Dart would duplicate FCM and APNs handling, the token
/// lifecycle, storage and deduplication, and the two copies would drift.
class ARYPushPlatform {
  ARYPushPlatform._();

  /// Creates an isolated instance so a test starts from a clean event stream.
  @visibleForTesting
  factory ARYPushPlatform.forTesting() = ARYPushPlatform._;

  /// The shared instance. Replaceable in tests.
  static ARYPushPlatform instance = ARYPushPlatform._();

  /// Method channel name, shared with the native plugins.
  static const String methodChannelName = 'ary_push/methods';

  /// Event channel name, shared with the native plugins.
  static const String eventChannelName = 'ary_push/events';

  @visibleForTesting
  MethodChannel methodChannel = const MethodChannel(methodChannelName);

  @visibleForTesting
  EventChannel eventChannel = const EventChannel(eventChannelName);

  Stream<Map<Object?, Object?>>? _events;

  /// One broadcast stream over the native event channel.
  ///
  /// A single channel carries every event type rather than one channel per stream. That means
  /// exactly one native listener however many Dart streams are subscribed, which is what keeps
  /// hot restart and engine recreation from accumulating duplicate listeners.
  Stream<Map<Object?, Object?>> get events {
    return _events ??= eventChannel
        .receiveBroadcastStream()
        .map<Map<Object?, Object?>>(
          (Object? event) =>
              event is Map<Object?, Object?> ? event : <Object?, Object?>{},
        )
        .asBroadcastStream();
  }

  /// Events of one type, unwrapped to their payload.
  Stream<Map<Object?, Object?>> eventsOfType(String type) => events
          .where((Map<Object?, Object?> event) => event['type'] == type)
          .map<Map<Object?, Object?>>((Map<Object?, Object?> event) {
        final Object? payload = event['payload'];
        return payload is Map<Object?, Object?>
            ? payload
            : <Object?, Object?>{};
      });

  /// Invokes a native method, translating platform errors into [ARYPushException].
  Future<T?> invoke<T>(String method, [Map<String, dynamic>? arguments]) async {
    try {
      return await methodChannel.invokeMethod<T>(method, arguments);
    } on PlatformException catch (error) {
      throw ARYPushException(error.code, error.message, error.details);
    } on MissingPluginException {
      // The plugin is not registered on this platform. Throwing a typed error beats a raw
      // MissingPluginException, which tells an application developer nothing actionable.
      throw const ARYPushException(
        'unsupported_platform',
        'The ARY Push SDK supports Android and iOS only.',
      );
    }
  }

  /// Invokes a native method returning a string map.
  Future<Map<String, String>> invokeStringMap(String method) async {
    final Map<Object?, Object?>? result =
        await invoke<Map<Object?, Object?>>(method);
    if (result == null) {
      return <String, String>{};
    }
    return result.map(
      (Object? key, Object? value) =>
          MapEntry<String, String>(key.toString(), value?.toString() ?? ''),
    );
  }

  /// Reads the notification that launched the application, if any.
  Future<PushNotification?> initialNotification() async {
    final Map<Object?, Object?>? result =
        await invoke<Map<Object?, Object?>>('getInitialNotification');
    return result == null ? null : PushNotification.fromMap(result);
  }
}

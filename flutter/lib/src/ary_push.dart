import 'dart:async';

import 'config.dart';
import 'models.dart';
import 'platform_channel.dart';

/// The ARY Push SDK.
///
/// Adding push to an existing Flutter application is one dependency and one line:
///
/// ```dart
/// Future<void> main() async {
///   WidgetsFlutterBinding.ensureInitialized();
///   await ARYPush.initialize();
///   runApp(const MyApp());
/// }
/// ```
///
/// Everything else is optional. The native SDKs handle permission, the push token and its
/// refreshes, the installation identity, receiving, displaying and opening notifications,
/// channels, topics, tags, user identity, local storage, backend synchronisation, the offline
/// queue, retries and deduplication.
///
/// What the SDK never does is navigate. Taps arrive on [onNotificationOpened] carrying the
/// payload, and the application's own router decides where the user lands.
class ARYPush {
  const ARYPush._();

  static ARYPushPlatform get _platform => ARYPushPlatform.instance;

  // ---------------------------------------------------------------- initialization

  /// Initializes the SDK.
  ///
  /// Idempotent and safe to call more than once: repeated calls reuse the existing native
  /// instance and never create a second set of listeners, installation ids or event streams.
  ///
  /// Call it after `WidgetsFlutterBinding.ensureInitialized()` and before `runApp`, so listeners
  /// attached during startup still see a notification tap that launched the application.
  static Future<void> initialize([ARYPushConfig? config]) async {
    await _platform.invoke<void>('initialize', config?.toMap());
  }

  /// Whether the native SDK has been initialized in this process.
  static Future<bool> get isInitialized async =>
      await _platform.invoke<bool>('isInitialized') ?? false;

  // ---------------------------------------------------------------- permission

  /// Requests notification permission, showing the system prompt when one is possible.
  ///
  /// Returns the resulting status. On iOS the prompt can only ever be shown once per install, so
  /// call this at a moment the user understands rather than on first launch.
  ///
  /// When a prompt is no longer possible (already granted, or permanently denied), the current
  /// status is returned without showing anything.
  static Future<PushPermissionStatus> requestPermission() async {
    final String? result = await _platform.invoke<String>('requestPermission');
    return PushPermissionStatus.fromWire(result);
  }

  /// Current notification permission state.
  ///
  /// Reflects both the runtime permission and the application-level notification toggle, so
  /// [PushPermissionStatus.denied] genuinely means nothing will be shown.
  static Future<PushPermissionStatus> getPermissionStatus() async {
    final String? result =
        await _platform.invoke<String>('getPermissionStatus');
    return PushPermissionStatus.fromWire(result);
  }

  /// Opens this application's notification settings.
  ///
  /// The escape hatch for a user who has already denied and can no longer be shown a prompt.
  static Future<void> openNotificationSettings() async {
    await _platform.invoke<void>('openNotificationSettings');
  }

  // ---------------------------------------------------------------- identity and token

  /// The SDK's installation identifier for this app on this device.
  ///
  /// Stable across token refreshes, logins and logouts.
  static Future<String?> getInstallationId() =>
      _platform.invoke<String>('getInstallationId');

  /// The current push token.
  ///
  /// Null before one has been issued. The application never has to send this to the backend: the
  /// SDK registers and re-registers it automatically.
  static Future<String?> getPushToken() =>
      _platform.invoke<String>('getPushToken');

  /// Which transport issued [getPushToken].
  static Future<PushProvider> getPushProvider() async =>
      PushProvider.fromWire(await _platform.invoke<String>('getPushProvider'));

  /// Hands the SDK an FCM registration token.
  ///
  /// Only needed on **iOS**, and only when the application sends through Firebase rather than
  /// straight to APNs. iOS gives the SDK an APNs device token, which is a different value from an
  /// FCM registration token; a backend that sends through Firebase must be given the Firebase one,
  /// and only Firebase knows it.
  ///
  /// ```dart
  /// FirebaseMessaging.instance.onTokenRefresh.listen(ARYPush.setFCMToken);
  /// final String? token = await FirebaseMessaging.instance.getToken();
  /// if (token != null) await ARYPush.setFCMToken(token);
  /// ```
  ///
  /// Accepted and ignored on Android, where FCM tokens reach the SDK directly, so this can be
  /// called unconditionally from shared code.
  static Future<void> setFCMToken(String token) =>
      _platform.invoke<void>('setFCMToken', <String, Object?>{'token': token});

  /// Emits whenever the push token changes.
  ///
  /// A token that arrived before this stream was listened to is replayed, so a subscription set
  /// up during startup never misses the first token.
  static Stream<String> get onTokenRefresh => _platform
      .eventsOfType('token')
      .map((Map<Object?, Object?> payload) => payload['token'] as String? ?? '')
      .where((String token) => token.isNotEmpty);

  // ---------------------------------------------------------------- user identity

  /// Associates this installation with a user.
  ///
  /// Local state changes immediately, so an offline login is true from the application's point
  /// of view straight away; the backend catches up through the durable queue.
  static Future<void> login(String userId) async {
    await _platform.invoke<void>('login', <String, dynamic>{'userId': userId});
  }

  /// Clears the user association.
  ///
  /// Deliberately narrow: the installation id, the push token and the device registration all
  /// survive, so the device keeps receiving unauthenticated campaigns. Logout is not
  /// unregistration.
  static Future<void> logout() async {
    await _platform.invoke<void>('logout');
  }

  /// The currently associated user, or null when logged out.
  static Future<String?> getUserId() => _platform.invoke<String>('getUserId');

  // ---------------------------------------------------------------- tags

  /// Sets one tag.
  ///
  /// Tags are attributes the backend builds segments from: `subscription=premium`,
  /// `language=en`, `country=PK`. Consecutive calls are coalesced into a single request.
  static Future<void> addTag(String key, String value) =>
      addTags(<String, String>{key: value});

  /// Sets several tags at once.
  static Future<void> addTags(Map<String, String> tags) async {
    await _platform.invoke<void>('addTags', <String, dynamic>{'tags': tags});
  }

  /// Removes one tag.
  static Future<void> removeTag(String key) => removeTags(<String>{key});

  /// Removes several tags.
  static Future<void> removeTags(Set<String> keys) async {
    await _platform.invoke<void>(
      'removeTags',
      <String, dynamic>{'keys': keys.toList()},
    );
  }

  /// Removes every tag.
  static Future<void> removeAllTags() async {
    await _platform.invoke<void>('removeAllTags');
  }

  /// Tags currently held for this installation, read from local storage.
  static Future<Map<String, String>> getTags() =>
      _platform.invokeStringMap('getTags');

  // ---------------------------------------------------------------- segments

  /// Lists the segments defined for the project.
  ///
  /// Backed by `GET /api/segments/list`. This is the project's segment catalogue — every segment
  /// a device could be added with [subscribeToSegment] — not the segments this installation
  /// belongs to: the push API has no per-installation membership read.
  ///
  /// An unreachable backend, or no backend at all, yields an empty list rather than throwing.
  static Future<List<Segment>> getSegments() async {
    final List<Object?>? result =
        await _platform.invoke<List<Object?>>('getSegments');
    if (result == null) {
      return const <Segment>[];
    }
    return result
        .whereType<Map<Object?, Object?>>()
        .map(Segment.fromMap)
        .toList(growable: false);
  }

  /// Adds this installation to a segment.
  ///
  /// Backed by `POST /api/segments/{segmentId}/subscribers`, which receives the full installation
  /// record. Take [segmentId] from [getSegments].
  ///
  /// Resolves to whether the server accepted it. This is a direct request, not a queued one, so it
  /// resolves to `false` while offline rather than reporting an optimistic success.
  static Future<bool> subscribeToSegment(String segmentId) async {
    if (segmentId.trim().isEmpty) {
      throw ArgumentError.value(segmentId, 'segmentId', 'must not be blank');
    }
    return await _platform.invoke<bool>(
          'subscribeToSegment',
          <String, Object?>{'segmentId': segmentId},
        ) ??
        false;
  }

  /// Whether a segment with this name or id exists in the project.
  ///
  /// The push API exposes no per-installation membership, so this can only see the project's
  /// segment list — it no longer answers whether this installation is in the segment.
  @Deprecated(
    'The push API has no membership read. This reports whether the segment exists in the '
    'project, not whether this installation is in it. Use getSegments().',
  )
  static Future<bool> isInSegment(String name) async {
    final List<Segment> segments = await getSegments();
    return segments.any(
      (Segment segment) =>
          segment.name.toLowerCase() == name.toLowerCase() ||
          segment.id == name,
    );
  }

  // ---------------------------------------------------------------- topics

  /// Subscribes this device to a topic.
  ///
  /// Topic names are validated before the call is made, so an invalid name fails visibly instead
  /// of being silently dropped by the server.
  ///
  /// A topic is not a segment: topics are opted into by the device, segments are computed by the
  /// backend from tags.
  static Future<bool> subscribeToTopic(String topic) async {
    final bool? result = await _platform.invoke<bool>(
      'subscribeToTopic',
      <String, dynamic>{'topic': topic},
    );
    return result ?? false;
  }

  /// Unsubscribes this device from a topic.
  static Future<bool> unsubscribeFromTopic(String topic) async {
    final bool? result = await _platform.invoke<bool>(
      'unsubscribeFromTopic',
      <String, dynamic>{'topic': topic},
    );
    return result ?? false;
  }

  /// Topics this device is recorded as subscribed to.
  static Future<Set<String>> getSubscribedTopics() async {
    final List<Object?>? result =
        await _platform.invoke<List<Object?>>('getSubscribedTopics');
    return result?.map((Object? topic) => topic.toString()).toSet() ??
        <String>{};
  }

  // ---------------------------------------------------------------- notification events

  /// Emits when a message arrives while the application is running.
  ///
  /// Not emitted for messages the system displayed on its own while the application was
  /// backgrounded: neither Android nor iOS tells an application about those until they are
  /// tapped. See docs/NOTIFICATION_LIFECYCLE.md.
  static Stream<PushNotification> get onNotificationReceived =>
      _platform.eventsOfType('received').map(PushNotification.fromMap);

  /// Emits when the user taps a notification, or one of its action buttons.
  ///
  /// A tap that happened while the application was terminated is not lost: the native SDK
  /// persists it and replays it when this stream is first listened to, so subscribing during
  /// startup always sees it.
  ///
  /// The SDK does not navigate. Read [PushNotification.data] and route from here.
  static Stream<PushNotification> get onNotificationOpened =>
      _platform.eventsOfType('opened').map(PushNotification.fromMap);

  /// The notification that launched the application, if it has not been delivered yet.
  ///
  /// Most applications should listen to [onNotificationOpened] instead, which replays the same
  /// event. This exists for code that prefers to pull once during startup.
  static Future<PushNotification?> getInitialNotification() =>
      _platform.initialNotification();

  // ---------------------------------------------------------------- events and maintenance

  /// Records a push-related event.
  ///
  /// Scope is push: delivery and engagement attribution on the push backend. This is not an
  /// analytics SDK and should not be used as one.
  static Future<void> trackEvent(
    String name, [
    Map<String, String> properties = const <String, String>{},
  ]) async {
    await _platform.invoke<void>('trackEvent', <String, dynamic>{
      'name': name,
      'properties': properties,
    });
  }

  /// Sends anything the SDK is holding back.
  ///
  /// Tag writes are debounced and queued operations wait for connectivity, so an application
  /// about to be killed can call this to stop waiting.
  static Future<void> flush() async {
    await _platform.invoke<void>('flush');
  }
}

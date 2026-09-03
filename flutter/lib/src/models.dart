import 'package:flutter/foundation.dart';

/// Platform-neutral representation of a push notification.
///
/// Android FCM payloads and iOS APNs payloads are normalised natively and arrive here in the
/// same shape, so Flutter code is identical on both platforms.
///
/// The SDK never interprets [data]. It is delivered verbatim, and the application decides what
/// an action means and where to navigate.
@immutable
class PushNotification {
  /// Creates a notification. Applications receive these from the SDK rather than building them.
  const PushNotification({
    required this.id,
    required this.receivedAt,
    this.title,
    this.body,
    this.imageUrl,
    this.data = const <String, dynamic>{},
    this.channelId,
    this.threadId,
    this.categoryId,
    this.collapseKey,
    this.actionId,
    this.wasForeground = false,
  });

  /// Stable identifier used for deduplication.
  final String id;

  /// Notification title, when the payload carries one.
  final String? title;

  /// Notification body, when the payload carries one.
  final String? body;

  /// Remote image rendered with the notification, when the payload carries one.
  final String? imageUrl;

  /// The custom data payload, delivered verbatim.
  final Map<String, dynamic> data;

  /// When the native SDK received the message.
  final DateTime receivedAt;

  /// Android notification channel the message was posted to.
  final String? channelId;

  /// iOS thread identifier, used to group notifications.
  final String? threadId;

  /// iOS category, which selects the registered action buttons.
  final String? categoryId;

  /// Android collapse key, when present.
  final String? collapseKey;

  /// Identifier of the action button the user tapped.
  ///
  /// Null when the notification body itself was tapped, and always null on received events.
  final String? actionId;

  /// True when the message arrived while the application was in the foreground.
  final bool wasForeground;

  /// Conventional data key carrying an application-defined action name.
  static const String actionKey = 'action';

  /// Convenience accessor for the conventional `action` data key.
  String? get action => data[actionKey] as String?;

  /// Reconstructs a notification from the platform channel payload.
  factory PushNotification.fromMap(Map<Object?, Object?> map) {
    final Object? rawData = map['data'];
    return PushNotification(
      id: map['id'] as String? ?? '',
      title: map['title'] as String?,
      body: map['body'] as String?,
      imageUrl: map['imageUrl'] as String?,
      data: rawData is Map
          ? rawData.map(
              (Object? key, Object? value) =>
                  MapEntry<String, dynamic>(key.toString(), value),
            )
          : const <String, dynamic>{},
      receivedAt: _timestamp(map['receivedAt']),
      channelId: map['channelId'] as String?,
      threadId: map['threadId'] as String?,
      categoryId: map['categoryId'] as String?,
      collapseKey: map['collapseKey'] as String?,
      actionId: map['actionId'] as String?,
      wasForeground: map['wasForeground'] as bool? ?? false,
    );
  }

  /// Native platforms report milliseconds since the epoch; anything else falls back to now.
  static DateTime _timestamp(Object? value) {
    if (value is int) {
      return DateTime.fromMillisecondsSinceEpoch(value);
    }
    if (value is double) {
      return DateTime.fromMillisecondsSinceEpoch(value.round());
    }
    return DateTime.now();
  }

  @override
  String toString() =>
      'PushNotification(id: $id, title: $title, action: $action)';

  @override
  bool operator ==(Object other) =>
      other is PushNotification &&
      other.id == id &&
      other.actionId == actionId &&
      other.receivedAt == receivedAt;

  @override
  int get hashCode => Object.hash(id, actionId, receivedAt);
}

/// Notification permission state, normalised across Android and iOS.
///
/// Android only ever reports [notDetermined], [granted] or [denied]. The remaining values exist
/// so the same model can describe iOS, keeping application code portable.
enum PushPermissionStatus {
  /// The user has not been asked yet.
  notDetermined,

  /// Notifications may be shown.
  granted,

  /// The user declined, or notifications are switched off for the application.
  denied,

  /// iOS only: quiet delivery granted without an explicit prompt.
  provisional,

  /// iOS only: temporary authorization granted to an App Clip.
  ephemeral,

  /// iOS only: notifications are restricted by policy and cannot be requested.
  restricted;

  /// True when the SDK is allowed to show notifications, quietly or otherwise.
  bool get isAuthorized =>
      this == granted || this == provisional || this == ephemeral;

  /// Parses the value reported by the native SDK.
  static PushPermissionStatus fromWire(String? value) {
    for (final PushPermissionStatus status in PushPermissionStatus.values) {
      if (status.name.toLowerCase() == value?.toLowerCase()) {
        return status;
      }
    }
    // An unrecognised value means a native SDK newer than this package. Reporting
    // notDetermined is the safe answer: it never claims an authorization the app may not have.
    return PushPermissionStatus.notDetermined;
  }
}

/// Transport that issued the current push token.
enum PushProvider {
  /// Firebase Cloud Messaging.
  fcm,

  /// Apple Push Notification service, used directly.
  apns;

  /// Parses the value reported by the native SDK.
  static PushProvider fromWire(String? value) =>
      value == 'apns' ? PushProvider.apns : PushProvider.fcm;
}

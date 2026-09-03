import 'package:flutter/foundation.dart';

/// Verbosity of the native SDK logger.
enum PushLogLevel {
  /// Everything, including per-request detail.
  verbose,

  /// Diagnostic detail useful while integrating.
  debug,

  /// Lifecycle milestones. The default.
  info,

  /// Recoverable problems only.
  warning,

  /// Failures only.
  error,

  /// Nothing at all.
  none,
}

/// What the SDK does with a notification that arrives while the app is in the foreground.
enum ForegroundDisplayPolicy {
  /// Show the notification and emit `onNotificationReceived`. The default.
  show,

  /// Show nothing, but still emit `onNotificationReceived`.
  ///
  /// Use this when the application already shows its own in-app banner, so the user never sees
  /// the same message twice.
  eventOnly,

  /// Show nothing and emit nothing.
  suppress,
}

/// Points the SDK at one of ARY's push API environments.
///
/// Environment selection belongs to the consuming application: the SDK never hardcodes a URL and
/// never needs rebuilding for development, QA, staging or production. Omit it entirely and every
/// push feature still works, with no server at all.
@immutable
class PushBackendConfig {
  /// Creates a backend configuration.
  const PushBackendConfig({
    required this.baseUrl,
    this.applicationId,
    this.apiVersion = 'v1',
  });

  /// Base URL of the push API, for example `https://push-api.ary.com`.
  ///
  /// Must be HTTPS in production.
  final String baseUrl;

  /// Optional PUBLIC application identifier, for example `wallet_flutter`.
  ///
  /// This distinguishes applications on the backend. It is not a credential, it is not secret,
  /// and the backend must never treat it as authentication.
  final String? applicationId;

  /// API version prefix. Endpoints are always versioned.
  final String apiVersion;

  /// Platform channel representation.
  Map<String, dynamic> toMap() => <String, dynamic>{
        'baseUrl': baseUrl,
        'applicationId': applicationId,
        'apiVersion': apiVersion,
      };
}

/// Optional configuration for [ARYPush.initialize].
///
/// Every value has a working default, so the minimal integration is a single line:
///
/// ```dart
/// await ARYPush.initialize();
/// ```
@immutable
class ARYPushConfig {
  /// Creates a configuration. Every argument is optional.
  const ARYPushConfig({
    this.enableLogging = false,
    this.logLevel = PushLogLevel.info,
    this.autoRequestPermission = false,
    this.defaultChannelId,
    this.defaultChannelName,
    this.foregroundDisplay = ForegroundDisplayPolicy.show,
    this.displayNotifications = true,
    this.backend,
    this.collectDeviceInfo = true,
  });

  /// Emit native SDK logs. Keep this off in release builds.
  final bool enableLogging;

  /// Minimum level emitted when [enableLogging] is true.
  final PushLogLevel logLevel;

  /// Ask for notification permission during initialization.
  ///
  /// Off by default: prompting on first launch, out of context, is the most common cause of a
  /// permanent denial. Call [ARYPush.requestPermission] at a moment that makes sense to the
  /// user instead.
  final bool autoRequestPermission;

  /// Android only: identifier of the channel used when a message does not name one.
  final String? defaultChannelId;

  /// Android only: user-visible name of the default channel.
  final String? defaultChannelName;

  /// What to do with a message that arrives while the application is in the foreground.
  final ForegroundDisplayPolicy foregroundDisplay;

  /// Whether the SDK displays notifications at all.
  ///
  /// Set to false when the application already renders its own notifications from the raw
  /// payload; the SDK then only handles tokens, identity, tags and events.
  final bool displayNotifications;

  /// Backend environment. Omit for a fully local, server-less integration.
  final PushBackendConfig? backend;

  /// Send device model, OS version, locale and timezone with the installation record.
  final bool collectDeviceInfo;

  /// Platform channel representation.
  Map<String, dynamic> toMap() => <String, dynamic>{
        'enableLogging': enableLogging,
        'logLevel': logLevel.name,
        'autoRequestPermission': autoRequestPermission,
        'defaultChannelId': defaultChannelId,
        'defaultChannelName': defaultChannelName,
        'foregroundDisplay': foregroundDisplay.name,
        'displayNotifications': displayNotifications,
        'collectDeviceInfo': collectDeviceInfo,
        'backend': backend?.toMap(),
      };
}

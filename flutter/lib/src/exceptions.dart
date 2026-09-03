/// A failure reported by the native SDK across the platform channel.
///
/// The SDK is deliberately reluctant to throw: a push or network failure is logged and absorbed
/// natively rather than surfaced, because a notification problem must not crash an application.
/// What does reach Dart as an exception is programmer error, such as calling an API before
/// [ARYPush.initialize] or passing an invalid topic name.
class ARYPushException implements Exception {
  /// Creates an exception from a platform error.
  const ARYPushException(this.code, this.message, [this.details]);

  /// Stable machine-readable code, for example `not_initialized`.
  final String code;

  /// Human-readable description.
  final String? message;

  /// Optional extra context from the native side.
  final Object? details;

  @override
  String toString() => 'ARYPushException($code): ${message ?? 'no message'}';
}

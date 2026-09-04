/// Private ARY push notification SDK for Flutter.
///
/// A thin bridge over the native Android and iOS ARY Push SDKs. There is no second
/// notification engine here: FCM and APNs handling, the token lifecycle, notification storage
/// and deduplication all live natively, and this package forwards to them.
///
/// ```dart
/// Future<void> main() async {
///   WidgetsFlutterBinding.ensureInitialized();
///   await ARYPush.initialize();
///   runApp(const MyApp());
/// }
/// ```
library ary_push;

export 'src/ary_push.dart' show ARYPush;
export 'src/config.dart'
    show
        ARYPushConfig,
        PushBackendConfig,
        ForegroundDisplayPolicy,
        PushLogLevel;
export 'src/models.dart'
    show PushNotification, PushPermissionStatus, PushProvider, Segment;
export 'src/exceptions.dart' show ARYPushException;

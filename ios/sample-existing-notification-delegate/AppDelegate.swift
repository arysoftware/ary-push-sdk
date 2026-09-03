import ARYPush
import UIKit
import UserNotifications

/// An application that already owned the notification delegate before the SDK arrived.
///
/// This is the case the SDK exists to survive. This app delegate is a
/// `UNUserNotificationCenterDelegate`, handles its own deep links, fires its own analytics and
/// implements the remote-notification callbacks. Adding the SDK changes none of it.
///
/// The only line added for push is `ARYPush.initialize(...)`. Everything below it is the
/// application's pre-existing code, still running, still receiving every callback, because the
/// SDK forwards to the delegate it found instead of replacing it.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        // The application's own delegate, installed first, exactly as it always was.
        UNUserNotificationCenter.current().delegate = self
        application.registerForRemoteNotifications()

        // The one line push integration adds. The SDK finds the delegate above, keeps it, and
        // forwards every callback to it.
        ARYPush.initialize(
            ARYPushConfig(
                enableLogging: true,
                logLevel: .debug,
                backend: PushBackendConfig(
                    baseURL: "https://push-api-dev.ary.com",
                    applicationId: "legacy_ios"
                )
            )
        )

        ARYPush.addNotificationOpenedListener { notification in
            print("ARY Push notification opened: \(notification.data)")
        }

        return true
    }

    // MARK: - The application's pre-existing delegate methods, unchanged

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Still called. The SDK unions its options with these rather than replacing them, so
        // neither side can suppress the other's banner.
        print("Application's own willPresent still runs")
        completionHandler([.sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        // Still called, so the application's existing deep links and analytics keep working.
        print("Application's own deep-link handling still runs")
        handleLegacyDeepLink(response.notification.request.content.userInfo)
        completionHandler()
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        // Still called, after the SDK has recorded the same token for its own use.
        print("Application's own token registration still runs")
        registerTokenWithLegacyBackend(deviceToken)
    }

    private func handleLegacyDeepLink(_ userInfo: [AnyHashable: Any]) {
        // The application's pre-existing routing lives here.
    }

    private func registerTokenWithLegacyBackend(_ deviceToken: Data) {
        // The application's pre-existing token upload lives here.
    }
}

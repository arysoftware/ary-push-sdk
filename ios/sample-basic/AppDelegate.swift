import ARYPush
import UIKit

/// The whole push integration for a brand-new application.
///
/// Two things happen here and nothing else: the SDK is initialized, and a listener is attached so
/// the application can route notification taps. Authorization, APNs registration, the device
/// token and its changes, the installation identity, foreground presentation, deduplication,
/// backend registration, the offline queue and retries are all handled by the SDK.
///
/// Note what is absent: no `UNUserNotificationCenter.current().delegate = self`, no
/// `didRegisterForRemoteNotificationsWithDeviceToken`, no token upload. The SDK proxies those.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {

        ARYPush.initialize(
            ARYPushConfig(
                enableLogging: true,
                logLevel: .debug,
                // Omit `backend` entirely and everything below still works, with no server.
                backend: PushBackendConfig(
                    baseURL: "https://push-api-dev.ary.com",
                    applicationId: "sample_ios"
                )
            )
        )

        // Registered here, during launch, so a tap that cold-started the process is replayed to
        // this listener rather than lost.
        ARYPush.addNotificationOpenedListener { notification in
            SampleRouter.route(notification)
        }

        ARYPush.addNotificationReceivedListener { notification in
            print("Received while running: \(notification.id)")
        }

        return true
    }
}

/// Where the host application, not the SDK, decides what a notification means.
///
/// The SDK delivers `data` verbatim and stops. Deciding that `action=open_order` leads to the
/// order screen is application knowledge, and putting it in the SDK would tie one push
/// implementation to one application's navigation.
enum SampleRouter {

    static func route(_ notification: PushNotification) {
        switch notification.action {
        case "open_order":
            let orderId = notification.data["orderId"] ?? "unknown"
            print("Navigate to order \(orderId)")
        default:
            print("Navigate to the home screen")
        }
    }
}

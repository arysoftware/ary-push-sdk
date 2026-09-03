import Foundation
import UserNotifications

/// Receives notification-centre callbacks without taking them away from the host application.
///
/// This is the single most dangerous thing a push SDK can get wrong on iOS. There is exactly one
/// `UNUserNotificationCenter.delegate`, and an SDK that simply assigns itself to it silently
/// breaks every notification the host application already handled: its deep links stop working,
/// its analytics stop firing, its own action buttons stop responding, and nothing logs an error.
///
/// So the SDK does not replace the delegate. It wraps it:
///
/// * the delegate that was already installed is kept and every callback is forwarded to it;
/// * presentation options are the **union** of what the SDK wants and what the host asked for,
///   so neither side can suppress the other's notification;
/// * a completion handler is called exactly once, even if the host's delegate calls its own
///   handler twice, or never calls it at all.
///
/// The previous delegate is held weakly, matching `UNUserNotificationCenter.delegate` itself, so
/// the proxy can never keep a host object alive or create a retain cycle.
final class NotificationCenterDelegateProxy: NSObject, UNUserNotificationCenterDelegate {

    /// How long the SDK waits for a host delegate to call its completion handler.
    ///
    /// A host delegate that never calls back would otherwise leave the notification
    /// undisplayed forever. Two seconds is long enough for any reasonable handler and short
    /// enough that the user still sees the banner.
    private static let hostCompletionTimeout: TimeInterval = 2

    private weak var previous: UNUserNotificationCenterDelegate?

    private let onWillPresent: (UNNotification) -> UNNotificationPresentationOptions
    private let onDidReceive: (UNNotificationResponse) -> Void

    init(
        previous: UNUserNotificationCenterDelegate?,
        onWillPresent: @escaping (UNNotification) -> UNNotificationPresentationOptions,
        onDidReceive: @escaping (UNNotificationResponse) -> Void
    ) {
        self.previous = previous
        self.onWillPresent = onWillPresent
        self.onDidReceive = onDidReceive
        super.init()
    }

    /// Updates the wrapped delegate when the host installs one after the SDK started.
    func setPreviousDelegate(_ delegate: UNUserNotificationCenterDelegate?) {
        guard delegate !== self else { return }
        previous = delegate
        PushLogger.debug("Host notification delegate captured for forwarding")
    }

    var wrappedDelegate: UNUserNotificationCenterDelegate? { previous }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let sdkOptions = onWillPresent(notification)

        guard let previous,
              previous.responds(
                  to: #selector(UNUserNotificationCenterDelegate.userNotificationCenter(_:willPresent:withCompletionHandler:))
              )
        else {
            completionHandler(sdkOptions)
            return
        }

        let once = OnceCompletion<UNNotificationPresentationOptions>(completionHandler)
        previous.userNotificationCenter?(center, willPresent: notification) { hostOptions in
            // Union, not replacement: if either the SDK or the host wants a banner, the user
            // sees a banner. Letting one side veto the other is how notifications go missing.
            once.complete(sdkOptions.union(hostOptions))
        }
        once.timeout(after: Self.hostCompletionTimeout, with: sdkOptions)
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        onDidReceive(response)

        guard let previous,
              previous.responds(
                  to: #selector(UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:))
              )
        else {
            completionHandler()
            return
        }

        // Wrapped rather than passed directly: Swift does not treat `() -> Void` as `(Void) -> Void`.
        let once = OnceCompletion<Void>({ _ in completionHandler() })
        previous.userNotificationCenter?(center, didReceive: response) {
            once.complete(())
        }
        once.timeout(after: Self.hostCompletionTimeout, with: ())
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        openSettingsFor notification: UNNotification?
    ) {
        // The SDK has no opinion here; it exists only so the host's implementation keeps working.
        previous?.userNotificationCenter?(center, openSettingsFor: notification)
    }
}

/// Guarantees a completion handler runs exactly once.
///
/// System completion handlers are not tolerant: calling one twice raises an exception, and never
/// calling it leaves the notification in limbo. Since one of the two callers here is host
/// application code the SDK does not control, the guarantee is enforced rather than assumed.
private final class OnceCompletion<T> {

    private let handler: (T) -> Void
    private let lock = NSLock()
    private var completed = false

    init(_ handler: @escaping (T) -> Void) {
        self.handler = handler
    }

    func complete(_ value: T) {
        lock.lock()
        let alreadyDone = completed
        completed = true
        lock.unlock()

        guard !alreadyDone else {
            PushLogger.warn("A notification completion handler was called more than once")
            return
        }
        handler(value)
    }

    /// Falls back to `value` if nothing has completed within `interval`.
    func timeout(after interval: TimeInterval, with value: T) {
        DispatchQueue.main.asyncAfter(deadline: .now() + interval) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            let alreadyDone = self.completed
            self.lock.unlock()
            guard !alreadyDone else { return }

            PushLogger.warn(
                "The host notification delegate did not call its completion handler within "
                    + "\(Int(interval))s; continuing with the SDK's own result"
            )
            self.complete(value)
        }
    }
}

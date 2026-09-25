import UserNotifications
import XCTest
@testable import ARYPush

/// Two delegates that each wrap the other, as the SDK's proxy and firebase_messaging's plugin end
/// up doing when each installs itself around whatever delegate it finds. Before the loop guard,
/// the first foreground notification went round until the stack overflowed.
final class NotificationCenterDelegateProxyTests: XCTestCase {

    /// Forwards everything to the delegate it wraps, like firebase_messaging's plugin.
    private final class ForwardingDelegate: NSObject, UNUserNotificationCenterDelegate {
        weak var original: UNUserNotificationCenterDelegate?
        var willPresentCalls = 0
        var didReceiveCalls = 0
        var openSettingsCalls = 0

        func userNotificationCenter(
            _ center: UNUserNotificationCenter,
            willPresent notification: UNNotification,
            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
        ) {
            willPresentCalls += 1
            original?.userNotificationCenter?(center, willPresent: notification,
                                              withCompletionHandler: completionHandler)
        }

        func userNotificationCenter(
            _ center: UNUserNotificationCenter,
            didReceive response: UNNotificationResponse,
            withCompletionHandler completionHandler: @escaping () -> Void
        ) {
            didReceiveCalls += 1
            original?.userNotificationCenter?(center, didReceive: response,
                                              withCompletionHandler: completionHandler)
        }

        func userNotificationCenter(
            _ center: UNUserNotificationCenter,
            openSettingsFor notification: UNNotification?
        ) {
            openSettingsCalls += 1
            original?.userNotificationCenter?(center, openSettingsFor: notification)
        }
    }

    /// The proxy only hands the centre on; `current()` needs a host app this test bundle lacks.
    private let center = unsafeBitCast(NSObject(), to: UNUserNotificationCenter.self)
    private var sdkCalls = 0
    private var other: ForwardingDelegate!
    private var proxy: NotificationCenterDelegateProxy!

    override func setUp() {
        super.setUp()
        sdkCalls = 0
        other = ForwardingDelegate()
        proxy = NotificationCenterDelegateProxy(
            previous: other,
            onWillPresent: { [unowned self] _ in
                self.sdkCalls += 1
                return [.alert, .sound]
            },
            onDidReceive: { [unowned self] _ in self.sdkCalls += 1 }
        )
        other.original = proxy
    }

    func testWillPresentInALoopRunsEachSideOnceAndShowsTheBanner() throws {
        let notification = try makeNotification(id: "loop-1")
        var results: [UNNotificationPresentationOptions] = []

        proxy.userNotificationCenter(center, willPresent: notification) { results.append($0) }

        XCTAssertEqual(sdkCalls, 1)
        XCTAssertEqual(other.willPresentCalls, 1)
        XCTAssertEqual(results, [[.alert, .sound]])
    }

    func testTheSameNotificationCanBePresentedAgainAfterwards() throws {
        let notification = try makeNotification(id: "loop-2")
        var completions = 0

        proxy.userNotificationCenter(center, willPresent: notification) { _ in completions += 1 }
        proxy.userNotificationCenter(center, willPresent: notification) { _ in completions += 1 }

        XCTAssertEqual(sdkCalls, 2)
        XCTAssertEqual(completions, 2)
    }

    func testOpenSettingsInALoopForwardsOnce() {
        proxy.userNotificationCenter(center, openSettingsFor: nil)
        XCTAssertEqual(other.openSettingsCalls, 1)
    }

    /// UNNotification has no public initializer; tests build one the way the system does.
    private func makeNotification(id: String) throws -> UNNotification {
        let content = UNMutableNotificationContent()
        content.title = "Test"
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        let selector = NSSelectorFromString("notificationWithRequest:date:")
        guard UNNotification.responds(to: selector),
              let made = UNNotification.perform(selector, with: request, with: Date())?
                  .takeUnretainedValue() as? UNNotification
        else { throw XCTSkip("UNNotification cannot be constructed on this OS") }
        return made
    }
}

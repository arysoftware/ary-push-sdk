import Foundation
import ObjectiveC
import UIKit

/// Observes the app delegate's remote-notification callbacks without owning them.
///
/// APNs hands the device token to exactly one place: `UIApplicationDelegate`. There is no
/// notification, no KVO and no API that lets a library observe it. So an SDK has two options,
/// and this file implements both:
///
/// 1. **Automatic (default).** The SDK adds its own implementation of the three
///    remote-notification selectors to the host's app delegate class. Where the host already
///    implements one, the implementations are exchanged so the host's code still runs; where it
///    does not, the method is simply added. Either way the host loses nothing.
/// 2. **Explicit.** Set `proxyApplicationDelegate: false` and forward from the app delegate to
///    ``ARYPush/didRegisterForRemoteNotifications(deviceToken:)`` and friends. Three lines,
///    no runtime manipulation, and the documented choice for teams whose policy forbids it.
///
/// The automatic path never removes an implementation, never changes behaviour the host relies
/// on, and is applied once per process.
final class AppDelegateProxy: NSObject {

    static let shared = AppDelegateProxy()

    private let lock = NSLock()
    private var installed = false

    /// Called with the raw APNs device token.
    var onAPNsToken: ((Data) -> Void)?

    /// Called when APNs registration fails.
    var onRegistrationFailure: ((Error) -> Void)?

    /// Called for a silent or background remote notification.
    var onRemoteNotification: (([AnyHashable: Any]) -> Void)?

    private override init() {
        super.init()
    }

    /// Attaches to the current app delegate. Safe to call repeatedly; only the first call acts.
    @MainActor
    func install() {
        lock.lock()
        let alreadyInstalled = installed
        installed = true
        lock.unlock()
        guard !alreadyInstalled else { return }

        guard let delegate = UIApplication.shared.delegate else {
            // Initialization ran before the app delegate was set, which happens when the SDK is
            // started from a Scene or a SwiftUI App. Retrying after the run loop turns catches
            // the delegate once UIKit has installed it.
            PushLogger.debug("No app delegate yet; retrying remote-notification proxy shortly")
            lock.lock(); installed = false; lock.unlock()
            DispatchQueue.main.async { [weak self] in self?.install() }
            return
        }

        let delegateClass: AnyClass = type(of: delegate)
        attach(to: delegateClass)
        PushLogger.info("Remote-notification callbacks proxied on \(NSStringFromClass(delegateClass))")
    }

    private func attach(to delegateClass: AnyClass) {
        swap(
            in: delegateClass,
            original: #selector(UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
            replacement: #selector(AppDelegateProxy.aryPush_application(_:didRegisterForRemoteNotificationsWithDeviceToken:)),
            types: "v@:@@"
        )
        swap(
            in: delegateClass,
            original: #selector(UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:)),
            replacement: #selector(AppDelegateProxy.aryPush_application(_:didFailToRegisterForRemoteNotificationsWithError:)),
            types: "v@:@@"
        )
        swap(
            in: delegateClass,
            original: #selector(UIApplicationDelegate.application(_:didReceiveRemoteNotification:fetchCompletionHandler:)),
            replacement: #selector(AppDelegateProxy.aryPush_application(_:didReceiveRemoteNotification:fetchCompletionHandler:)),
            types: "v@:@@@?"
        )
    }

    /// Installs one selector, preserving any implementation the host already had.
    ///
    /// When the host implements the selector, the SDK's version is added under a private name
    /// and the two implementations are exchanged: the system now calls the SDK's body, and the
    /// SDK's body calls the host's under the private name. When the host does not implement it,
    /// the SDK's version is simply added and there is nothing to forward to.
    private func swap(
        in delegateClass: AnyClass,
        original: Selector,
        replacement: Selector,
        types: String
    ) {
        guard let replacementMethod = class_getInstanceMethod(AppDelegateProxy.self, replacement)
        else {
            PushLogger.warn("Missing proxy implementation for \(NSStringFromSelector(replacement))")
            return
        }
        let replacementIMP = method_getImplementation(replacementMethod)

        // `class_addMethod` fails when the class already implements the selector itself, which
        // is exactly the signal needed to choose between adding and exchanging.
        let hostImplementsIt = !class_addMethod(delegateClass, original, replacementIMP, types)

        guard hostImplementsIt else {
            PushLogger.debug("Added \(NSStringFromSelector(original)) to the app delegate")
            return
        }

        guard class_addMethod(delegateClass, replacement, replacementIMP, types),
              let originalMethod = class_getInstanceMethod(delegateClass, original),
              let addedMethod = class_getInstanceMethod(delegateClass, replacement)
        else {
            PushLogger.warn(
                "Could not proxy \(NSStringFromSelector(original)); forward it manually from "
                    + "your app delegate instead. See docs/IOS.md."
            )
            return
        }

        method_exchangeImplementations(originalMethod, addedMethod)
        PushLogger.debug("Wrapped the app delegate's \(NSStringFromSelector(original))")
    }
}

// MARK: - Injected implementations
//
// These run with `self` bound to the host's app delegate, not to AppDelegateProxy: their IMPs
// were installed on the delegate's class. Each one tells the SDK what happened and then forwards
// to the host's original implementation, which after the exchange lives under the private
// selector name.

private extension AppDelegateProxy {

    @objc func aryPush_application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        AppDelegateProxy.shared.onAPNsToken?(deviceToken)

        let forwarded = #selector(
            AppDelegateProxy.aryPush_application(_:didRegisterForRemoteNotificationsWithDeviceToken:)
        )
        guard responds(to: forwarded) else { return }
        // After the exchange this selector carries the host's original implementation.
        typealias Signature = @convention(c) (AnyObject, Selector, UIApplication, Data) -> Void
        let implementation = unsafeBitCast(method(for: forwarded), to: Signature.self)
        implementation(self, forwarded, application, deviceToken)
    }

    @objc func aryPush_application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        AppDelegateProxy.shared.onRegistrationFailure?(error)

        let forwarded = #selector(
            AppDelegateProxy.aryPush_application(_:didFailToRegisterForRemoteNotificationsWithError:)
        )
        guard responds(to: forwarded) else { return }
        typealias Signature = @convention(c) (AnyObject, Selector, UIApplication, NSError) -> Void
        let implementation = unsafeBitCast(method(for: forwarded), to: Signature.self)
        implementation(self, forwarded, application, error as NSError)
    }

    @objc func aryPush_application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        AppDelegateProxy.shared.onRemoteNotification?(userInfo)

        let forwarded = #selector(
            AppDelegateProxy.aryPush_application(_:didReceiveRemoteNotification:fetchCompletionHandler:)
        )
        guard responds(to: forwarded) else {
            // No host implementation to defer to. `.noData` is the honest answer: the SDK
            // handled the payload but fetched nothing on the application's behalf.
            completionHandler(.noData)
            return
        }

        typealias Signature = @convention(c) (
            AnyObject, Selector, UIApplication, NSDictionary, @escaping (UIBackgroundFetchResult) -> Void
        ) -> Void
        let implementation = unsafeBitCast(method(for: forwarded), to: Signature.self)
        implementation(self, forwarded, application, userInfo as NSDictionary, completionHandler)
    }
}

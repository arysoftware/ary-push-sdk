import Foundation
import UIKit

/// Owns the push token for its whole life: first issue, refresh, replacement, invalidation.
///
/// On iOS the SDK deals with two genuinely different tokens and refuses to conflate them:
///
/// * the **APNs device token**, a hex string Apple hands to the app delegate;
/// * the **FCM registration token**, which Firebase issues only after it has been given the APNs
///   token, and which is what a Firebase-based backend must send to.
///
/// Both are recorded, and ``provider`` says which one the backend should use. An application
/// that uses Firebase Messaging calls ``ARYPush/setFCMToken(_:)`` from
/// `messaging(_:didReceiveRegistrationToken:)`; an application that talks to APNs directly does
/// nothing at all and gets the APNs token.
final class TokenManager {

    private let storage: StorageManager
    private let onTokenChanged: (String, PushProvider) -> Void
    private let lock = NSLock()
    private var listeners: [UUID: (String) -> Void] = [:]

    init(storage: StorageManager, onTokenChanged: @escaping (String, PushProvider) -> Void) {
        self.storage = storage
        self.onTokenChanged = onTokenChanged
    }

    /// The token the backend should send to, given the current provider.
    var currentToken: String? { storage.string(StorageManager.Keys.pushToken) }

    /// The raw APNs device token, kept even when FCM is the active provider.
    var apnsToken: String? { storage.string(StorageManager.Keys.apnsToken) }

    var provider: PushProvider {
        PushProvider.fromWire(storage.string(StorageManager.Keys.pushProvider))
    }

    /// Asks iOS to register with APNs.
    ///
    /// Must run on the main thread, and does nothing useful before the user has authorized
    /// notifications, so callers check authorization first.
    @MainActor
    func registerForRemoteNotifications() {
        UIApplication.shared.registerForRemoteNotifications()
        PushLogger.debug("Requested APNs registration")
    }

    /// Records the APNs device token handed to the app delegate.
    ///
    /// Idempotent: an unchanged token produces no backend write, which matters because iOS
    /// re-delivers the same token on every launch.
    func handleAPNsToken(_ deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        lock.lock()
        let previous = storage.string(StorageManager.Keys.apnsToken)
        storage.set(StorageManager.Keys.apnsToken, hex)
        lock.unlock()

        if previous != hex {
            PushLogger.info("APNs token \(previous == nil ? "received" : "refreshed"): \(PushLogger.mask(hex))")
        }

        // Firebase, when present, owns the token the backend sends to. Overwriting an FCM token
        // with an APNs token would silently point the backend at the wrong transport.
        guard provider != .fcm else {
            PushLogger.debug("FCM is the active provider; APNs token stored but not synchronised")
            return
        }
        apply(token: hex, provider: .apns)
    }

    /// Records an FCM registration token supplied by the host application.
    func handleFCMToken(_ token: String) {
        guard !token.isEmpty else {
            PushLogger.warn("Ignoring blank FCM token")
            return
        }
        apply(token: token, provider: .fcm)
    }

    /// Reports that APNs registration failed.
    ///
    /// Common and usually not a bug: the Simulator without a paired Mac, a missing entitlement,
    /// or no network at launch. It is logged clearly and never thrown.
    func handleRegistrationFailure(_ error: Error) {
        PushLogger.error(
            "APNs registration failed: \(error.localizedDescription). Check that the Push "
                + "Notifications capability and the aps-environment entitlement are present."
        )
    }

    func addListener(_ listener: @escaping (String) -> Void) -> UUID {
        lock.lock()
        let id = UUID()
        listeners[id] = listener
        let existing = storage.string(StorageManager.Keys.pushToken)
        lock.unlock()

        // A token that arrived before the host attached its listener is still news to it.
        if let existing { deliver(listener, existing) }
        return id
    }

    func removeListener(_ id: UUID) {
        lock.lock()
        listeners[id] = nil
        lock.unlock()
    }

    private func apply(token: String, provider: PushProvider) {
        lock.lock()
        let previousToken = storage.string(StorageManager.Keys.pushToken)
        let previousProvider = storage.string(StorageManager.Keys.pushProvider)
        let unchanged = previousToken == token && previousProvider == provider.wireValue
        if !unchanged {
            storage.set(StorageManager.Keys.pushToken, token)
            storage.set(StorageManager.Keys.pushProvider, provider.wireValue)
        }
        let snapshot = Array(listeners.values)
        lock.unlock()

        guard !unchanged else {
            PushLogger.debug("Push token unchanged (\(PushLogger.mask(token)))")
            return
        }

        PushLogger.info(
            "Push token \(previousToken == nil ? "received" : "refreshed"): "
                + "\(PushLogger.mask(token)) (\(provider.wireValue))"
        )
        onTokenChanged(token, provider)
        snapshot.forEach { deliver($0, token) }
    }

    private func deliver(_ listener: @escaping (String) -> Void, _ token: String) {
        // Host callbacks are foreign code, and they will touch UI.
        DispatchQueue.main.async { listener(token) }
    }
}

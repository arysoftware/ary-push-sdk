import Foundation
import UIKit
import UserNotifications

/// Reads and requests notification authorization.
///
/// Two separate things decide whether a notification is delivered on iOS, and confusing them is
/// the usual source of "the token is fine but nothing appears":
///
/// * the user's authorization decision, which can only be asked for once;
/// * whether the app has actually registered with APNs, which is a separate call and which the
///   OS will not do for an unauthorized app.
///
/// This type owns the first. Registration is triggered by ``TokenManager``.
final class PermissionManager {

    private let storage: StorageManager
    private let center: UNUserNotificationCenter

    init(storage: StorageManager, center: UNUserNotificationCenter = .current()) {
        self.storage = storage
        self.center = center
    }

    /// Current normalised authorization state.
    func status() async -> PushPermissionStatus {
        let settings = await center.notificationSettings()
        return PushPermissionStatus.from(settings.authorizationStatus)
    }

    /// Callback form of ``status()``, for call sites that are not already async.
    func status(_ completion: @escaping (PushPermissionStatus) -> Void) {
        center.getNotificationSettings { settings in
            completion(PushPermissionStatus.from(settings.authorizationStatus))
        }
    }

    /// Whether the system prompt has already been shown.
    ///
    /// iOS answers `notDetermined` only until the first prompt, so this is mostly a convenience;
    /// it is recorded anyway so that the SDK never shows a prompt it knows will not appear.
    var hasBeenAsked: Bool {
        get { storage.bool(StorageManager.Keys.permissionRequested, default: false) }
        set { storage.set(StorageManager.Keys.permissionRequested, newValue) }
    }

    /// Requests authorization, presenting the system prompt when one is still possible.
    ///
    /// `completion` is invoked on the main queue exactly once, whatever the user does.
    func request(
        options: UNAuthorizationOptions,
        completion: @escaping (PushPermissionStatus) -> Void
    ) {
        center.getNotificationSettings { [weak self] settings in
            guard let self else { return }

            guard settings.authorizationStatus == .notDetermined else {
                // Already granted, or already denied: iOS will not present a second prompt, and
                // asking again silently does nothing. Report the truth instead.
                let current = PushPermissionStatus.from(settings.authorizationStatus)
                PushLogger.info("requestPermission(): no prompt is possible, status is \(current)")
                DispatchQueue.main.async { completion(current) }
                return
            }

            self.hasBeenAsked = true
            self.center.requestAuthorization(options: options) { _, error in
                if let error {
                    PushLogger.error("Authorization request failed: \(error)")
                }
                // The status is re-read rather than inferred from the granted flag, so
                // provisional and ephemeral authorization are reported accurately.
                self.status { status in
                    PushLogger.info("Permission result: \(status)")
                    DispatchQueue.main.async { completion(status) }
                }
            }
        }
    }

    /// Records the permission state and reports whether it changed.
    ///
    /// The backend needs to know when a device stops being reachable, and iOS never tells an
    /// application that the user switched notifications off in Settings, so this is sampled at
    /// every launch and after every permission request.
    func recordStatusChange(_ status: PushPermissionStatus) -> Bool {
        let previous = storage.string(StorageManager.Keys.lastPermissionState)
        guard previous != status.rawValue else { return false }
        storage.set(StorageManager.Keys.lastPermissionState, status.rawValue)
        PushLogger.info("Permission status: \(previous ?? "unknown") -> \(status.rawValue)")
        return true
    }

    /// Opens this application's page in Settings, the only route left once a user has denied.
    @MainActor
    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString),
              UIApplication.shared.canOpenURL(url) else {
            PushLogger.warn("Could not open the application settings page")
            return
        }
        UIApplication.shared.open(url)
    }
}

import Foundation

/// The user's in-app choice to receive notifications, set through
/// ``ARYPush/setNotificationsEnabled(_:)``.
///
/// Separate from the system authorization, which only the user can change and only in Settings.
/// Notifications reach the user when both allow it, and that combination is what the backend is
/// told: a user who switches notifications off inside the application stops receiving them even
/// though the system authorization is still granted.
///
/// On until the application turns it off, and persisted, so the choice survives restarts.
final class NotificationPreference {

    private let storage: StorageManager

    init(storage: StorageManager) {
        self.storage = storage
    }

    /// The in-app choice; `true` unless the application has turned notifications off.
    var enabledByUser: Bool {
        storage.bool(StorageManager.Keys.notificationsEnabledByUser, default: true)
    }

    /// Records the in-app choice.
    ///
    /// - Returns: whether it changed. Setting the current value again is a no-op, so a switch that
    ///   re-applies its state on every screen load causes no backend traffic.
    @discardableResult
    func setEnabledByUser(_ enabled: Bool) -> Bool {
        guard enabled != enabledByUser else { return false }
        storage.set(StorageManager.Keys.notificationsEnabledByUser, enabled)
        return true
    }

    /// What the backend is told: the system permits notifications and the user has not opted out.
    func effective(osAuthorized: Bool) -> Bool {
        osAuthorized && enabledByUser
    }
}

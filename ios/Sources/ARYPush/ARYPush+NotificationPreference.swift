import Foundation

public extension ARYPush {
    /// Posted on the main thread after a preference change is accepted by the backend.
    static let notificationPreferenceDidChange = Notification.Name("ARYPush.PreferenceChanged")

    /// The persisted app preference, defaulting to true. This is separate from iOS authorization.
    /// Effective delivery also requires authorization and server-side audience enforcement.
    static func getNotificationPreference() -> Bool {
        PushCore.instance?.notificationPreference ?? true
    }

    /// Updates the app preference without requesting or changing iOS authorization.
    /// Completion runs once on the main actor. Failure preserves the previous preference.
    /// No work is queued for later if configuration, connectivity, or a token is unavailable.
    static func setNotificationPreference(_ enabled: Bool, completion: @escaping (Bool) -> Void) {
        Task { @MainActor in
            guard let core = PushCore.instance else {
                completion(false)
                return
            }
            let accepted = await core.setNotificationPreference(enabled)
            if accepted {
                NotificationCenter.default.post(name: notificationPreferenceDidChange, object: nil)
            }
            completion(accepted)
        }
    }
}

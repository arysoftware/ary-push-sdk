import Foundation

/// Owns the association between this installation and a user.
///
/// The three identifiers the SDK handles are deliberately distinct, and mixing them up is the
/// usual cause of "the wrong person got the notification":
///
/// * **Installation ID** identifies an app on a device. Stable forever.
/// * **Push token** identifies a delivery route. Rotates on its own schedule.
/// * **User ID** identifies a person. Changes at every login and logout, and one person may own
///   several installations.
///
/// Logout clears only the last of these.
final class UserManager {

    private let storage: StorageManager
    private let onUserChanged: (String?) -> Void
    private let lock = NSLock()

    init(storage: StorageManager, onUserChanged: @escaping (String?) -> Void) {
        self.storage = storage
        self.onUserChanged = onUserChanged
    }

    var userId: String? { storage.string(StorageManager.Keys.userId) }

    var isLoggedIn: Bool { userId != nil }

    /// Associates this installation with `userId`.
    ///
    /// Local state is updated first and synchronously, so an offline login is immediately true
    /// from the application's point of view; the backend catches up through the queue.
    func login(_ userId: String) {
        let trimmed = userId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            PushLogger.warn("login() ignored: the user id was blank")
            return
        }

        lock.lock()
        let unchanged = storage.string(StorageManager.Keys.userId) == trimmed
        if !unchanged {
            // Switching directly from one user to another on a shared device: the previous
            // association is replaced, never merged.
            storage.set(StorageManager.Keys.userId, trimmed)
        }
        lock.unlock()

        guard !unchanged else {
            PushLogger.debug("login() ignored: already associated with this user")
            return
        }
        PushLogger.info("User associated with installation")
        onUserChanged(trimmed)
    }

    /// Clears the user association and nothing else.
    ///
    /// The installation id, the push token and the APNs registration all survive. An application
    /// that unregisters the device on logout stops being able to reach the user with anything at
    /// all, including the win-back campaigns that logout exists to enable.
    func logout() {
        lock.lock()
        let wasLoggedIn = storage.string(StorageManager.Keys.userId) != nil
        if wasLoggedIn { storage.set(StorageManager.Keys.userId, nil) }
        lock.unlock()

        guard wasLoggedIn else {
            PushLogger.debug("logout() ignored: no user is associated")
            return
        }
        PushLogger.info("User association cleared; installation and token retained")
        onUserChanged(nil)
    }
}

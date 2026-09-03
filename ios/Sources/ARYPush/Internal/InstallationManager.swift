import Foundation

/// Owns the installation identity.
///
/// The installation id is the SDK's own anchor and is deliberately independent of everything
/// else it might be confused with:
///
/// * not the push token, which rotates whenever APNs or FCM decides;
/// * not the user id, which changes at every login and logout;
/// * not `identifierForVendor`, which the SDK has no business reading and which the OS may reset.
///
/// One user may own many installations (an iPhone and an iPad); one installation belongs to at
/// most one user at a time. Keeping the id stable across token refreshes and logouts is what lets
/// the backend keep tag history and delivery state attached to a device.
final class InstallationManager {

    private let storage: StorageManager
    private let lock = NSLock()

    init(storage: StorageManager) {
        self.storage = storage
    }

    /// Returns the installation id, creating and persisting one on first use.
    ///
    /// Locked because a notification-service extension, a background fetch and the host
    /// application's own launch can reach this within milliseconds of each other. Two ids would
    /// mean two installations on the backend for one device.
    var installationId: String {
        lock.lock()
        defer { lock.unlock() }

        if let existing = storage.string(StorageManager.Keys.installationId) {
            return existing
        }
        let generated = UUID().uuidString
        storage.set(StorageManager.Keys.installationId, generated)
        PushLogger.info("Installation ID created")
        return generated
    }

    /// True when an id already exists, without creating one as a side effect.
    var hasInstallation: Bool {
        storage.string(StorageManager.Keys.installationId) != nil
    }

    /// Discards the installation identity.
    ///
    /// Not part of logout, and not exposed on the public API: this exists for the documented
    /// support path where a device's records must genuinely be started over.
    func reset() {
        lock.lock()
        defer { lock.unlock() }
        storage.remove(
            StorageManager.Keys.installationId,
            StorageManager.Keys.registrationHash
        )
        PushLogger.warn("Installation identity reset")
    }
}

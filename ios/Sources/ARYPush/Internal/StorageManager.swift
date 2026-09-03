import Foundation

/// Namespaced, isolated local storage for SDK state.
///
/// Two isolation guarantees matter here:
///
/// * **From the host application.** Everything lives in the SDK's own `UserDefaults` suite,
///   under `ary_push.` keys, so the SDK can never collide with, read or clobber an
///   application's own defaults.
/// * **Between applications.** A defaults suite is per-application container storage, so two
///   applications embedding this SDK on one device get entirely separate installation ids,
///   tokens, users, tags and queues with no extra work.
///
/// Every accessor is guarded: a corrupted or unavailable defaults store degrades the SDK to
/// "no persisted state", which is recoverable, rather than crashing an application that merely
/// wanted to receive a notification.
final class StorageManager {

    static let suiteName = "com.ary.push.store"

    private let defaults: UserDefaults
    private let lock = NSLock()

    init(defaults: UserDefaults? = nil) {
        // Falling back to `.standard` keeps the SDK working if the suite cannot be created;
        // the namespaced keys still prevent collisions with host application values.
        self.defaults = defaults ?? UserDefaults(suiteName: Self.suiteName) ?? .standard
    }

    // MARK: - Primitives

    func string(_ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return defaults.string(forKey: key)
    }

    func set(_ key: String, _ value: String?) {
        lock.lock(); defer { lock.unlock() }
        if let value {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }

    func bool(_ key: String, default defaultValue: Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return defaults.object(forKey: key) == nil ? defaultValue : defaults.bool(forKey: key)
    }

    func set(_ key: String, _ value: Bool) {
        lock.lock(); defer { lock.unlock() }
        defaults.set(value, forKey: key)
    }

    func stringArray(_ key: String) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return defaults.stringArray(forKey: key) ?? []
    }

    func set(_ key: String, _ value: [String]) {
        lock.lock(); defer { lock.unlock() }
        if value.isEmpty {
            defaults.removeObject(forKey: key)
        } else {
            defaults.set(value, forKey: key)
        }
    }

    func stringMap(_ key: String) -> [String: String] {
        lock.lock(); defer { lock.unlock() }
        return defaults.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    func set(_ key: String, _ value: [String: String]) {
        lock.lock(); defer { lock.unlock() }
        if value.isEmpty {
            defaults.removeObject(forKey: key)
        } else {
            defaults.set(value, forKey: key)
        }
    }

    func remove(_ keys: String...) {
        lock.lock(); defer { lock.unlock() }
        keys.forEach { defaults.removeObject(forKey: $0) }
    }

    /// Clears every SDK key. Used by tests and by the documented device-reset support path.
    func clearAll() {
        lock.lock(); defer { lock.unlock() }
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(Keys.namespace) {
            defaults.removeObject(forKey: key)
        }
    }

    enum Keys {
        static let namespace = "ary_push."

        static let installationId = namespace + "installation_id"
        static let pushToken = namespace + "push_token"
        static let apnsToken = namespace + "apns_token"
        static let pushProvider = namespace + "push_provider"
        static let userId = namespace + "user_id"
        static let tags = namespace + "tags"
        static let topics = namespace + "topics"
        static let pendingOpen = namespace + "pending_open"
        static let pendingOperations = namespace + "pending_operations"
        static let seenMessageIds = namespace + "seen_message_ids"
        static let registrationHash = namespace + "registration_hash"
        static let lastPermissionState = namespace + "last_permission_state"
        static let permissionRequested = namespace + "permission_requested"
    }
}

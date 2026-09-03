import Foundation

/// Topic subscriptions.
///
/// A platform difference worth stating plainly: on Android, FCM performs topic fan-out on the
/// device's behalf, so `subscribeToTopic` is a transport call. On iOS with APNs there is no such
/// mechanism — APNs has no concept of a topic subscription — so the SDK records the subscription
/// and reports it to the push backend, which does the fan-out server side.
///
/// The public API is identical on both platforms; only where the fan-out happens differs, and
/// that is documented in docs/IOS.md rather than leaked into host application code.
///
/// Topics are not segments. A topic is something a device opts into; a segment is something the
/// backend computes from tags.
final class TopicManager {

    private let storage: StorageManager
    private let onTopicsChanged: (Set<String>) -> Void
    private let lock = NSLock()

    init(storage: StorageManager, onTopicsChanged: @escaping (Set<String>) -> Void) {
        self.storage = storage
        self.onTopicsChanged = onTopicsChanged
    }

    var topics: Set<String> {
        Set(storage.stringArray(StorageManager.Keys.topics))
    }

    /// Subscribes this installation to a topic.
    ///
    /// Returns false without contacting anything when the name would be rejected, so an invalid
    /// topic fails locally and visibly instead of being silently dropped later.
    @discardableResult
    func subscribe(_ topic: String) -> Bool {
        guard let normalized = Self.normalize(topic) else {
            PushLogger.warn("Rejected invalid topic name: \(topic)")
            return false
        }

        lock.lock()
        var current = topics
        let inserted = current.insert(normalized).inserted
        if inserted { storage.set(StorageManager.Keys.topics, Array(current).sorted()) }
        lock.unlock()

        guard inserted else { return true }
        PushLogger.info("Subscribed to topic \(normalized)")
        onTopicsChanged(current)
        return true
    }

    @discardableResult
    func unsubscribe(_ topic: String) -> Bool {
        guard let normalized = Self.normalize(topic) else {
            PushLogger.warn("Rejected invalid topic name: \(topic)")
            return false
        }

        lock.lock()
        var current = topics
        let removed = current.remove(normalized) != nil
        if removed { storage.set(StorageManager.Keys.topics, Array(current).sorted()) }
        lock.unlock()

        guard removed else { return true }
        PushLogger.info("Unsubscribed from topic \(normalized)")
        onTopicsChanged(current)
        return true
    }

    /// FCM's topic grammar, applied on iOS too so a topic name means the same thing on both
    /// platforms and an application cannot create a topic only half its devices can join.
    private static let validTopic = "^[a-zA-Z0-9-_.~%]{1,900}$"

    /// Strips the optional `/topics/` prefix and validates, returning `nil` when invalid.
    static func normalize(_ topic: String) -> String? {
        var bare = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        if bare.hasPrefix("/topics/") { bare = String(bare.dropFirst("/topics/".count)) }
        guard bare.range(of: validTopic, options: .regularExpression) != nil else { return nil }
        return bare
    }
}

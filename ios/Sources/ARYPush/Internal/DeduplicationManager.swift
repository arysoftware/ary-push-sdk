import Foundation

/// Stops the same message being processed twice.
///
/// Duplicates are normal, not exceptional. iOS can deliver one message to both a notification
/// service extension and the app; a silent `content-available` message and its visible twin can
/// both arrive; and a tap can be re-delivered after a relaunch. Without this, users see the same
/// alert twice and the backend counts one delivery as two.
///
/// The cache is an LRU bounded by `maxSize` and persisted, so it survives the process restart a
/// background message causes. Bounded is the operative word: an unbounded seen-set on a device
/// that receives thousands of messages is a slow storage leak.
final class DeduplicationManager {

    private let storage: StorageManager
    private let maxSize: Int
    private let lock = NSLock()

    init(storage: StorageManager, maxSize: Int) {
        self.storage = storage
        self.maxSize = max(1, maxSize)
    }

    /// Records `messageId` and reports whether it is new.
    ///
    /// Check and insert are one atomic step on purpose: two copies of a message can arrive on
    /// two queues, and a separate `contains` then `add` would let both through.
    @discardableResult
    func markSeenIfNew(_ messageId: String) -> Bool {
        guard !messageId.isEmpty else { return true }

        lock.lock()
        defer { lock.unlock() }

        var seen = storage.stringArray(StorageManager.Keys.seenMessageIds)
        guard !seen.contains(messageId) else {
            PushLogger.debug("Duplicate message ignored: \(messageId)")
            return false
        }

        seen.append(messageId)
        if seen.count > maxSize {
            // Oldest entries fall off the front once the bound is reached.
            seen.removeFirst(seen.count - maxSize)
        }
        storage.set(StorageManager.Keys.seenMessageIds, seen)
        return true
    }

    /// True when the message has already been handled, without recording it.
    func hasSeen(_ messageId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return storage.stringArray(StorageManager.Keys.seenMessageIds).contains(messageId)
    }

    /// Current cache size. Used by tests to assert the bound actually holds.
    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return storage.stringArray(StorageManager.Keys.seenMessageIds).count
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        storage.remove(StorageManager.Keys.seenMessageIds)
    }
}

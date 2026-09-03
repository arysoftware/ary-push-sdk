import Foundation

/// Tags: the attributes the backend builds segments from.
///
/// Tags are flat key/value attributes owned by the device, e.g. `subscription=premium`,
/// `language=en`. The SDK never evaluates a segment rule: deciding that "Premium Pakistan Users"
/// means `subscription == premium AND country == PK` is backend work, because the rule changes
/// far more often than the app is released.
///
/// Writes land locally first and are then coalesced: a screen that sets five tags in a row
/// produces one PATCH, not five.
final class TagManager {

    private let storage: StorageManager
    private let debounce: TimeInterval
    private let onTagsChanged: ([String: String]) -> Void
    private let onTagsRemoved: (Set<String>, Bool) -> Void

    private let lock = NSLock()
    private var dirty: [String: String] = [:]
    private var flushWorkItem: DispatchWorkItem?
    private let queue = DispatchQueue(label: "com.ary.push.tags")

    init(
        storage: StorageManager,
        debounce: TimeInterval,
        onTagsChanged: @escaping ([String: String]) -> Void,
        onTagsRemoved: @escaping (Set<String>, Bool) -> Void
    ) {
        self.storage = storage
        self.debounce = debounce
        self.onTagsChanged = onTagsChanged
        self.onTagsRemoved = onTagsRemoved
    }

    /// Current tags, read from local storage so an offline read is always correct.
    var tags: [String: String] { storage.stringMap(StorageManager.Keys.tags) }

    func addTag(_ key: String, _ value: String) {
        addTags([key: value])
    }

    func addTags(_ newTags: [String: String]) {
        var sanitized: [String: String] = [:]
        for (key, value) in newTags {
            let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty {
                PushLogger.warn("Ignoring tag with a blank key")
                continue
            }
            sanitized[trimmed] = value
        }
        guard !sanitized.isEmpty else { return }

        lock.lock()
        var current = storage.stringMap(StorageManager.Keys.tags)
        let changed = sanitized.filter { current[$0.key] != $0.value }
        if !changed.isEmpty {
            changed.forEach { current[$0.key] = $0.value }
            storage.set(StorageManager.Keys.tags, current)
            changed.forEach { dirty[$0.key] = $0.value }
        }
        lock.unlock()

        guard !changed.isEmpty else {
            PushLogger.debug("addTags() ignored: no values changed")
            return
        }
        PushLogger.debug("Tags updated locally: \(Array(sanitized.keys))")
        scheduleFlush()
    }

    func removeTag(_ key: String) {
        removeTags([key])
    }

    func removeTags(_ keys: Set<String>) {
        let trimmed = Set(
            keys.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        )
        guard !trimmed.isEmpty else { return }

        lock.lock()
        var current = storage.stringMap(StorageManager.Keys.tags)
        let actuallyRemoved = trimmed.filter { current.removeValue(forKey: $0) != nil }
        if !actuallyRemoved.isEmpty {
            storage.set(StorageManager.Keys.tags, current)
            // A removal supersedes any unsent write of the same key.
            actuallyRemoved.forEach { dirty[$0] = nil }
        }
        lock.unlock()

        guard !actuallyRemoved.isEmpty else { return }
        PushLogger.debug("Tags removed locally: \(actuallyRemoved)")
        onTagsRemoved(actuallyRemoved, false)
    }

    func removeAllTags() {
        lock.lock()
        let hadTags = !storage.stringMap(StorageManager.Keys.tags).isEmpty
        if hadTags {
            storage.set(StorageManager.Keys.tags, [String: String]())
            dirty.removeAll()
        }
        lock.unlock()

        guard hadTags else { return }
        PushLogger.info("All tags removed locally")
        onTagsRemoved([], true)
    }

    /// Sends anything still pending immediately, cancelling the debounce window.
    func flushNow() {
        lock.lock()
        flushWorkItem?.cancel()
        flushWorkItem = nil
        lock.unlock()
        emitDirty()
    }

    private func scheduleFlush() {
        guard debounce > 0 else {
            emitDirty()
            return
        }

        lock.lock()
        // Restarting the window is what collapses a burst: only the final call actually sends.
        flushWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.emitDirty() }
        flushWorkItem = item
        lock.unlock()

        queue.asyncAfter(deadline: .now() + debounce, execute: item)
    }

    private func emitDirty() {
        lock.lock()
        let batch = dirty
        dirty.removeAll()
        lock.unlock()

        guard !batch.isEmpty else { return }
        onTagsChanged(batch)
    }
}

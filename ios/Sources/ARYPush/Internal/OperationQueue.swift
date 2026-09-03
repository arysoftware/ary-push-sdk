import Foundation

/// The durable, bounded, self-coalescing queue behind offline support.
///
/// Three properties matter:
///
/// * **Durable.** Every mutation is written straight through, so a `login()` made in aeroplane
///   mode is still pending after the app is killed and the device rebooted.
/// * **Coalescing.** Enqueueing collapses redundant work at the source rather than sending it
///   and hoping the backend copes: three `addTag` calls become one PATCH, and a newer token
///   replaces an older unsent one instead of queueing behind it.
/// * **Bounded.** A device offline for a week must not accumulate unbounded state. Beyond
///   `maxOperations` the oldest low-value entries (events) are dropped first.
final class PendingOperationQueue {

    /// Deliberately small: this is deferred bookkeeping, not a message store.
    private static let maxOperations = 100

    private let storage: StorageManager
    private let lock = NSRecursiveLock()

    init(storage: StorageManager) {
        self.storage = storage
    }

    /// Adds an operation, collapsing it into an equivalent pending one where possible.
    func enqueue(_ operation: PendingOperation) {
        lock.lock()
        defer { lock.unlock() }

        var current = read()

        if operation.type.isLatestValueWins {
            current.removeAll { $0.type == operation.type }
        } else if operation.type == .updateTags,
                  let index = current.firstIndex(where: { $0.type == .updateTags }) {
            // Merge into the pending tag write so a burst of addTag calls costs one request.
            current[index] = merged(existing: current[index], incoming: operation)
            write(current)
            return
        }

        // A logout supersedes an unsent identify, and vice versa: sending both is contradictory.
        switch operation.type {
        case .logoutUser: current.removeAll { $0.type == .identifyUser }
        case .identifyUser: current.removeAll { $0.type == .logoutUser }
        default: break
        }

        current.append(operation)
        write(bounded(current))
    }

    /// Snapshot of pending operations in dependency order.
    func snapshot() -> [PendingOperation] {
        lock.lock()
        defer { lock.unlock() }
        return read().sorted {
            $0.type.order == $1.type.order
                ? $0.createdAt < $1.createdAt
                : $0.type.order < $1.type.order
        }
    }

    var isEmpty: Bool {
        lock.lock(); defer { lock.unlock() }
        return read().isEmpty
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        return read().count
    }

    /// Removes a completed or permanently failed operation.
    func remove(id: String) {
        lock.lock(); defer { lock.unlock() }
        write(read().filter { $0.id != id })
    }

    /// Records a failed attempt so backoff and the permanent-failure cut-off survive restarts.
    func recordAttempt(id: String) {
        lock.lock(); defer { lock.unlock() }
        write(read().map { $0.id == id ? $0.withAttempts($0.attempts + 1) : $0 })
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        storage.set(StorageManager.Keys.pendingOperations, nil)
    }

    // MARK: - Persistence

    private func read() -> [PendingOperation] {
        guard let raw = storage.string(StorageManager.Keys.pendingOperations),
              let data = raw.data(using: .utf8)
        else { return [] }

        guard let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            // A corrupted queue is dropped rather than retried forever. Losing deferred
            // synchronisation is recoverable; a permanently wedged queue is not.
            PushLogger.warn("Pending operation queue was unreadable and has been reset")
            storage.set(StorageManager.Keys.pendingOperations, nil)
            return []
        }
        return array.compactMap(PendingOperation.from(dictionary:))
    }

    private func write(_ operations: [PendingOperation]) {
        guard !operations.isEmpty else {
            storage.set(StorageManager.Keys.pendingOperations, nil)
            return
        }
        let payload = operations.map { $0.toDictionary() }
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let encoded = String(data: data, encoding: .utf8)
        else {
            PushLogger.warn("Could not persist the pending operation queue")
            return
        }
        storage.set(StorageManager.Keys.pendingOperations, encoded)
    }

    private func merged(
        existing: PendingOperation,
        incoming: PendingOperation
    ) -> PendingOperation {
        var tags = decodeTags(existing.payload[PendingOperation.Key.tags])
        decodeTags(incoming.payload[PendingOperation.Key.tags]).forEach { tags[$0.key] = $0.value }
        // Merging produces a new value, so the attempt counter starts over.
        return existing.withPayload([PendingOperation.Key.tags: encodeTags(tags)], attempts: 0)
    }

    private func bounded(_ operations: [PendingOperation]) -> [PendingOperation] {
        guard operations.count > Self.maxOperations else { return operations }
        PushLogger.warn(
            "Pending operation queue exceeded \(Self.maxOperations) entries; trimming"
        )
        let overflow = operations.count - Self.maxOperations
        let events = operations.filter { $0.type == .trackEvents }.sorted { $0.createdAt < $1.createdAt }
        let dropped = Set(events.prefix(min(overflow, events.count)).map(\.id))
        let kept = operations.filter { !dropped.contains($0.id) }
        guard kept.count > Self.maxOperations else { return kept }
        return Array(kept.sorted { $0.createdAt < $1.createdAt }.suffix(Self.maxOperations))
    }

    func decodeTags(_ raw: String?) -> [String: String] {
        guard let raw, let data = raw.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: String]
        else { return [:] }
        return json
    }

    func encodeTags(_ tags: [String: String]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: tags),
              let encoded = String(data: data, encoding: .utf8)
        else { return "{}" }
        return encoded
    }
}

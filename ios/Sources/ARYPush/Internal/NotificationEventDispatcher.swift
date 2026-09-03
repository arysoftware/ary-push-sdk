import Foundation

/// Delivers notification events to the host application, and refuses to lose one.
///
/// The hard case is the terminated application. The user taps a notification, iOS launches the
/// process, and `didReceive response` fires during `didFinishLaunching` — before any host code
/// has attached a listener. Dropping it there is the single most visible bug a push SDK can
/// have: the user taps an order notification and lands on the home screen.
///
/// So an open with nobody listening is persisted and replayed to the first listener that
/// appears. Received events are not persisted: a message nobody was listening for is stale by
/// the time the application starts, and the notification is already in Notification Centre.
///
/// Callbacks always run on the main queue, because host code will navigate from them.
final class NotificationEventDispatcher {

    private let storage: StorageManager
    private let lock = NSLock()

    private var receivedListeners: [UUID: (PushNotification) -> Void] = [:]
    private var openedListeners: [UUID: (PushNotification) -> Void] = [:]

    init(storage: StorageManager) {
        self.storage = storage
    }

    // MARK: - Registration

    func addReceivedListener(_ listener: @escaping (PushNotification) -> Void) -> UUID {
        lock.lock()
        let id = UUID()
        receivedListeners[id] = listener
        lock.unlock()
        return id
    }

    func removeReceivedListener(_ id: UUID) {
        lock.lock()
        receivedListeners[id] = nil
        lock.unlock()
    }

    func addOpenedListener(_ listener: @escaping (PushNotification) -> Void) -> UUID {
        lock.lock()
        let id = UUID()
        openedListeners[id] = listener
        lock.unlock()

        // Replayed before returning, so a listener attached in didFinishLaunching still sees the
        // tap that started the process.
        if let pending = consumePendingOpen() {
            PushLogger.info("Replaying pending notification open to a newly attached listener")
            deliver(listener, pending)
        }
        return id
    }

    func removeOpenedListener(_ id: UUID) {
        lock.lock()
        openedListeners[id] = nil
        lock.unlock()
    }

    var hasOpenedListeners: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !openedListeners.isEmpty
    }

    // MARK: - Dispatch

    func dispatchReceived(_ notification: PushNotification) {
        PushLogger.info("Notification received: \(notification.id)")
        lock.lock()
        let listeners = Array(receivedListeners.values)
        lock.unlock()
        listeners.forEach { deliver($0, notification) }
    }

    func dispatchOpened(_ notification: PushNotification) {
        PushLogger.info(
            "Notification opened: \(notification.id)"
                + (notification.actionId.map { " (action=\($0))" } ?? "")
        )
        lock.lock()
        let listeners = Array(openedListeners.values)
        lock.unlock()

        guard !listeners.isEmpty else {
            persistPendingOpen(notification)
            return
        }
        listeners.forEach { deliver($0, notification) }
    }

    // MARK: - Pending open

    private func persistPendingOpen(_ notification: PushNotification) {
        PushLogger.debug("No open listeners yet; persisting notification open \(notification.id)")
        storage.set(StorageManager.Keys.pendingOpen, NotificationCodec.encode(notification))
    }

    /// Reads and clears the pending open, if there is one.
    func consumePendingOpen() -> PushNotification? {
        guard let raw = storage.string(StorageManager.Keys.pendingOpen) else { return nil }
        storage.set(StorageManager.Keys.pendingOpen, nil)
        return NotificationCodec.decode(raw)
    }

    /// Reads the pending open without clearing it.
    func peekPendingOpen() -> PushNotification? {
        NotificationCodec.decode(storage.string(StorageManager.Keys.pendingOpen))
    }

    /// Detaches every listener. Used when a Flutter engine is destroyed and by tests.
    func clearListeners() {
        lock.lock()
        receivedListeners.removeAll()
        openedListeners.removeAll()
        lock.unlock()
    }

    private func deliver(
        _ listener: @escaping (PushNotification) -> Void,
        _ notification: PushNotification
    ) {
        if Thread.isMainThread {
            listener(notification)
        } else {
            DispatchQueue.main.async { listener(notification) }
        }
    }
}

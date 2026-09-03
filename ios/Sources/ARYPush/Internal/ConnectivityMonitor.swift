import Foundation
import Network

/// Tells the sync queue when it is worth trying the network again.
///
/// This is a path observer, not a poll and not a background task: the SDK never keeps the app
/// alive to watch connectivity. When the OS reports a usable path the queue drains; the rest of
/// the time this costs nothing.
final class ConnectivityMonitor {

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.ary.push.connectivity")
    private let onAvailable: () -> Void

    private let lock = NSLock()
    private var online = true
    private var started = false

    init(onAvailable: @escaping () -> Void) {
        self.onAvailable = onAvailable
    }

    /// True when a usable network path is currently available.
    ///
    /// Defaults to true before the first path update: assuming online means the request itself
    /// reports the real outcome, whereas assuming offline would stall synchronisation on a
    /// perfectly good connection.
    var isOnline: Bool {
        lock.lock()
        defer { lock.unlock() }
        return online
    }

    func start() {
        lock.lock()
        let alreadyStarted = started
        started = true
        lock.unlock()
        guard !alreadyStarted else { return }

        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let satisfied = path.status == .satisfied

            self.lock.lock()
            let wasOffline = !self.online
            self.online = satisfied
            self.lock.unlock()

            if satisfied && wasOffline {
                PushLogger.debug("Network available; draining pending operations")
                self.onAvailable()
            }
        }
        monitor.start(queue: queue)
    }

    func stop() {
        lock.lock()
        let wasStarted = started
        started = false
        lock.unlock()
        guard wasStarted else { return }
        monitor.cancel()
    }
}

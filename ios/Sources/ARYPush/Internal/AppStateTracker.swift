import Foundation
import UIKit

/// Tracks whether the application is in the foreground.
///
/// The foreground display policy needs this, and it cannot be read on demand: SDK code runs on
/// background queues, and `UIApplication.applicationState` is main-thread only. So the value is
/// cached from lifecycle notifications, which are always delivered on the main thread.
final class AppStateTracker {

    private let lock = NSLock()
    private var foreground: Bool

    init(notificationCenter: NotificationCenter = .default) {
        // Seeded pessimistically: a process started by a silent notification is not foreground,
        // and treating it as such would suppress a notification the user should have seen.
        self.foreground = false

        notificationCenter.addObserver(
            self,
            selector: #selector(didBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        notificationCenter.addObserver(
            self,
            selector: #selector(willResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )

        if Thread.isMainThread {
            foreground = UIApplication.shared.applicationState == .active
        } else {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.set(UIApplication.shared.applicationState == .active)
            }
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    var isForeground: Bool {
        lock.lock()
        defer { lock.unlock() }
        return foreground
    }

    @objc private func didBecomeActive() { set(true) }

    @objc private func willResignActive() { set(false) }

    private func set(_ value: Bool) {
        lock.lock()
        foreground = value
        lock.unlock()
    }
}

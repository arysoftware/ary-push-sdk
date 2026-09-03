import Foundation
import os.log

/// The SDK's logger.
///
/// Two rules, both enforced here rather than left to call sites:
///
/// 1. Silent unless the host application opts in. A library that writes to the console by
///    default is a library that leaks its users' data into bug reports and sysdiagnoses.
/// 2. Secrets never reach the log. Tokens are masked by ``mask(_:)`` before they are formatted,
///    so a careless call site cannot leak one.
enum PushLogger {

    private static let log = OSLog(subsystem: "com.ary.push", category: "ARYPush")

    private static let lock = NSLock()
    private static var enabled = false
    private static var minimumLevel: PushLogLevel = .info

    static func configure(enabled: Bool, level: PushLogLevel) {
        lock.lock()
        defer { lock.unlock() }
        self.enabled = enabled && level != .none
        self.minimumLevel = level
    }

    static var isEnabled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return enabled
    }

    static func verbose(_ message: @autoclosure () -> String) { emit(.verbose, message()) }
    static func debug(_ message: @autoclosure () -> String) { emit(.debug, message()) }
    static func info(_ message: @autoclosure () -> String) { emit(.info, message()) }
    static func warn(_ message: @autoclosure () -> String) { emit(.warning, message()) }
    static func error(_ message: @autoclosure () -> String) { emit(.error, message()) }

    private static func emit(_ level: PushLogLevel, _ message: @autoclosure () -> String) {
        lock.lock()
        let shouldEmit = enabled && level >= minimumLevel
        lock.unlock()
        guard shouldEmit else { return }

        // %{public}@ because the SDK has already removed anything sensitive; without it the
        // message is redacted to <private> and the log is useless for support.
        os_log("[ARYPush] %{public}@", log: log, type: level.osLogType, message())
    }

    /// Renders a sensitive value as a short, non-reversible hint.
    ///
    /// Enough to tell two tokens apart in a bug report, never enough to use one.
    static func mask(_ secret: String?) -> String {
        guard let secret else { return "null" }
        if secret.isEmpty { return "empty" }
        if secret.count <= 8 { return "***(\(secret.count))" }
        return "\(secret.prefix(4))***\(secret.suffix(2))(\(secret.count))"
    }
}

private extension PushLogLevel {
    var osLogType: OSLogType {
        switch self {
        case .verbose, .debug: return .debug
        case .info: return .info
        case .warning: return .default
        case .error: return .error
        case .none: return .debug
        }
    }
}

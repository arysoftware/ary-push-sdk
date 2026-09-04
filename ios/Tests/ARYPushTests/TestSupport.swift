import Foundation
@testable import ARYPush

/// A storage manager backed by a throwaway defaults suite.
///
/// Each test gets its own suite so state cannot leak between cases, and nothing touches the
/// standard defaults of whatever process is running the tests.
func makeTestStorage(function: String = #function) -> StorageManager {
    let suite = "com.ary.push.tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return StorageManager(defaults: defaults)
}

/// Records backend calls and returns scripted results.
///
/// Because the core depends on ``PushBackend`` rather than on HTTP, the whole synchronisation
/// state machine (ordering, retries, permanent failures, offline behaviour) is testable without
/// a server.
final class FakePushBackend: PushBackend, @unchecked Sendable {

    private let lock = NSLock()
    private(set) var calls: [String] = []
    private var scripted: [ApiResult<Void>] = []

    var defaultResult: ApiResult<Void> = .success((), statusCode: 200)

    func script(_ results: ApiResult<Void>...) {
        lock.lock()
        scripted.append(contentsOf: results)
        lock.unlock()
    }

    func count(of name: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return calls.filter { $0 == name }.count
    }

    private func next(_ name: String) -> ApiResult<Void> {
        lock.lock()
        defer { lock.unlock() }
        calls.append(name)
        return scripted.isEmpty ? defaultResult : scripted.removeFirst()
    }

    func registerInstallation(_ installation: Installation) async -> ApiResult<Void> {
        next("register")
    }

    func updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ) async -> ApiResult<Void> {
        next("token:\(token)")
    }

    func identify(installationId: String, userId: String) async -> ApiResult<Void> {
        next("identify:\(userId)")
    }

    func logout(installationId: String) async -> ApiResult<Void> {
        next("logout")
    }

    func updateTags(installationId: String, tags: [String: String]) async -> ApiResult<Void> {
        next("tags:" + tags.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ","))
    }

    func removeTags(installationId: String, keys: Set<String>, all: Bool) async -> ApiResult<Void> {
        next(all ? "removeAllTags" : "removeTags:\(keys.sorted().joined(separator: ","))")
    }

    func updateTopics(installationId: String, topics: Set<String>) async -> ApiResult<Void> {
        next("topics:\(topics.sorted().joined(separator: ","))")
    }

    func updateNotificationPermission(
        installationId: String,
        enabled: Bool
    ) async -> ApiResult<Void> {
        next("permission:\(enabled)")
    }

    /// Segments handed back by `getSegments`.
    var scriptedSegments: [Segment] = []

    func getSegments(installationId: String) async -> ApiResult<[Segment]> {
        lock.lock()
        calls.append("segments")
        let segments = scriptedSegments
        lock.unlock()
        return .success(segments, statusCode: 200)
    }

    func trackEvents(installationId: String, events: [PushEvent]) async -> ApiResult<Void> {
        next("events:\(events.count)")
    }
}

/// A representative installation for tests that need one.
func makeTestInstallation(
    id: String = "install-1",
    token: String? = "token-1",
    userId: String? = nil
) -> Installation {
    Installation(
        id: id,
        applicationId: "wallet_ios",
        platform: "ios",
        provider: .apns,
        pushToken: token,
        userId: userId,
        appVersion: "5.2.0",
        appBuild: "520",
        sdkVersion: "1.0.0",
        osVersion: "17.4",
        deviceModel: "iPhone16,1",
        locale: "en-PK",
        timezone: "Asia/Karachi",
        notificationsEnabled: true
    )
}

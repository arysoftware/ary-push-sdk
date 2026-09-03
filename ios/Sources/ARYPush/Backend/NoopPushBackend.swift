import Foundation

/// The backend used when an application configures no server.
///
/// Every operation succeeds immediately and does nothing. This is what makes the push half of the
/// SDK genuinely independent of the sync half: an application that only wants to receive and open
/// notifications never sees a network call, and the sync queue stays permanently empty.
///
/// It is also the substitute installed when a REST backend was requested but could not be built,
/// so that a configuration mistake degrades to "no synchronisation" rather than to "no push".
public final class NoopPushBackend: PushBackend {

    public static let shared = NoopPushBackend()

    public init() {}

    private var ok: ApiResult<Void> { .success((), statusCode: 200) }

    public func registerInstallation(_ installation: Installation) async -> ApiResult<Void> { ok }

    public func updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ) async -> ApiResult<Void> { ok }

    public func identify(installationId: String, userId: String) async -> ApiResult<Void> { ok }

    public func logout(installationId: String) async -> ApiResult<Void> { ok }

    public func updateTags(
        installationId: String,
        tags: [String: String]
    ) async -> ApiResult<Void> { ok }

    public func removeTags(
        installationId: String,
        keys: Set<String>,
        all: Bool
    ) async -> ApiResult<Void> { ok }

    public func updateTopics(
        installationId: String,
        topics: Set<String>
    ) async -> ApiResult<Void> { ok }

    public func updateNotificationPermission(
        installationId: String,
        enabled: Bool
    ) async -> ApiResult<Void> { ok }

    public func trackEvents(
        installationId: String,
        events: [PushEvent]
    ) async -> ApiResult<Void> { ok }
}

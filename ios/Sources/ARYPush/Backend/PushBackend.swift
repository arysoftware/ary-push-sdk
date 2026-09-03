import Foundation

/// Everything the SDK needs from a server, expressed without any reference to HTTP.
///
/// The notification engine depends on this protocol, never on a transport. That keeps the core
/// testable, lets an application run with no server at all (``NoopPushBackend``), and lets a team
/// swap in a bespoke transport without touching push handling.
///
/// Implementations must be safe to call concurrently and must never throw: every failure is
/// reported as an ``ApiResult``. Every operation must be idempotent, because the sync queue will
/// retry it.
public protocol PushBackend: AnyObject {

    /// Creates or refreshes the installation record. Idempotent on the installation id.
    func registerInstallation(_ installation: Installation) async -> ApiResult<Void>

    /// Replaces the push token held for an installation.
    func updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ) async -> ApiResult<Void>

    /// Associates an installation with a user. A user may own many installations.
    func identify(installationId: String, userId: String) async -> ApiResult<Void>

    /// Clears the user association.
    ///
    /// The installation, its token and its device registration survive: logging out of an
    /// application must not unregister the device from push.
    func logout(installationId: String) async -> ApiResult<Void>

    /// Merges tag values into the installation record.
    func updateTags(installationId: String, tags: [String: String]) async -> ApiResult<Void>

    /// Deletes the named tags, or every tag when `all` is true.
    func removeTags(installationId: String, keys: Set<String>, all: Bool) async -> ApiResult<Void>

    /// Reports the installation's current topic subscriptions.
    func updateTopics(installationId: String, topics: Set<String>) async -> ApiResult<Void>

    /// Reports whether the OS currently permits notifications for this installation.
    func updateNotificationPermission(
        installationId: String,
        enabled: Bool
    ) async -> ApiResult<Void>

    /// Submits a batch of push-related events.
    func trackEvents(installationId: String, events: [PushEvent]) async -> ApiResult<Void>

    /// Releases any transport resources held by this backend.
    func close()
}

public extension PushBackend {
    func close() {}
}

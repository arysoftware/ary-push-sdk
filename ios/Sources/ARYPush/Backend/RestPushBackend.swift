import Foundation

/// Maps push operations onto the ARY push API.
///
/// This type is the only place in the SDK that knows the wire contract. It contains no HTTP
/// mechanics (that is ``RestClient``) and no scheduling or retry policy (that is the sync queue),
/// which keeps the contract easy to read against docs/REST_API.md and easy to version.
///
/// Every request is idempotent by construction: registration is keyed on the installation id,
/// and token, identity, tag and topic writes are full replacements or merges rather than deltas.
///
/// The paths below are identical to the Android implementation on purpose: one backend contract,
/// two clients.
final class RestPushBackend: PushBackend {

    private enum Path {
        static let installations = "installations"
        static let events = "events"
    }

    private let client: RestClient
    private let config: PushBackendConfig

    init(client: RestClient, config: PushBackendConfig) {
        self.client = client
        self.config = config
    }

    func registerInstallation(_ installation: Installation) async -> ApiResult<Void> {
        var body: [String: Any?] = [
            "applicationId": installation.applicationId ?? config.applicationId,
            "installationId": installation.id,
            "platform": installation.platform,
            "provider": installation.provider.wireValue,
            "pushToken": installation.pushToken,
            "userId": installation.userId,
            "appVersion": installation.appVersion,
            "appBuild": installation.appBuild,
            "sdkVersion": installation.sdkVersion,
            "notificationsEnabled": installation.notificationsEnabled
        ]

        // Optional device block, omitted entirely when device collection is disabled.
        var device: [String: Any] = [:]
        if let value = installation.osVersion { device["osVersion"] = value }
        if let value = installation.deviceModel { device["deviceModel"] = value }
        if let value = installation.locale { device["locale"] = value }
        if let value = installation.timezone { device["timezone"] = value }
        if !device.isEmpty { body["device"] = device }

        return await client.post(
            path: Path.installations,
            body: body.compactMapValues { $0 },
            parse: ignoreBody
        )
    }

    func updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ) async -> ApiResult<Void> {
        await client.put(
            path: "\(Path.installations)/\(installationId)/token",
            body: ["token": token, "provider": provider.wireValue],
            parse: ignoreBody
        )
    }

    func identify(installationId: String, userId: String) async -> ApiResult<Void> {
        await client.post(
            path: "\(Path.installations)/\(installationId)/identify",
            body: ["userId": userId],
            parse: ignoreBody
        )
    }

    func logout(installationId: String) async -> ApiResult<Void> {
        // Deletes the user association only. The installation, its token and its device
        // registration survive, so the device keeps receiving unauthenticated campaigns.
        await client.delete(path: "\(Path.installations)/\(installationId)/user")
    }

    func updateTags(installationId: String, tags: [String: String]) async -> ApiResult<Void> {
        await client.patch(
            path: "\(Path.installations)/\(installationId)/tags",
            body: ["tags": tags],
            parse: ignoreBody
        )
    }

    func removeTags(
        installationId: String,
        keys: Set<String>,
        all: Bool
    ) async -> ApiResult<Void> {
        let query: [String: Any?]
        if all {
            query = ["all": true]
        } else {
            guard !keys.isEmpty else { return .success((), statusCode: 200) }
            query = ["keys": keys.sorted().joined(separator: ",")]
        }
        return await client.delete(
            path: "\(Path.installations)/\(installationId)/tags",
            query: query
        )
    }

    func updateTopics(installationId: String, topics: Set<String>) async -> ApiResult<Void> {
        await client.put(
            path: "\(Path.installations)/\(installationId)/topics",
            body: ["topics": topics.sorted()],
            parse: ignoreBody
        )
    }

    func updateNotificationPermission(
        installationId: String,
        enabled: Bool
    ) async -> ApiResult<Void> {
        await client.patch(
            path: "\(Path.installations)/\(installationId)",
            body: ["notificationsEnabled": enabled],
            parse: ignoreBody
        )
    }

    func trackEvents(installationId: String, events: [PushEvent]) async -> ApiResult<Void> {
        guard !events.isEmpty else { return .success((), statusCode: 200) }
        let body: [String: Any] = [
            "installationId": installationId,
            "events": events.map { event in
                [
                    "name": event.name,
                    "occurredAt": Int(event.occurredAt.timeIntervalSince1970 * 1000),
                    "properties": event.properties
                ] as [String: Any]
            }
        ]
        return await client.post(path: Path.events, body: body, parse: ignoreBody)
    }

    func close() {
        client.close()
    }
}

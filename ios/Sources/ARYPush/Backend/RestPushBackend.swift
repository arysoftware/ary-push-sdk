import Foundation

/// Maps push operations onto the ARY push API.
///
/// This type is the only place in the SDK that knows the wire contract. It contains no HTTP
/// mechanics (that is ``RestClient``) and no scheduling or retry policy (that is the sync queue).
///
/// The API has exactly five endpoints, identical to the Android implementation on purpose:
///
/// | Operation               | Request                                        |
/// |-------------------------|------------------------------------------------|
/// | Register device         | `POST /api/notifications/devices/register`     |
/// | Device token update     | `PUT  /api/notifications/devices/update`       |
/// | Subscribe / unsubscribe | `PUT  /api/notifications/devices/toggle`       |
/// | Add segment subscriber  | `POST /api/segments/{segmentId}/subscribers`   |
/// | Segment list            | `GET  /api/segments/list`                      |
///
/// `projectId` and the bearer token are added to every request by the transport.
///
/// Operations with no endpoint -- user identity, tags, topics and events -- are answered locally
/// and never reach the network. They stay fully functional on the device; the server simply has
/// no API to receive them, and sending them anyway would produce a stream of 404s.
final class RestPushBackend: PushBackend {

    private enum Path {
        static let register = "/api/notifications/devices/register"
        static let update = "/api/notifications/devices/update"
        static let toggle = "/api/notifications/devices/toggle"
        static let segments = "/api/segments"
        static let segmentList = "/api/segments/list"
    }

    /// Status reported for an operation settled without a request.
    private static let noRequest = 204

    private let client: RestClient
    private let config: PushBackendConfig

    /// The current installation record. The update and toggle endpoints need fields their
    /// operations do not carry, so they are read from the live record.
    ///
    /// Optional because the owner captures itself weakly, as the sync manager does: a request
    /// still in flight when the core is torn down must degrade, not crash.
    private let installationProvider: () -> Installation?

    init(
        client: RestClient,
        config: PushBackendConfig,
        installationProvider: @escaping () -> Installation?
    ) {
        self.client = client
        self.config = config
        self.installationProvider = installationProvider
    }

    private var answeredLocally: ApiResult<Void> { .success((), statusCode: Self.noRequest) }

    // MARK: - The five endpoints

    func registerInstallation(_ installation: Installation) async -> ApiResult<Void> {
        await client.post(
            path: Path.register,
            body: installationPayload(installation),
            parse: ignoreBody
        )
    }

    func updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ) async -> ApiResult<Void> {
        let current = installationProvider()
        var body: [String: Any] = [
            "installationId": installationId,
            "newToken": token,
            "platform": current?.platform ?? "ios"
        ]
        if let current { body["notificationsEnabled"] = current.notificationsEnabled }
        if let appVersion = current?.appVersion { body["appVersion"] = appVersion }
        return await client.put(path: Path.update, body: body, parse: ignoreBody)
    }

    func updateNotificationPermission(
        installationId: String,
        enabled: Bool
    ) async -> ApiResult<Void> {
        // The toggle endpoint identifies the device by its push token. Before a token exists
        // there is nothing to address, and nothing is lost by waiting: the registration sent
        // once the token arrives carries notificationsEnabled itself.
        guard let token = installationProvider()?.pushToken, !token.isEmpty else {
            return answeredLocally
        }
        // Annotated: a mixed-type literal passed straight to `Any?` does not compile.
        let body: [String: Any] = ["token": token, "notificationsEnabled": enabled]
        return await client.put(path: Path.toggle, body: body, parse: ignoreBody)
    }

    func subscribeToSegment(
        segmentId: String,
        installation: Installation
    ) async -> ApiResult<Void> {
        await client.post(
            path: "\(Path.segments)/\(Self.encodePathSegment(segmentId))/subscribers",
            body: installationPayload(installation),
            parse: ignoreBody
        )
    }

    func getSegments(installationId: String) async -> ApiResult<[Segment]> {
        await client.get(path: Path.segmentList) { data in
            Self.parseSegments(data)
        }
    }

    // MARK: - No endpoint

    func identify(installationId: String, userId: String) async -> ApiResult<Void> {
        answeredLocally
    }

    func logout(installationId: String) async -> ApiResult<Void> {
        answeredLocally
    }

    func updateTags(installationId: String, tags: [String: String]) async -> ApiResult<Void> {
        answeredLocally
    }

    func removeTags(
        installationId: String,
        keys: Set<String>,
        all: Bool
    ) async -> ApiResult<Void> {
        answeredLocally
    }

    func updateTopics(installationId: String, topics: Set<String>) async -> ApiResult<Void> {
        answeredLocally
    }

    func trackEvents(installationId: String, events: [PushEvent]) async -> ApiResult<Void> {
        answeredLocally
    }

    func close() {
        client.close()
    }

    // MARK: - Payloads

    /// The installation payload shared by registration and segment subscription.
    ///
    /// The `device` block is omitted entirely, rather than sent empty, when the host disabled
    /// device-information collection. Fields with no value are omitted rather than sent as null.
    private func installationPayload(_ installation: Installation) -> [String: Any] {
        var body: [String: Any?] = [
            "token": installation.pushToken,
            "platform": installation.platform,
            "applicationId": installation.applicationId ?? config.applicationId,
            "installationId": installation.id,
            "provider": installation.provider.wireValue,
            "appVersion": installation.appVersion,
            "appBuild": installation.appBuild,
            "sdkVersion": installation.sdkVersion,
            "notificationsEnabled": installation.notificationsEnabled
        ]

        var device: [String: Any] = [:]
        if let value = installation.osVersion { device["osVersion"] = value }
        if let value = installation.deviceModel { device["deviceModel"] = value }
        if let value = installation.locale { device["locale"] = value }
        if let value = installation.timezone { device["timezone"] = value }
        if !device.isEmpty { body["device"] = device }

        return body.compactMapValues { $0 }
    }

    /// Parses the segment list.
    ///
    /// Accepts a bare array, or an object wrapping one under `segments` or `data`, because
    /// gateways that wrap collection responses are common and no shape is worth failing over. An
    /// entry without an id is skipped rather than failing the whole response.
    private static func parseSegments(_ data: Data) -> [Segment] {
        let json = try? JSONSerialization.jsonObject(with: data)
        let entries: [[String: Any]]
        if let array = json as? [[String: Any]] {
            entries = array
        } else if let wrapper = json as? [String: Any],
                  let array = (wrapper["segments"] ?? wrapper["data"]) as? [[String: Any]] {
            entries = array
        } else {
            return []
        }
        return entries.compactMap(Segment.from(json:))
    }

    /// Percent-encodes a path segment, including `/`, which `urlPathAllowed` would let through.
    private static func encodePathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

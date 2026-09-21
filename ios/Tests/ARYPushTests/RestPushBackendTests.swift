import XCTest
@testable import ARYPush

/// Records requests instead of sending them.
///
/// Having the backend depend on ``RestClient`` rather than on HTTP is what makes this possible:
/// the whole wire contract is asserted with no socket, no server and no flakiness.
private final class FakeRestClient: RestClient, @unchecked Sendable {

    struct Call {
        let method: String
        let path: String
        let body: [String: Any]?
        let query: [String: Any?]
    }

    private(set) var calls: [Call] = []
    var nextResult: Any = ()
    private(set) var closed = false

    private func record(_ method: String, _ path: String, _ body: Any?, _ query: [String: Any?]) {
        calls.append(
            Call(method: method, path: path, body: body as? [String: Any], query: query)
        )
    }

    func get<T>(
        path: String, query: [String: Any?], headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        record("GET", path, nil, query)
        return .success(nextResult as! T, statusCode: 200)
    }

    func post<T>(
        path: String, body: Any?, headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        record("POST", path, body, [:])
        return .success(nextResult as! T, statusCode: 200)
    }

    func put<T>(
        path: String, body: Any?, headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        record("PUT", path, body, [:])
        return .success(nextResult as! T, statusCode: 200)
    }

    func patch<T>(
        path: String, body: Any?, headers: [String: String],
        parse: @escaping (Data) throws -> T
    ) async -> ApiResult<T> {
        record("PATCH", path, body, [:])
        return .success(nextResult as! T, statusCode: 200)
    }

    func delete(
        path: String, query: [String: Any?], headers: [String: String]
    ) async -> ApiResult<Void> {
        record("DELETE", path, nil, query)
        return .success((), statusCode: 200)
    }

    func close() { closed = true }

    var only: Call { calls[0] }
}

final class RestPushBackendTests: XCTestCase {

    private var client: FakeRestClient!
    private var backend: RestPushBackend!

    /// What the backend reads as the live record; replaced by tests that need a variant.
    private var current: Installation?

    private let config = PushBackendConfig(
        baseURL: "https://push-api.ary.com",
        applicationId: "wallet_ios",
        projectId: "proj-42"
    )

    override func setUp() {
        super.setUp()
        client = FakeRestClient()
        current = makeTestInstallation()
        backend = RestPushBackend(
            client: client,
            config: config,
            installationProvider: { [unowned self] in self.current }
        )
    }

    // MARK: 1. Register

    func testRegistrationPostsTheFullInstallationPayload() async {
        _ = await backend.registerInstallation(makeTestInstallation(userId: "USER_123"))

        let call = client.only
        XCTAssertEqual(call.method, "POST")
        // Identical to the Android client: one backend contract, two clients.
        XCTAssertEqual(call.path, "/api/notifications/devices/register")
        XCTAssertEqual(call.body?["token"] as? String, "token-1")
        XCTAssertEqual(call.body?["platform"] as? String, "ios")
        XCTAssertEqual(call.body?["applicationId"] as? String, "wallet_ios")
        XCTAssertEqual(call.body?["installationId"] as? String, "install-1")
        XCTAssertEqual(call.body?["provider"] as? String, "apns")
        XCTAssertEqual(call.body?["appVersion"] as? String, "5.2.0")
        XCTAssertEqual(call.body?["appBuild"] as? String, "520")
        XCTAssertEqual(call.body?["sdkVersion"] as? String, "1.0.0")
        XCTAssertEqual(call.body?["notificationsEnabled"] as? Bool, true)

        let device = call.body?["device"] as? [String: Any]
        XCTAssertEqual(device?["osVersion"] as? String, "17.4")
        XCTAssertEqual(device?["timezone"] as? String, "Asia/Karachi")
    }

    func testRegistrationSendsExactlyTheDocumentedFields() async {
        _ = await backend.registerInstallation(makeTestInstallation(userId: "USER_123"))

        XCTAssertEqual(
            Set(client.only.body?.keys.map { $0 } ?? []),
            [
                "token", "platform", "applicationId", "installationId", "provider",
                "appVersion", "appBuild", "sdkVersion", "notificationsEnabled", "device"
            ]
        )
    }

    // MARK: 2. Token update

    func testATokenUpdatePutsTheNewTokenWithTheLiveDeviceState() async {
        _ = await backend.updateToken(
            installationId: "install-1",
            token: "token-2",
            provider: .fcm
        )

        let call = client.only
        XCTAssertEqual(call.method, "PUT")
        XCTAssertEqual(call.path, "/api/notifications/devices/update")
        XCTAssertEqual(call.body?["installationId"] as? String, "install-1")
        XCTAssertEqual(call.body?["newToken"] as? String, "token-2")
        XCTAssertEqual(call.body?["platform"] as? String, "ios")
        XCTAssertEqual(call.body?["notificationsEnabled"] as? Bool, true)
        XCTAssertEqual(call.body?["appVersion"] as? String, "5.2.0")
    }

    // MARK: 3. Toggle

    func testAPermissionChangePutsTheToggleKeyedByPushToken() async {
        _ = await backend.updateNotificationPermission(installationId: "install-1", enabled: false)

        let call = client.only
        XCTAssertEqual(call.method, "PUT")
        XCTAssertEqual(call.path, "/api/notifications/devices/toggle")
        XCTAssertEqual(call.body?["token"] as? String, "token-1")
        XCTAssertEqual(call.body?["notificationsEnabled"] as? Bool, false)
    }

    func testAPermissionChangeBeforeAnyTokenExistsMakesNoRequest() async {
        current = makeTestInstallation(token: nil)

        let result = await backend.updateNotificationPermission(
            installationId: "install-1",
            enabled: true
        )

        XCTAssertTrue(result.isSuccess)
        XCTAssertTrue(client.calls.isEmpty)
    }

    // MARK: 4. Segment subscriber

    func testSubscribingToASegmentPostsTheFullInstallationPayload() async {
        _ = await backend.subscribeToSegment(
            segmentId: "seg_premium",
            installation: makeTestInstallation()
        )

        let call = client.only
        XCTAssertEqual(call.method, "POST")
        XCTAssertEqual(call.path, "/api/segments/seg_premium/subscribers")
        XCTAssertEqual(call.body?["installationId"] as? String, "install-1")
        XCTAssertEqual(call.body?["token"] as? String, "token-1")
    }

    func testASegmentIdIsPercentEncodedAsAPathSegment() async {
        _ = await backend.subscribeToSegment(
            segmentId: "premium users/pk",
            installation: makeTestInstallation()
        )

        XCTAssertEqual(client.only.path, "/api/segments/premium%20users%2Fpk/subscribers")
    }

    // MARK: 5. Segment list

    func testTheSegmentListIsReadFromTheProjectCollection() async {
        client.nextResult = [Segment]()

        _ = await backend.getSegments(installationId: "install-1")

        XCTAssertEqual(client.only.method, "GET")
        XCTAssertEqual(client.only.path, "/api/segments/list")
    }

    // MARK: No endpoint

    func testOperationsWithNoEndpointSucceedLocallyAndSendNothing() async {
        var results: [ApiResult<Void>] = []
        results.append(await backend.identify(installationId: "install-1", userId: "USER_9"))
        results.append(await backend.logout(installationId: "install-1"))
        results.append(await backend.updateTags(installationId: "install-1", tags: ["a": "b"]))
        results.append(
            await backend.removeTags(installationId: "install-1", keys: ["a"], all: false)
        )
        results.append(await backend.updateTopics(installationId: "install-1", topics: ["news"]))
        results.append(
            await backend.trackEvents(
                installationId: "install-1",
                events: [PushEvent(name: "notification_opened")]
            )
        )

        XCTAssertTrue(results.allSatisfy { $0.isSuccess })
        XCTAssertTrue(client.calls.isEmpty)
    }

    // MARK: Configuration

    func testTheBackendConfigurationNeverPrintsItsBearerToken() {
        let config = PushBackendConfig(baseURL: "https://push.example", authToken: "secret-value")

        XCTAssertFalse(config.description.contains("secret-value"))
        XCTAssertTrue(config.description.contains("authToken: ***"))

        var dumped = ""
        dump(config, to: &dumped)
        XCTAssertFalse(dumped.contains("secret-value"))
    }

    func testClosingTheBackendClosesItsTransport() {
        backend.close()

        XCTAssertTrue(client.closed)
    }
}

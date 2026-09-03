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

    private let config = PushBackendConfig(
        baseURL: "https://push-api.ary.com",
        applicationId: "wallet_ios"
    )

    override func setUp() {
        super.setUp()
        client = FakeRestClient()
        backend = RestPushBackend(client: client, config: config)
    }

    func testRegistrationPostsTheDocumentedBody() async {
        _ = await backend.registerInstallation(makeTestInstallation(userId: "USER_123"))

        let call = client.only
        XCTAssertEqual(call.method, "POST")
        // Identical to the Android client: one backend contract, two clients.
        XCTAssertEqual(call.path, "installations")
        XCTAssertEqual(call.body?["installationId"] as? String, "install-1")
        XCTAssertEqual(call.body?["applicationId"] as? String, "wallet_ios")
        XCTAssertEqual(call.body?["platform"] as? String, "ios")
        XCTAssertEqual(call.body?["provider"] as? String, "apns")
        XCTAssertEqual(call.body?["pushToken"] as? String, "token-1")
        XCTAssertEqual(call.body?["userId"] as? String, "USER_123")

        let device = call.body?["device"] as? [String: Any]
        XCTAssertEqual(device?["timezone"] as? String, "Asia/Karachi")
    }

    func testTokenUpdatesPutToTheTokenResource() async {
        _ = await backend.updateToken(
            installationId: "install-1",
            token: "token-2",
            provider: .fcm
        )

        XCTAssertEqual(client.only.method, "PUT")
        XCTAssertEqual(client.only.path, "installations/install-1/token")
        XCTAssertEqual(client.only.body?["token"] as? String, "token-2")
        XCTAssertEqual(client.only.body?["provider"] as? String, "fcm")
    }

    func testLogoutDeletesOnlyTheUserAssociation() async {
        _ = await backend.logout(installationId: "install-1")

        XCTAssertEqual(client.only.method, "DELETE")
        // Not /installations/install-1: the device registration and token must survive.
        XCTAssertEqual(client.only.path, "installations/install-1/user")
    }

    func testTagsAreMergedWithPatch() async {
        _ = await backend.updateTags(
            installationId: "install-1",
            tags: ["subscription": "premium"]
        )

        XCTAssertEqual(client.only.method, "PATCH")
        XCTAssertEqual(client.only.path, "installations/install-1/tags")
        XCTAssertEqual(
            (client.only.body?["tags"] as? [String: String])?["subscription"],
            "premium"
        )
    }

    func testRemovingNamedTagsSendsThemAsAQueryParameter() async {
        _ = await backend.removeTags(installationId: "install-1", keys: ["b", "a"], all: false)

        XCTAssertEqual(client.only.method, "DELETE")
        XCTAssertEqual(client.only.query["keys"] as? String, "a,b")
    }

    func testRemovingAnEmptyKeySetMakesNoRequest() async {
        let result = await backend.removeTags(installationId: "install-1", keys: [], all: false)

        XCTAssertTrue(result.isSuccess)
        XCTAssertTrue(client.calls.isEmpty)
    }

    func testAnEmptyEventBatchMakesNoRequest() async {
        let result = await backend.trackEvents(installationId: "install-1", events: [])

        XCTAssertTrue(result.isSuccess)
        XCTAssertTrue(client.calls.isEmpty)
    }

    func testEventsAreBatchedIntoOneRequest() async {
        _ = await backend.trackEvents(
            installationId: "install-1",
            events: [
                PushEvent(name: "notification_received"),
                PushEvent(name: "notification_opened")
            ]
        )

        XCTAssertEqual(client.only.path, "events")
        XCTAssertEqual((client.only.body?["events"] as? [[String: Any]])?.count, 2)
    }

    func testClosingTheBackendClosesItsTransport() {
        backend.close()

        XCTAssertTrue(client.closed)
    }
}

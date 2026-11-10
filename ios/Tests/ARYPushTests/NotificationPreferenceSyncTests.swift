import XCTest
@testable import ARYPush

final class NotificationPreferenceSyncTests: XCTestCase {

    func testSuccessfulOptOutPersistsAndLaterPermissionSyncKeepsItDisabled() async {
        let fixture = Fixture()
        let sync = fixture.makeSync()

        XCTAssertTrue(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        let saved = await sync.setNotificationPreference(false)

        XCTAssertTrue(saved)
        XCTAssertFalse(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 1)

        // A new manager reads the same persisted preference, even when the OS is authorized.
        let restarted = fixture.makeSync()
        restarted.enqueuePermissionUpdate(enabled: true)
        await waitForDrain { fixture.queue.isEmpty }

        XCTAssertFalse(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 2)
        XCTAssertEqual(fixture.backend.count(of: "permission:true"), 0)
    }

    func testDeviceNotFoundPreservesPreviousPreferenceWithoutRegistrationRecovery() async {
        let fixture = Fixture()
        fixture.storage.set(StorageManager.Keys.notificationPreference, false)
        fixture.backend.script(
            .success((), statusCode: 200),
            .failure(ApiError(statusCode: 404, code: "NOT_FOUND", message: "Device not found."))
        )
        let sync = fixture.makeSync()

        let saved = await sync.setNotificationPreference(true)

        XCTAssertFalse(saved)
        XCTAssertFalse(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertEqual(fixture.backend.count(of: "register"), 1)
        XCTAssertEqual(fixture.backend.count(of: "permission:true"), 1)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 0)
        XCTAssertTrue(fixture.queue.isEmpty)
    }

    func testRegistrationFailureDoesNotAttemptToggleOrCommitPreference() async {
        let fixture = Fixture()
        fixture.backend.script(.failure(ApiError(statusCode: 401)))
        let sync = fixture.makeSync()

        let saved = await sync.setNotificationPreference(false)

        XCTAssertFalse(saved)
        XCTAssertTrue(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertEqual(fixture.backend.count(of: "register"), 1)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 0)
    }

    func testEnvironmentChangeDuringRegistrationRejectsPreferenceUntilRetriedInLatestContext() async {
        let fixture = Fixture()
        fixture.backend.onRegister = { [state = fixture.state] _ in
            state.environment = "https://push.example.com|project-b|wallet_ios"
        }
        let sync = fixture.makeSync()

        let saved = await sync.setNotificationPreference(false)

        XCTAssertFalse(saved)
        XCTAssertTrue(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertNil(fixture.storage.string(StorageManager.Keys.registrationHash))
        XCTAssertEqual(fixture.backend.count(of: "register"), 1)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 0)

        fixture.backend.onRegister = nil
        let retried = await sync.setNotificationPreference(false)

        XCTAssertTrue(retried)
        XCTAssertFalse(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertEqual(fixture.backend.count(of: "register"), 2)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 1)
    }

    func testTokenChangeDuringRegistrationRejectsPreferenceUntilRetriedWithLatestToken() async {
        let fixture = Fixture()
        fixture.backend.onRegister = { [state = fixture.state] _ in
            state.token = "rotated-token"
        }
        let sync = fixture.makeSync()

        let saved = await sync.setNotificationPreference(false)

        XCTAssertFalse(saved)
        XCTAssertTrue(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertNil(fixture.storage.string(StorageManager.Keys.registrationHash))
        XCTAssertEqual(fixture.backend.count(of: "register"), 1)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 0)

        fixture.backend.onRegister = nil
        let retried = await sync.setNotificationPreference(false)

        XCTAssertTrue(retried)
        XCTAssertFalse(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true))
        XCTAssertEqual(fixture.backend.count(of: "register"), 2)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 1)
    }

    func testOfflinePreferenceChangeFailsWithoutBackendCalls() async {
        let fixture = Fixture()
        fixture.state.online = false
        let sync = fixture.makeSync()

        let saved = await sync.setNotificationPreference(false)

        XCTAssertFalse(saved)
        assertNoPreferenceWrites(fixture)
    }

    func testUnconfiguredPreferenceChangeFailsWithoutBackendCalls() async {
        let fixture = Fixture()
        fixture.state.environment = nil
        let sync = fixture.makeSync()

        let saved = await sync.setNotificationPreference(false)

        XCTAssertFalse(saved)
        assertNoPreferenceWrites(fixture)
    }

    func testMissingOrBlankTokenPreferenceChangeFailsWithoutBackendCalls() async {
        for token: String? in [nil, "", " \n\t"] {
            let fixture = Fixture()
            fixture.state.token = token
            let sync = fixture.makeSync()

            let saved = await sync.setNotificationPreference(false)

            XCTAssertFalse(saved)
            assertNoPreferenceWrites(fixture)
        }
    }

    func testWorkQueuedWithoutTokenRemainsPendingUntilTokenArrives() async {
        let fixture = Fixture()
        fixture.state.token = nil
        let sync = fixture.makeSync()

        sync.enqueueLogout()
        let saved = await sync.setNotificationPreference(false)
        XCTAssertFalse(saved)
        XCTAssertEqual(fixture.queue.count, 1)
        XCTAssertEqual(fixture.backend.count(of: "register"), 0)
        XCTAssertEqual(fixture.backend.count(of: "logout"), 0)

        fixture.state.token = "ready-token"
        sync.requestSync()
        await waitForDrain { fixture.queue.isEmpty }

        XCTAssertEqual(fixture.backend.count(of: "register"), 1)
        XCTAssertEqual(fixture.backend.count(of: "logout"), 1)
    }

    func testStaleQueuedTokenDoesNotOverwriteLatestInstallationToken() async {
        let fixture = Fixture()
        fixture.state.online = false
        fixture.state.token = "latest-token"
        let sync = fixture.makeSync()
        sync.enqueueTokenUpdate(token: "old-token", provider: .apns)

        fixture.state.online = true
        sync.requestSync()
        await waitForDrain { fixture.queue.isEmpty }

        XCTAssertEqual(fixture.backend.count(of: "token:old-token"), 0)
        XCTAssertEqual(fixture.backend.count(of: "token:latest-token"), 0)

        sync.enqueueTokenUpdate(token: "latest-token", provider: .apns)
        await waitForDrain { fixture.queue.isEmpty }

        XCTAssertEqual(fixture.backend.count(of: "token:latest-token"), 1)
        XCTAssertEqual(fixture.backend.count(of: "token:old-token"), 0)
    }

    private func assertNoPreferenceWrites(
        _ fixture: Fixture,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(fixture.backend.count(of: "register"), 0, file: file, line: line)
        XCTAssertEqual(fixture.backend.count(of: "permission:false"), 0, file: file, line: line)
        XCTAssertEqual(fixture.backend.count(of: "permission:true"), 0, file: file, line: line)
        XCTAssertTrue(fixture.storage.bool(StorageManager.Keys.notificationPreference, default: true), file: file, line: line)
        XCTAssertTrue(fixture.queue.isEmpty, file: file, line: line)
    }

    private func waitForDrain(
        timeout: TimeInterval = 3,
        file: StaticString = #filePath,
        line: UInt = #line,
        until condition: @escaping () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertTrue(condition(), "Timed out waiting for queued writes", file: file, line: line)
    }
}

private final class Fixture {
    let storage = makeTestStorage()
    let backend = FakePushBackend()
    let state = PreferenceTestState()
    lazy var queue = PendingOperationQueue(storage: storage)

    func makeSync() -> SyncManager {
        SyncManager(
            queue: queue,
            storage: storage,
            isOnline: { [state] in state.online },
            retryManager: RetryManager(config: RetryConfig(maxAttempts: 2, initialBackoff: 0.001, jitterFactor: 0)),
            backendProvider: { [backend] in backend },
            installationProvider: { [storage, state] in
                makeTestInstallation(
                    token: state.token,
                    notificationsEnabled: storage.bool(StorageManager.Keys.notificationPreference, default: true)
                )
            },
            environmentProvider: { [state] in state.environment }
        )
    }
}

/// State can be read by the drain task while the test supplies a token or restores connectivity.
private final class PreferenceTestState {
    private let lock = NSLock()
    private var currentOnline = true
    private var currentToken: String? = "token-1"
    private var currentEnvironment: String? = "https://push.example.com|project-a|wallet_ios"

    var online: Bool {
        get { lock.lock(); defer { lock.unlock() }; return currentOnline }
        set { lock.lock(); defer { lock.unlock() }; currentOnline = newValue }
    }

    var token: String? {
        get { lock.lock(); defer { lock.unlock() }; return currentToken }
        set { lock.lock(); defer { lock.unlock() }; currentToken = newValue }
    }

    var environment: String? {
        get { lock.lock(); defer { lock.unlock() }; return currentEnvironment }
        set { lock.lock(); defer { lock.unlock() }; currentEnvironment = newValue }
    }
}

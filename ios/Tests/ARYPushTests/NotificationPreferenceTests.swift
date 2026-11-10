import XCTest
@testable import ARYPush

final class NotificationPreferenceTests: XCTestCase {

    private var storage: StorageManager!

    override func setUp() {
        super.setUp()
        storage = makeTestStorage()
    }

    func testNotificationsAreOnUntilTheApplicationTurnsThemOff() {
        XCTAssertTrue(NotificationPreference(storage: storage).enabledByUser)
    }

    func testTheChoiceSurvivesARestart() {
        NotificationPreference(storage: storage).setEnabledByUser(false)

        XCTAssertFalse(NotificationPreference(storage: storage).enabledByUser)
    }

    func testSettingTheCurrentValueAgainReportsNoChange() {
        let preference = NotificationPreference(storage: storage)

        XCTAssertFalse(preference.setEnabledByUser(true))
        XCTAssertTrue(preference.setEnabledByUser(false))
        XCTAssertFalse(preference.setEnabledByUser(false))
        XCTAssertTrue(preference.setEnabledByUser(true))
    }

    func testTheBackendSeesNotificationsEnabledOnlyWhenTheSystemAndTheUserAllowThem() {
        let preference = NotificationPreference(storage: storage)

        XCTAssertTrue(preference.effective(osAuthorized: true))
        XCTAssertFalse(preference.effective(osAuthorized: false))

        preference.setEnabledByUser(false)
        XCTAssertFalse(preference.effective(osAuthorized: true))
        XCTAssertFalse(preference.effective(osAuthorized: false))
    }

    func testTurningNotificationsOffReRegistersOnceAndTurningThemBackOnReRegistersAgain() {
        let preference = NotificationPreference(storage: storage)
        let queue = PendingOperationQueue(storage: storage)
        let backend = FakePushBackend()
        let sync = SyncManager(
            queue: queue,
            storage: storage,
            isOnline: { true },
            retryManager: RetryManager(
                config: RetryConfig(maxAttempts: 3, initialBackoff: 0.001, jitterFactor: 0),
                randomProvider: { 0 }
            ),
            backendProvider: { backend },
            installationProvider: {
                makeTestInstallation(notificationsEnabled: preference.effective(osAuthorized: true))
            }
        )

        sync.registerInstallationIfChanged()
        settle(queue)
        preference.setEnabledByUser(false)
        sync.registerInstallationIfChanged()
        settle(queue)
        sync.registerInstallationIfChanged()     // unchanged: nothing more
        settle(queue)
        preference.setEnabledByUser(true)
        sync.registerInstallationIfChanged()
        settle(queue)

        XCTAssertEqual(backend.count(of: "register"), 3)
    }

    private func settle(_ queue: PendingOperationQueue) {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline, !queue.isEmpty {
            RunLoop.current.run(until: Date().addingTimeInterval(0.01))
        }
        RunLoop.current.run(until: Date().addingTimeInterval(0.1))
    }
}

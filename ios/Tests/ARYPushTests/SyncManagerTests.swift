import XCTest
@testable import ARYPush

final class SyncManagerTests: XCTestCase {

    private var storage: StorageManager!
    private var queue: PendingOperationQueue!
    private var backend: FakePushBackend!
    private var online = true

    override func setUp() {
        super.setUp()
        storage = makeTestStorage()
        queue = PendingOperationQueue(storage: storage)
        backend = FakePushBackend()
        online = true
    }

    private func makeSync(
        retry: RetryConfig = RetryConfig(maxAttempts: 3, initialBackoff: 0.001, jitterFactor: 0)
    ) -> SyncManager {
        SyncManager(
            queue: queue,
            storage: storage,
            isOnline: { [weak self] in self?.online ?? true },
            retryManager: RetryManager(config: retry, randomProvider: { 0 }),
            backendProvider: { [backend] in backend! },
            installationProvider: { makeTestInstallation() }
        )
    }

    /// Waits for the drain task to settle. The queue is durable, so "settled" means either
    /// empty or explicitly parked because the device is offline.
    private func waitForDrain(
        timeout: TimeInterval = 5,
        until condition: @escaping () -> Bool
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.01))
        }
        XCTFail("Timed out waiting for the sync queue to settle")
    }

    func testAQueuedOperationReachesTheBackendAndLeavesTheQueue() {
        let sync = makeSync()

        sync.enqueueLogout()

        waitForDrain { self.queue.isEmpty }
        XCTAssertTrue(backend.calls.contains("logout"))
    }

    func testNothingIsSentWhileOfflineAndNothingIsLost() {
        online = false
        let sync = makeSync()

        sync.enqueueIdentify(userId: "USER_1")

        // Give the drain a chance to run and decide it cannot.
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        XCTAssertTrue(backend.calls.isEmpty, "no request may be attempted offline")
        XCTAssertEqual(queue.count, 1)
    }

    func testWorkQueuedOfflineDrainsOnceTheNetworkReturns() {
        online = false
        let sync = makeSync()
        sync.enqueueIdentify(userId: "USER_1")
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))

        online = true
        sync.requestSync()

        waitForDrain { self.queue.isEmpty }
        XCTAssertTrue(backend.calls.contains("identify:USER_1"))
    }

    func testDependentOperationsRegisterTheInstallationFirst() {
        let sync = makeSync()

        sync.enqueueIdentify(userId: "USER_1")

        waitForDrain { self.queue.isEmpty }
        // Identifying a user against an installation the backend has never seen is meaningless.
        XCTAssertEqual(backend.calls, ["register", "identify:USER_1"])
    }

    func testAnUnchangedRegistrationIsNotResentOnEveryLaunch() {
        let sync = makeSync()

        sync.registerInstallationIfChanged()
        waitForDrain { self.queue.isEmpty }
        sync.registerInstallationIfChanged()
        sync.registerInstallationIfChanged()
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))

        XCTAssertEqual(backend.count(of: "register"), 1)
    }

    func testATransientFailureIsRetriedAndThenSucceeds() {
        backend.script(.failure(ApiError(statusCode: 503)), .success((), statusCode: 200))
        let sync = makeSync()

        sync.enqueue(PendingOperation(type: .registerInstallation))

        waitForDrain { self.queue.isEmpty }
        XCTAssertEqual(backend.count(of: "register"), 2)
    }

    func testAPermanentFailureIsDroppedInsteadOfRetriedForever() {
        backend.defaultResult = .failure(ApiError(statusCode: 422, code: "invalid_installation"))
        let sync = makeSync()

        sync.enqueue(PendingOperation(type: .registerInstallation))

        waitForDrain { self.queue.isEmpty }
        // One attempt only: a 422 will never succeed, and retrying it would wedge the queue.
        XCTAssertEqual(backend.count(of: "register"), 1)
    }

    func testAnOperationThatExhaustsItsAttemptsIsDropped() {
        backend.defaultResult = .networkError(URLError(.notConnectedToInternet))
        let sync = makeSync(
            retry: RetryConfig(maxAttempts: 3, initialBackoff: 0.001, jitterFactor: 0)
        )

        sync.enqueue(PendingOperation(type: .registerInstallation))

        waitForDrain { self.queue.isEmpty }
        XCTAssertEqual(backend.count(of: "register"), 3)
    }

    func testABurstOfTagWritesBecomesASingleRequest() {
        let sync = makeSync()

        sync.enqueueTagUpdate(["a": "1"])
        sync.enqueueTagUpdate(["b": "2"])
        sync.enqueueTagUpdate(["c": "3"])

        waitForDrain { self.queue.isEmpty }
        let tagCalls = backend.calls.filter { $0.hasPrefix("tags:") }
        XCTAssertEqual(tagCalls.count, 1)
        XCTAssertTrue(tagCalls[0].contains("a=1"))
        XCTAssertTrue(tagCalls[0].contains("c=3"))
    }

    func testResetClearsDeferredWorkAndTheRegistrationMarker() {
        let sync = makeSync()
        sync.registerInstallationIfChanged()
        waitForDrain { self.queue.isEmpty }

        sync.reset()

        XCTAssertTrue(queue.isEmpty)
        XCTAssertNil(storage.string(StorageManager.Keys.registrationHash))
    }
}

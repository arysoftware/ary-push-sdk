import XCTest
@testable import ARYPush

final class PendingOperationQueueTests: XCTestCase {

    private var storage: StorageManager!
    private var queue: PendingOperationQueue!

    override func setUp() {
        super.setUp()
        storage = makeTestStorage()
        queue = PendingOperationQueue(storage: storage)
    }

    private func tagOperation(_ tags: [String: String]) -> PendingOperation {
        PendingOperation(
            type: .updateTags,
            payload: [PendingOperation.Key.tags: queue.encodeTags(tags)]
        )
    }

    func testTheQueueStartsEmpty() {
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(queue.count, 0)
    }

    func testQueuedWorkSurvivesAProcessRestart() {
        queue.enqueue(PendingOperation(type: .logoutUser))

        let reopened = PendingOperationQueue(storage: storage)

        XCTAssertEqual(reopened.count, 1)
        XCTAssertEqual(reopened.snapshot().first?.type, .logoutUser)
    }

    func testANewerTokenReplacesAnOlderUnsentOne() {
        queue.enqueue(
            PendingOperation(type: .updateToken, payload: [PendingOperation.Key.token: "old"])
        )
        queue.enqueue(
            PendingOperation(type: .updateToken, payload: [PendingOperation.Key.token: "new"])
        )

        // Sending the stale token first would briefly point the backend at a dead route.
        let operations = queue.snapshot()
        XCTAssertEqual(operations.count, 1)
        XCTAssertEqual(operations.first?.payload[PendingOperation.Key.token], "new")
    }

    func testABurstOfTagWritesCollapsesIntoOneMergedOperation() {
        queue.enqueue(tagOperation(["subscription": "premium"]))
        queue.enqueue(tagOperation(["language": "en"]))
        queue.enqueue(tagOperation(["subscription": "gold"]))

        let operations = queue.snapshot()
        XCTAssertEqual(operations.count, 1)

        let merged = queue.decodeTags(operations[0].payload[PendingOperation.Key.tags])
        XCTAssertEqual(merged, ["subscription": "gold", "language": "en"])
    }

    func testLogoutAndIdentifySupersedeEachOther() {
        queue.enqueue(
            PendingOperation(type: .identifyUser, payload: [PendingOperation.Key.userId: "U1"])
        )
        queue.enqueue(PendingOperation(type: .logoutUser))
        XCTAssertEqual(queue.snapshot().map(\.type), [.logoutUser])

        queue.enqueue(
            PendingOperation(type: .identifyUser, payload: [PendingOperation.Key.userId: "U2"])
        )
        XCTAssertEqual(queue.snapshot().map(\.type), [.identifyUser])
    }

    func testSnapshotsComeBackInDependencyOrder() {
        queue.enqueue(tagOperation(["a": "1"]))
        queue.enqueue(
            PendingOperation(type: .identifyUser, payload: [PendingOperation.Key.userId: "U1"])
        )
        queue.enqueue(PendingOperation(type: .registerInstallation))

        XCTAssertEqual(
            queue.snapshot().map(\.type),
            [.registerInstallation, .identifyUser, .updateTags]
        )
    }

    func testAttemptsArePersisted() {
        queue.enqueue(PendingOperation(type: .logoutUser))
        let id = queue.snapshot()[0].id

        queue.recordAttempt(id: id)
        queue.recordAttempt(id: id)

        XCTAssertEqual(PendingOperationQueue(storage: storage).snapshot()[0].attempts, 2)
    }

    func testTheQueueIsBounded() {
        for index in 0..<300 {
            queue.enqueue(
                PendingOperation(
                    type: .trackEvents,
                    payload: [PendingOperation.Key.events: "[]"],
                    createdAt: Date(timeIntervalSince1970: TimeInterval(index))
                )
            )
        }

        XCTAssertLessThanOrEqual(queue.count, 100)
    }

    func testACorruptedQueueIsResetRatherThanBlockingEveryFutureWrite() {
        storage.set(StorageManager.Keys.pendingOperations, "[[[not json")

        XCTAssertTrue(queue.snapshot().isEmpty)

        queue.enqueue(PendingOperation(type: .logoutUser))
        XCTAssertFalse(queue.isEmpty)
    }
}

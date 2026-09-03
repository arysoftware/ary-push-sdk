import XCTest
@testable import ARYPush

final class DeduplicationManagerTests: XCTestCase {

    private var storage: StorageManager!

    override func setUp() {
        super.setUp()
        storage = makeTestStorage()
    }

    func testAMessageIsNewExactlyOnce() {
        let dedup = DeduplicationManager(storage: storage, maxSize: 10)

        XCTAssertTrue(dedup.markSeenIfNew("m1"))
        XCTAssertFalse(dedup.markSeenIfNew("m1"))
        XCTAssertFalse(dedup.markSeenIfNew("m1"))
    }

    func testDistinctMessagesAreAllNew() {
        let dedup = DeduplicationManager(storage: storage, maxSize: 10)

        XCTAssertTrue(dedup.markSeenIfNew("m1"))
        XCTAssertTrue(dedup.markSeenIfNew("m2"))
        XCTAssertEqual(dedup.count, 2)
    }

    func testTheCacheSurvivesAProcessRestart() {
        DeduplicationManager(storage: storage, maxSize: 10).markSeenIfNew("m1")

        // A background message often kills the process right after handling; the redelivery
        // that follows must still be recognised.
        XCTAssertFalse(DeduplicationManager(storage: storage, maxSize: 10).markSeenIfNew("m1"))
    }

    func testTheCacheIsBoundedAndEvictsOldestFirst() {
        let dedup = DeduplicationManager(storage: storage, maxSize: 5)

        for index in 0..<20 { dedup.markSeenIfNew("m\(index)") }

        XCTAssertEqual(dedup.count, 5)
        XCTAssertTrue(dedup.hasSeen("m19"))
        XCTAssertFalse(dedup.hasSeen("m0"))
    }

    func testABlankIdIsNeverCollapsed() {
        let dedup = DeduplicationManager(storage: storage, maxSize: 10)

        XCTAssertTrue(dedup.markSeenIfNew(""))
        XCTAssertTrue(dedup.markSeenIfNew(""))
        XCTAssertEqual(dedup.count, 0)
    }

    func testReceiptAndOpenAreTrackedIndependently() {
        let dedup = DeduplicationManager(storage: storage, maxSize: 10)

        XCTAssertTrue(dedup.markSeenIfNew("m1"))
        // Opening a message that was already received must not be swallowed as a duplicate.
        XCTAssertTrue(dedup.markSeenIfNew("open:m1:"))
        XCTAssertTrue(dedup.markSeenIfNew("open:m1:accept"))
        XCTAssertFalse(dedup.markSeenIfNew("open:m1:accept"))
    }
}

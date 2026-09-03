import XCTest
@testable import ARYPush

final class StableHashTests: XCTestCase {

    func testTheSameInputAlwaysProducesTheSameDigest() {
        XCTAssertEqual(StableHash.digest("order-42"), StableHash.digest("order-42"))
    }

    func testDifferentInputsProduceDifferentDigests() {
        XCTAssertNotEqual(StableHash.digest("a"), StableHash.digest("b"))
    }

    func testTheDigestIsPinnedAcrossProcessesAndReleases() {
        // The whole point of this type is that the value does not move. A notification identity
        // and a registration hash are both persisted, so a change here would silently break
        // deduplication and re-register every installation on upgrade.
        XCTAssertEqual(StableHash.digest(""), "cbf29ce484222325")
        XCTAssertEqual(StableHash.digest("a"), "af63dc4c8601ec8c")
    }

    func testEmptyAndWhitespaceDiffer() {
        XCTAssertNotEqual(StableHash.digest(""), StableHash.digest(" "))
    }
}

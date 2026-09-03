import XCTest
@testable import ARYPush

final class HTTPHeaderParsingTests: XCTestCase {

    func testDeltaSecondsAreParsed() {
        XCTAssertEqual(HTTPHeaderParsing.parseRetryAfter("120"), 120)
        XCTAssertEqual(HTTPHeaderParsing.parseRetryAfter("0"), 0)
    }

    func testWhitespaceIsTolerated() {
        XCTAssertEqual(HTTPHeaderParsing.parseRetryAfter("  5 "), 5)
    }

    func testHTTPDateIsConvertedToADelay() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let header = HTTPHeaderParsing.formatHTTPDate(now.addingTimeInterval(90))

        let parsed = HTTPHeaderParsing.parseRetryAfter(header, now: now)

        XCTAssertNotNil(parsed)
        // HTTP dates have second precision, so the result is within a second of 90.
        XCTAssertEqual(parsed ?? 0, 90, accuracy: 1)
    }

    func testHTTPDateInThePastMeansRetryImmediately() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let header = HTTPHeaderParsing.formatHTTPDate(now.addingTimeInterval(-60))

        XCTAssertEqual(HTTPHeaderParsing.parseRetryAfter(header, now: now), 0)
    }

    func testUnusableValuesAreIgnored() {
        XCTAssertNil(HTTPHeaderParsing.parseRetryAfter(nil))
        XCTAssertNil(HTTPHeaderParsing.parseRetryAfter(""))
        XCTAssertNil(HTTPHeaderParsing.parseRetryAfter("   "))
        XCTAssertNil(HTTPHeaderParsing.parseRetryAfter("soon"))
        XCTAssertNil(HTTPHeaderParsing.parseRetryAfter("-30"))
    }

    func testImplausiblyDistantValueIsClampedToADay() {
        XCTAssertEqual(HTTPHeaderParsing.parseRetryAfter("9999999"), 24 * 60 * 60)
    }
}

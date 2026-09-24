import XCTest
@testable import ARYPush

/// Which links the SDK hands to the application itself, rather than to `UIApplication.open`,
/// decides whether a tap on one lands in the app or in Safari.
final class UniversalLinkMatchingTests: XCTestCase {

    private func matches(_ link: String, _ domains: [String]) -> Bool {
        PushCore.isUniversalLink(URL(string: link)!, ownedBy: domains)
    }

    func testAnExactHostMatches() {
        XCTAssertTrue(matches("https://ary.com/offers/1", ["ary.com"]))
        XCTAssertTrue(matches("https://ARY.com/offers/1", [" ary.com "]))
        XCTAssertFalse(matches("https://www.ary.com/offers/1", ["ary.com"]))
        XCTAssertFalse(matches("https://notary.com/", ["ary.com"]))
    }

    func testAWildcardMatchesTheDomainAndEverySubdomain() {
        XCTAssertTrue(matches("https://ary.com/a", ["*.ary.com"]))
        XCTAssertTrue(matches("https://news.ary.com/a", ["*.ary.com"]))
        XCTAssertFalse(matches("https://notary.com/a", ["*.ary.com"]))
    }

    func testTheEntitlementSpellingIsAccepted() {
        XCTAssertTrue(matches("https://ary.com/a", ["applinks:ary.com"]))
    }

    func testCustomSchemesAreLeftToTheSystem() {
        XCTAssertFalse(matches("aryapp://offers/1", ["ary.com"]))
        XCTAssertFalse(matches("https://ary.com/a", []))
        XCTAssertFalse(matches("https://ary.com/a", [""]))
    }
}

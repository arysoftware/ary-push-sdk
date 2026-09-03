import XCTest
@testable import ARYPush

final class NotificationParserTests: XCTestCase {

    private func payload(
        alert: Any? = nil,
        extra: [String: Any] = [:]
    ) -> [AnyHashable: Any] {
        var aps: [String: Any] = [:]
        if let alert { aps["alert"] = alert }
        var userInfo: [AnyHashable: Any] = ["aps": aps]
        extra.forEach { userInfo[$0.key] = $0.value }
        return userInfo
    }

    func testTitleAndBodyAreReadFromADictionaryAlert() {
        let parsed = NotificationParser.parse(
            userInfo: payload(
                alert: ["title": "Order shipped", "body": "On its way"],
                extra: ["notification_id": "order-42"]
            ),
            wasForeground: false
        )

        XCTAssertEqual(parsed.title, "Order shipped")
        XCTAssertEqual(parsed.body, "On its way")
        XCTAssertEqual(parsed.id, "order-42")
    }

    func testAStringAlertBecomesTheBody() {
        let parsed = NotificationParser.parse(
            userInfo: payload(alert: "Simple message", extra: ["notification_id": "m1"]),
            wasForeground: false
        )

        XCTAssertNil(parsed.title)
        XCTAssertEqual(parsed.body, "Simple message")
    }

    func testTheCustomPayloadIsDeliveredWithoutTransportPlumbing() {
        let parsed = NotificationParser.parse(
            userInfo: payload(
                extra: [
                    "notification_id": "m1",
                    "action": "open_order",
                    "orderId": "12345",
                    "gcm.message_id": "should-not-appear",
                    "google.c.sender.id": "should-not-appear"
                ]
            ),
            wasForeground: false
        )

        XCTAssertEqual(parsed.action, "open_order")
        XCTAssertEqual(parsed.data["orderId"], "12345")
        XCTAssertNil(parsed.data["aps"])
        XCTAssertNil(parsed.data["gcm.message_id"])
        XCTAssertNil(parsed.data["google.c.sender.id"])
    }

    func testASenderSuppliedIdWinsOverTheTransportId() {
        // A sender-supplied id groups resends of one logical message; the transport id is unique
        // per delivery attempt and would defeat deduplication.
        let parsed = NotificationParser.parse(
            userInfo: payload(extra: ["notification_id": "order-42", "gcm.message_id": "fcm-1"]),
            wasForeground: false
        )

        XCTAssertEqual(parsed.id, "order-42")
    }

    func testMessagesWithNoIdGetAStableContentIdentity() {
        let first = NotificationParser.parse(
            userInfo: payload(alert: ["title": "Hi", "body": "There"]),
            wasForeground: false
        )
        let second = NotificationParser.parse(
            userInfo: payload(alert: ["title": "Hi", "body": "There"]),
            wasForeground: false
        )

        XCTAssertTrue(first.id.hasPrefix("hash-"))
        XCTAssertEqual(first.id, second.id, "identical payloads must deduplicate")
    }

    func testDifferentContentProducesDifferentIdentities() {
        let first = NotificationParser.parse(userInfo: payload(alert: "A"), wasForeground: false)
        let second = NotificationParser.parse(userInfo: payload(alert: "B"), wasForeground: false)

        XCTAssertNotEqual(first.id, second.id)
    }

    func testTheImageIsReadFromFcmOptionsOrAConventionalKey() {
        let fromOptions = NotificationParser.parse(
            userInfo: payload(extra: ["fcm_options": ["image": "https://cdn.ary.com/a.png"]]),
            wasForeground: false
        )
        XCTAssertEqual(fromOptions.imageURL, "https://cdn.ary.com/a.png")

        let fromData = NotificationParser.parse(
            userInfo: payload(extra: ["image_url": "https://cdn.ary.com/b.png"]),
            wasForeground: false
        )
        XCTAssertEqual(fromData.imageURL, "https://cdn.ary.com/b.png")
    }

    func testForegroundStateAndActionAreRecorded() {
        let parsed = NotificationParser.parse(
            userInfo: payload(extra: ["notification_id": "m1"]),
            wasForeground: true,
            actionId: "track"
        )

        XCTAssertTrue(parsed.wasForeground)
        XCTAssertEqual(parsed.actionId, "track")
    }
}

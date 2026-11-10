import XCTest
@testable import ARYPush

final class NotificationURLConfigurationTests: XCTestCase {

    func testAutomaticURLOpeningRemainsEnabledByDefault() {
        XCTAssertTrue(ARYPushConfig().autoOpenNotificationURLs)
    }

    func testHostCanDisableAutomaticURLOpening() {
        XCTAssertFalse(ARYPushConfig(autoOpenNotificationURLs: false).autoOpenNotificationURLs)
    }

    func testPlistOptOutIsRead() throws {
        XCTAssertFalse(try plistConfig(["AutoOpenNotificationURLs": false]).autoOpenNotificationURLs)
        XCTAssertTrue(try plistConfig(["AutoOpenNotificationURLs": true]).autoOpenNotificationURLs)
    }

    func testOmittedOrInvalidPlistOptionPreservesAutomaticOpening() throws {
        XCTAssertTrue(try plistConfig([:]).autoOpenNotificationURLs)
        XCTAssertTrue(try plistConfig(["AutoOpenNotificationURLs": "false"]).autoOpenNotificationURLs)
    }

    private func plistConfig(_ settings: [String: Any]) throws -> ARYPushConfig {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".bundle", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let data = try PropertyListSerialization.data(
            fromPropertyList: ["CFBundleIdentifier": "com.ary.push.config-tests", "ARYPush": settings],
            format: .xml,
            options: 0
        )
        try data.write(to: directory.appendingPathComponent("Info.plist"))
        let bundle = try XCTUnwrap(Bundle(url: directory))
        return InfoPlistConfigReader.read(bundle: bundle)
    }
}

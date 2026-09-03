import XCTest
@testable import ARYPush

final class NotificationEventDispatcherTests: XCTestCase {

    private var storage: StorageManager!
    private var dispatcher: NotificationEventDispatcher!

    override func setUp() {
        super.setUp()
        storage = makeTestStorage()
        dispatcher = NotificationEventDispatcher(storage: storage)
    }

    private func notification(id: String = "n1", action: String? = nil) -> PushNotification {
        PushNotification(id: id, title: "T", body: "B", actionId: action)
    }

    func testReceivedEventsReachEveryListener() {
        var seenA: [PushNotification] = []
        var seenB: [PushNotification] = []
        _ = dispatcher.addReceivedListener { seenA.append($0) }
        _ = dispatcher.addReceivedListener { seenB.append($0) }

        dispatcher.dispatchReceived(notification())

        XCTAssertEqual(seenA.count, 1)
        XCTAssertEqual(seenB.count, 1)
    }

    func testARemovedListenerStopsReceivingEvents() {
        var seen: [PushNotification] = []
        let id = dispatcher.addReceivedListener { seen.append($0) }
        dispatcher.removeReceivedListener(id)

        dispatcher.dispatchReceived(notification())

        XCTAssertTrue(seen.isEmpty)
    }

    func testAnOpenWithNobodyListeningIsPersisted() {
        dispatcher.dispatchOpened(notification(id: "order-42"))

        // This is the terminated-application case: the tap happens during didFinishLaunching,
        // before any host code has attached a listener.
        XCTAssertEqual(dispatcher.peekPendingOpen()?.id, "order-42")
    }

    func testAPersistedOpenIsReplayedToTheFirstListener() {
        dispatcher.dispatchOpened(notification(id: "order-42", action: "track"))

        var seen: [PushNotification] = []
        _ = dispatcher.addOpenedListener { seen.append($0) }

        XCTAssertEqual(seen.count, 1)
        XCTAssertEqual(seen.first?.id, "order-42")
        XCTAssertEqual(seen.first?.actionId, "track")
    }

    func testAReplayedOpenIsDeliveredExactlyOnce() {
        dispatcher.dispatchOpened(notification(id: "order-42"))

        var first: [PushNotification] = []
        var second: [PushNotification] = []
        _ = dispatcher.addOpenedListener { first.append($0) }
        _ = dispatcher.addOpenedListener { second.append($0) }

        XCTAssertEqual(first.count, 1)
        XCTAssertTrue(second.isEmpty, "the second listener must not see the same tap again")
        XCTAssertNil(dispatcher.peekPendingOpen())
    }

    func testAPendingOpenSurvivesAProcessRestart() {
        dispatcher.dispatchOpened(notification(id: "order-42"))

        let afterRestart = NotificationEventDispatcher(storage: storage)

        XCTAssertEqual(afterRestart.consumePendingOpen()?.id, "order-42")
    }

    func testAnOpenWithAListenerIsDeliveredLiveAndNotPersisted() {
        var seen: [PushNotification] = []
        _ = dispatcher.addOpenedListener { seen.append($0) }

        dispatcher.dispatchOpened(notification(id: "order-42"))

        XCTAssertEqual(seen.count, 1)
        XCTAssertNil(dispatcher.peekPendingOpen())
    }

    func testReceivedEventsAreNotPersisted() {
        // A message nobody was listening for is stale by the time the app starts, and the
        // notification is already in Notification Centre.
        dispatcher.dispatchReceived(notification())

        XCTAssertNil(dispatcher.peekPendingOpen())
    }
}

import XCTest
@testable import ARYPush

final class BackendWriteQueueTests: XCTestCase {

    func testSuspendedWriteKeepsNextWriteFromEnteringUntilItFinishes() async {
        let queue = BackendWriteQueue()
        let gate = WriteTestGate()
        let events = WriteTestEvents()
        let firstEntered = expectation(description: "First write entered")
        let secondSubmitted = expectation(description: "Second write submitted")
        let secondEnteredTooEarly = expectation(description: "Second write overlapped suspended first write")
        secondEnteredTooEarly.isInverted = true

        let first = Task {
            await queue.run {
                await events.append("first:start")
                firstEntered.fulfill()
                await gate.wait()
                await events.append("first:end")
                return 11
            }
        }
        await fulfillment(of: [firstEntered], timeout: 2)

        let second = Task {
            secondSubmitted.fulfill()
            return await queue.run {
                if !gate.isOpen { secondEnteredTooEarly.fulfill() }
                await events.append("second:start")
                await events.append("second:end")
                return 22
            }
        }
        await fulfillment(of: [secondSubmitted], timeout: 2)
        // The first write remains suspended throughout this negative assertion; no sleep
        // determines execution order. An actor that directly awaited the closure would reenter.
        await fulfillment(of: [secondEnteredTooEarly], timeout: 0.1)

        gate.open()
        let firstResult = await first.value
        let secondResult = await second.value
        let recorded = await events.values

        XCTAssertEqual(firstResult, 11)
        XCTAssertEqual(secondResult, 22)
        XCTAssertEqual(recorded, ["first:start", "first:end", "second:start", "second:end"])
    }
}

private actor WriteTestEvents {
    private(set) var values: [String] = []

    func append(_ value: String) {
        values.append(value)
    }
}

/// A lock protects both early opening and suspension, so opening never loses a wake-up.
private final class WriteTestGate {
    private let lock = NSLock()
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return opened
    }

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.lock()
            if opened {
                lock.unlock()
                continuation.resume()
            } else {
                self.continuation = continuation
                lock.unlock()
            }
        }
    }

    func open() {
        lock.lock()
        opened = true
        let waiter = continuation
        continuation = nil
        lock.unlock()
        waiter?.resume()
    }
}

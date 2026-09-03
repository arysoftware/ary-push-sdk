import XCTest
@testable import ARYPush

final class RetryManagerTests: XCTestCase {

    /// Jitter disabled so backoff growth itself can be asserted exactly.
    private func deterministic(_ config: RetryConfig) -> RetryManager {
        RetryManager(config: config, randomProvider: { 0 })
    }

    func testBackoffGrowsExponentially() {
        let retry = deterministic(
            RetryConfig(initialBackoff: 1, backoffMultiplier: 2, jitterFactor: 0)
        )

        XCTAssertEqual(retry.delay(attempt: 1), 1, accuracy: 0.0001)
        XCTAssertEqual(retry.delay(attempt: 2), 2, accuracy: 0.0001)
        XCTAssertEqual(retry.delay(attempt: 3), 4, accuracy: 0.0001)
        XCTAssertEqual(retry.delay(attempt: 4), 8, accuracy: 0.0001)
    }

    func testBackoffIsCapped() {
        let retry = deterministic(
            RetryConfig(initialBackoff: 1, maxBackoff: 5, jitterFactor: 0)
        )

        XCTAssertEqual(retry.delay(attempt: 10), 5, accuracy: 0.0001)
        XCTAssertEqual(retry.delay(attempt: 50), 5, accuracy: 0.0001)
    }

    func testFullJitterStaysInsideTheExponentialBound() {
        var seed = 0.0
        let retry = RetryManager(
            config: RetryConfig(initialBackoff: 1, backoffMultiplier: 2, jitterFactor: 1),
            randomProvider: {
                seed += 0.137
                if seed >= 1 { seed -= 1 }
                return seed
            }
        )

        for _ in 0..<200 {
            let delay = retry.delay(attempt: 3)
            XCTAssertGreaterThanOrEqual(delay, 0)
            XCTAssertLessThanOrEqual(delay, 4)
        }
    }

    func testJitterActuallyVariesTheDelay() {
        var seed = 0.0
        let retry = RetryManager(
            config: RetryConfig(initialBackoff: 1, jitterFactor: 1),
            randomProvider: {
                seed += 0.31
                if seed >= 1 { seed -= 1 }
                return seed
            }
        )

        // Without variation, a backend outage produces a synchronised retry storm across every
        // device that failed at the same moment.
        let delays = Set((0..<20).map { _ in retry.delay(attempt: 4) })
        XCTAssertGreaterThan(delays.count, 1)
    }

    func testRetryAfterWinsOverComputedBackoff() {
        let retry = deterministic(RetryConfig(jitterFactor: 0))

        XCTAssertEqual(retry.delay(attempt: 1, retryAfter: 30), 30, accuracy: 0.0001)
    }

    func testAbsurdRetryAfterIsClamped() {
        let retry = deterministic(RetryConfig(jitterFactor: 0, maxRetryAfter: 60))

        XCTAssertEqual(retry.delay(attempt: 1, retryAfter: 48 * 3600), 60, accuracy: 0.0001)
    }

    func testRetryAfterIsIgnoredWhenDisabled() {
        let retry = deterministic(
            RetryConfig(initialBackoff: 1, jitterFactor: 0, respectRetryAfter: false)
        )

        XCTAssertEqual(retry.delay(attempt: 1, retryAfter: 30), 1, accuracy: 0.0001)
    }

    func testAttemptsAreBounded() {
        let retry = deterministic(RetryConfig(maxAttempts: 3, jitterFactor: 0))

        XCTAssertTrue(retry.canRetry(attempt: 1))
        XCTAssertTrue(retry.canRetry(attempt: 2))
        XCTAssertFalse(retry.canRetry(attempt: 3))
        XCTAssertFalse(retry.canRetry(attempt: 99))
    }
}

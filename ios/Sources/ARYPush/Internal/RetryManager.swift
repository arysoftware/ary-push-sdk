import Foundation

/// Exponential backoff with jitter.
///
/// Jitter is not decoration. Without it, every device that failed against the same backend
/// outage retries at the same instant and re-creates the outage. The SDK is embedded in many
/// applications, so a synchronised retry storm is a realistic failure mode rather than a
/// theoretical one.
struct RetryManager {

    private let config: RetryConfig
    private let randomProvider: () -> Double

    init(config: RetryConfig, randomProvider: @escaping () -> Double = { Double.random(in: 0..<1) }) {
        self.config = config
        self.randomProvider = randomProvider
    }

    var maxAttempts: Int { config.maxAttempts }

    /// True while another attempt is permitted. `attempt` is 1-based.
    func canRetry(attempt: Int) -> Bool { attempt < config.maxAttempts }

    /// Delay before the attempt following `attempt`.
    ///
    /// A server-supplied `Retry-After` always wins when present and permitted, clamped so that a
    /// hostile or mistaken header cannot park the queue indefinitely.
    func delay(attempt: Int, retryAfter: TimeInterval? = nil) -> TimeInterval {
        if config.respectRetryAfter, let retryAfter, retryAfter > 0 {
            return min(retryAfter, config.maxRetryAfter)
        }

        let exponent = Double(max(0, attempt - 1))
        let exponential = config.initialBackoff * pow(config.backoffMultiplier, exponent)
        let capped = min(exponential, config.maxBackoff)
        guard config.jitterFactor > 0 else { return capped }

        // Full jitter at factor 1.0: uniform over [0, capped]. Lower factors keep a fixed floor
        // so backoff still grows predictably.
        let floor = capped * (1 - config.jitterFactor)
        return max(0, floor + randomProvider() * (capped - floor))
    }
}

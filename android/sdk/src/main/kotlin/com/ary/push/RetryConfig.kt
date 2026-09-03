package com.ary.push

/**
 * Exponential backoff with full jitter, used by the sync queue and by transient HTTP failures.
 *
 * The delay before attempt `n` (1-based) is a random value in
 * `[0, min(maxBackoffMs, initialBackoffMs * multiplier^(n-1))]` scaled by [jitterFactor]:
 * a jitter factor of `1.0` is full jitter, `0.0` disables jitter entirely.
 */
public data class RetryConfig @JvmOverloads constructor(
    /** Total attempts, including the first one. */
    public val maxAttempts: Int = 5,
    public val initialBackoffMs: Long = 1_000L,
    public val maxBackoffMs: Long = 5 * 60_000L,
    public val backoffMultiplier: Double = 2.0,
    public val jitterFactor: Double = 1.0,

    /** Honour a `Retry-After` response header when the server sends one. */
    public val respectRetryAfter: Boolean = true,

    /** Upper bound applied to a server-supplied `Retry-After`, to bound queue latency. */
    public val maxRetryAfterMs: Long = 15 * 60_000L
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialBackoffMs > 0) { "initialBackoffMs must be positive" }
        require(maxBackoffMs >= initialBackoffMs) { "maxBackoffMs must be >= initialBackoffMs" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        require(jitterFactor in 0.0..1.0) { "jitterFactor must be within 0.0..1.0" }
    }
}

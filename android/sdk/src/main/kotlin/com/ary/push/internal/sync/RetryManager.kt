package com.ary.push.internal.sync

import com.ary.push.RetryConfig
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter.
 *
 * Jitter is not decoration. Without it, every device that failed against the same backend
 * outage retries at the same instant and re-creates the outage; the SDK is embedded in many
 * applications, so a synchronised retry storm is a realistic failure mode rather than a
 * theoretical one.
 */
internal class RetryManager(
    private val config: RetryConfig,
    private val random: Random = Random.Default
) {

    /** True while another attempt is permitted. [attempt] is 1-based. */
    fun canRetry(attempt: Int): Boolean = attempt < config.maxAttempts

    /**
     * Delay before the attempt following [attempt].
     *
     * A server-supplied `Retry-After` always wins when present and permitted, clamped so that a
     * hostile or mistaken header cannot park the queue indefinitely.
     */
    fun delayMillis(attempt: Int, retryAfterMs: Long? = null): Long {
        if (config.respectRetryAfter && retryAfterMs != null && retryAfterMs > 0) {
            return min(retryAfterMs, config.maxRetryAfterMs)
        }
        val exponential = config.initialBackoffMs *
            config.backoffMultiplier.pow((attempt - 1).coerceAtLeast(0))
        val capped = min(exponential, config.maxBackoffMs.toDouble())
        if (config.jitterFactor <= 0.0) return capped.toLong()

        // Full jitter at factor 1.0: uniform over [0, capped]. Lower factors keep a fixed
        // floor so that backoff still grows predictably.
        val floor = capped * (1.0 - config.jitterFactor)
        val span = capped - floor
        return (floor + random.nextDouble() * span).toLong().coerceAtLeast(0L)
    }

    /** Total attempts permitted, including the first. */
    val maxAttempts: Int get() = config.maxAttempts
}

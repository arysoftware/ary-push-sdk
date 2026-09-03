package com.ary.push.internal.sync

import com.ary.push.RetryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryManagerTest {

    /** Jitter is disabled so backoff growth itself can be asserted exactly. */
    private fun deterministic(config: RetryConfig = RetryConfig(jitterFactor = 0.0)) =
        RetryManager(config)

    @Test
    fun `backoff grows exponentially from the initial delay`() {
        val retry = deterministic(RetryConfig(initialBackoffMs = 1_000, backoffMultiplier = 2.0, jitterFactor = 0.0))

        assertEquals(1_000L, retry.delayMillis(attempt = 1))
        assertEquals(2_000L, retry.delayMillis(attempt = 2))
        assertEquals(4_000L, retry.delayMillis(attempt = 3))
        assertEquals(8_000L, retry.delayMillis(attempt = 4))
    }

    @Test
    fun `backoff is capped at the configured maximum`() {
        val retry = deterministic(
            RetryConfig(initialBackoffMs = 1_000, maxBackoffMs = 5_000, jitterFactor = 0.0)
        )

        assertEquals(5_000L, retry.delayMillis(attempt = 10))
        assertEquals(5_000L, retry.delayMillis(attempt = 50))
    }

    @Test
    fun `full jitter keeps every delay inside the exponential bound`() {
        val retry = RetryManager(
            RetryConfig(initialBackoffMs = 1_000, backoffMultiplier = 2.0, jitterFactor = 1.0),
            random = Random(seed = 42)
        )

        repeat(200) {
            val delay = retry.delayMillis(attempt = 3)
            assertTrue("delay $delay must not be negative", delay >= 0)
            assertTrue("delay $delay must not exceed the 4000ms bound", delay <= 4_000)
        }
    }

    @Test
    fun `jitter actually varies the delay`() {
        val retry = RetryManager(
            RetryConfig(initialBackoffMs = 1_000, jitterFactor = 1.0),
            random = Random(seed = 7)
        )

        val delays = (1..50).map { retry.delayMillis(attempt = 4) }.toSet()

        // Without variation, a backend outage would produce a synchronised retry storm across
        // every device that failed at the same moment.
        assertTrue("expected varied delays, got $delays", delays.size > 1)
    }

    @Test
    fun `a server supplied Retry-After wins over computed backoff`() {
        val retry = deterministic()

        assertEquals(30_000L, retry.delayMillis(attempt = 1, retryAfterMs = 30_000))
    }

    @Test
    fun `an absurd Retry-After is clamped so the queue cannot be parked forever`() {
        val retry = deterministic(RetryConfig(jitterFactor = 0.0, maxRetryAfterMs = 60_000))

        assertEquals(60_000L, retry.delayMillis(attempt = 1, retryAfterMs = 48 * 60 * 60 * 1000L))
    }

    @Test
    fun `Retry-After is ignored when the configuration disables it`() {
        val retry = deterministic(RetryConfig(jitterFactor = 0.0, respectRetryAfter = false))

        assertEquals(1_000L, retry.delayMillis(attempt = 1, retryAfterMs = 30_000))
    }

    @Test
    fun `attempts are bounded by maxAttempts`() {
        val retry = deterministic(RetryConfig(maxAttempts = 3, jitterFactor = 0.0))

        assertTrue(retry.canRetry(attempt = 1))
        assertTrue(retry.canRetry(attempt = 2))
        assertFalse(retry.canRetry(attempt = 3))
        assertFalse(retry.canRetry(attempt = 99))
    }
}

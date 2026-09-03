package com.ary.push.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpHeaderParsingTest {

    @Test
    fun `delta-seconds are converted to milliseconds`() {
        assertEquals(120_000L, HttpHeaderParsing.parseRetryAfterMillis("120"))
        assertEquals(0L, HttpHeaderParsing.parseRetryAfterMillis("0"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(5_000L, HttpHeaderParsing.parseRetryAfterMillis("  5 "))
    }

    @Test
    fun `an HTTP date is converted to a delay from now`() {
        val now = 1_700_000_000_000L
        val header = HttpHeaderParsing.formatHttpDate(now + 90_000)

        val parsed = HttpHeaderParsing.parseRetryAfterMillis(header, nowMs = now)

        // HTTP dates have second precision, so the result is within a second of 90s.
        assertTrue("expected roughly 90s, got $parsed", parsed != null && parsed in 89_000..90_000)
    }

    @Test
    fun `an HTTP date in the past means retry immediately`() {
        val now = 1_700_000_000_000L
        val header = HttpHeaderParsing.formatHttpDate(now - 60_000)

        assertEquals(0L, HttpHeaderParsing.parseRetryAfterMillis(header, nowMs = now))
    }

    @Test
    fun `absent malformed and negative values are ignored`() {
        assertNull(HttpHeaderParsing.parseRetryAfterMillis(null))
        assertNull(HttpHeaderParsing.parseRetryAfterMillis(""))
        assertNull(HttpHeaderParsing.parseRetryAfterMillis("   "))
        assertNull(HttpHeaderParsing.parseRetryAfterMillis("soon"))
        assertNull(HttpHeaderParsing.parseRetryAfterMillis("-30"))
    }

    @Test
    fun `an implausibly distant value is clamped to a day`() {
        val oneDay = 24L * 60 * 60 * 1000

        assertEquals(oneDay, HttpHeaderParsing.parseRetryAfterMillis("9999999"))
    }
}

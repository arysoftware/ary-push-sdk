package com.ary.push.internal.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch intent is the only trace of a tap on a notification the system rendered, so what
 * counts as one -- and what is an ordinary cold start -- is part of the contract.
 */
class LaunchIntentParserTest {

    private val transportKeys = mapOf(
        "google.message_id" to "0:1700000000%abc",
        "google.sent_time" to "1700000000000",
        "google.delivered_priority" to "high",
        "gcm.n.e" to "1",
        "from" to "123456789",
        "collapse_key" to "com.example.app"
    )

    @Test
    fun `an ordinary launch is not a notification tap`() {
        assertNull(LaunchIntentParser.parse(emptyMap()))
        assertNull(LaunchIntentParser.parse(mapOf("some_extra" to "value")))
        assertNull(LaunchIntentParser.parse(mapOf("url" to "https://ary.com/a")))
    }

    @Test
    fun `a blank message id is not a notification tap either`() {
        assertNull(LaunchIntentParser.parse(mapOf("google.message_id" to "   ")))
    }

    @Test
    fun `the launch URL survives the trip through the intent`() {
        val parsed = LaunchIntentParser.parse(
            transportKeys + mapOf("url" to "https://ary.com/offers/123")
        )

        assertEquals("https://ary.com/offers/123", parsed?.launchUrl)
    }

    @Test
    fun `every link key is still honoured, in the documented order`() {
        fun urlFor(vararg pairs: Pair<String, String>) =
            LaunchIntentParser.parse(transportKeys + pairs.toMap())?.launchUrl

        assertEquals("myapp://order/42", urlFor("deep_link" to "myapp://order/42"))
        assertEquals("https://ary.com/c", urlFor("link" to "https://ary.com/c"))
        assertEquals("https://ary.com/d", urlFor("launch_url" to "https://ary.com/d"))
        assertEquals(
            "https://ary.com/a",
            urlFor("url" to "https://ary.com/a", "launch_url" to "https://ary.com/d")
        )
    }

    @Test
    fun `transport extras are kept out of the data payload`() {
        val parsed = LaunchIntentParser.parse(transportKeys + mapOf("orderId" to "12345"))

        assertEquals(mapOf("orderId" to "12345"), parsed?.data)
    }

    @Test
    fun `identity matches the messaging path, so one tap is never handled twice`() {
        // A sender-supplied id wins, exactly as it does for a message that arrives through the
        // messaging service. Both paths therefore produce the same id for one logical message.
        assertEquals(
            "offer-123",
            LaunchIntentParser.parse(transportKeys + mapOf("notification_id" to "offer-123"))?.id
        )
        assertEquals(
            "offer-123",
            LaunchIntentParser.parse(transportKeys + mapOf("id" to "offer-123"))?.id
        )
        // With neither, the transport's own id is the fallback.
        assertEquals("0:1700000000%abc", LaunchIntentParser.parse(transportKeys)?.id)
    }

    @Test
    fun `the system-rendered title and body are recovered when present`() {
        val parsed = LaunchIntentParser.parse(
            transportKeys + mapOf(
                "gcm.notification.title" to "New offer",
                "gcm.notification.body" to "Tap to view"
            )
        )

        assertEquals("New offer", parsed?.title)
        assertEquals("Tap to view", parsed?.body)
        assertEquals(emptyMap<String, String>(), parsed?.data)
    }

    @Test
    fun `a data-only title and body are used when the transport supplied none`() {
        val parsed = LaunchIntentParser.parse(
            transportKeys + mapOf("title" to "Data title", "body" to "Data body")
        )

        assertEquals("Data title", parsed?.title)
        assertEquals("Data body", parsed?.body)
    }

    @Test
    fun `a tap is never reported as a foreground arrival`() {
        assertEquals(false, LaunchIntentParser.parse(transportKeys)?.wasForeground)
    }
}

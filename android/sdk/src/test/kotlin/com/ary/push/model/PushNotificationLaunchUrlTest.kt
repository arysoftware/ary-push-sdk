package com.ary.push.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tap handler opens whatever this returns, so the precedence between the three conventional
 * keys is part of the contract rather than an implementation detail.
 */
class PushNotificationLaunchUrlTest {

    private fun notification(data: Map<String, String>) = PushNotification(id = "n1", data = data)

    @Test
    fun `url, deep_link and link are all read`() {
        assertEquals("https://ary.com/a", notification(mapOf("url" to "https://ary.com/a")).launchUrl)
        assertEquals("myapp://order/42", notification(mapOf("deep_link" to "myapp://order/42")).launchUrl)
        assertEquals("https://ary.com/c", notification(mapOf("link" to "https://ary.com/c")).launchUrl)
    }

    @Test
    fun `url wins over deep_link, which wins over link`() {
        assertEquals(
            "https://ary.com/a",
            notification(
                mapOf(
                    "url" to "https://ary.com/a",
                    "deep_link" to "myapp://b",
                    "link" to "https://ary.com/c"
                )
            ).launchUrl
        )
        assertEquals(
            "myapp://b",
            notification(mapOf("deep_link" to "myapp://b", "link" to "https://ary.com/c")).launchUrl
        )
    }

    @Test
    fun `a blank value is skipped, not opened`() {
        assertEquals(
            "https://ary.com/c",
            notification(mapOf("url" to "   ", "link" to "https://ary.com/c")).launchUrl
        )
    }

    @Test
    fun `a payload with no link at all has none`() {
        assertNull(notification(mapOf("action" to "open_order")).launchUrl)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "https://ary.com/a",
            notification(mapOf("url" to "  https://ary.com/a  ")).launchUrl
        )
    }
}

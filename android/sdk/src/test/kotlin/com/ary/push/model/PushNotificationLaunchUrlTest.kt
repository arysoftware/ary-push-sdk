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
    fun `all four conventional keys are read`() {
        assertEquals("https://ary.com/a", notification(mapOf("url" to "https://ary.com/a")).launchUrl)
        assertEquals("myapp://order/42", notification(mapOf("deep_link" to "myapp://order/42")).launchUrl)
        assertEquals("https://ary.com/c", notification(mapOf("link" to "https://ary.com/c")).launchUrl)
        assertEquals("https://ary.com/d", notification(mapOf("launch_url" to "https://ary.com/d")).launchUrl)
    }

    @Test
    fun `priority is url, then deep_link, then link, then launch_url`() {
        val all = mapOf(
            "url" to "https://ary.com/a",
            "deep_link" to "myapp://b",
            "link" to "https://ary.com/c",
            "launch_url" to "https://ary.com/d"
        )

        assertEquals("https://ary.com/a", notification(all).launchUrl)
        assertEquals("myapp://b", notification(all - "url").launchUrl)
        assertEquals("https://ary.com/c", notification(all - "url" - "deep_link").launchUrl)
        assertEquals("https://ary.com/d", notification(all - "url" - "deep_link" - "link").launchUrl)
    }

    @Test
    fun `a blank value is skipped, not opened`() {
        assertEquals(
            "https://ary.com/c",
            notification(mapOf("url" to "   ", "link" to "https://ary.com/c")).launchUrl
        )
        assertEquals(
            "https://ary.com/d",
            notification(mapOf("link" to "", "launch_url" to "https://ary.com/d")).launchUrl
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

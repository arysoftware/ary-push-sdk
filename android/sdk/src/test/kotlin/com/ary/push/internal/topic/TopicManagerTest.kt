package com.ary.push.internal.topic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicManagerTest {

    @Test
    fun `valid topic names are accepted`() {
        assertEquals("sports", TopicManager.normalize("sports"))
        assertEquals("news_en", TopicManager.normalize("news_en"))
        assertEquals("promo-2026", TopicManager.normalize("promo-2026"))
        assertEquals("a.b~c%d", TopicManager.normalize("a.b~c%d"))
    }

    @Test
    fun `the optional topics prefix is stripped`() {
        assertEquals("sports", TopicManager.normalize("/topics/sports"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("sports", TopicManager.normalize("  sports  "))
    }

    @Test
    fun `names FCM would reject fail locally instead of silently on the server`() {
        assertNull(TopicManager.normalize(""))
        assertNull(TopicManager.normalize("   "))
        assertNull(TopicManager.normalize("has space"))
        assertNull(TopicManager.normalize("has/slash"))
        assertNull(TopicManager.normalize("emoji-nope-\u2728"))
        assertNull(TopicManager.normalize("x".repeat(901)))
    }

    @Test
    fun `the maximum supported length is accepted`() {
        val longest = "x".repeat(900)

        assertEquals(longest, TopicManager.normalize(longest))
    }
}

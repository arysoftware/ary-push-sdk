package com.ary.push.internal.notification

import com.ary.push.internal.storage.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeduplicationManagerTest {

    private lateinit var storage: StorageManager

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
    }

    @Test
    fun `a message is new exactly once`() {
        val dedup = DeduplicationManager(storage, maxSize = 10)

        assertTrue(dedup.markSeenIfNew("m1"))
        assertFalse(dedup.markSeenIfNew("m1"))
        assertFalse(dedup.markSeenIfNew("m1"))
    }

    @Test
    fun `distinct messages are all new`() {
        val dedup = DeduplicationManager(storage, maxSize = 10)

        assertTrue(dedup.markSeenIfNew("m1"))
        assertTrue(dedup.markSeenIfNew("m2"))
        assertEquals(2, dedup.size())
    }

    @Test
    fun `the cache survives a process restart, which is when duplicates actually arrive`() {
        DeduplicationManager(storage, maxSize = 10).markSeenIfNew("m1")

        // A background message often kills the process right after handling; the redelivery
        // that follows must still be recognised.
        assertFalse(DeduplicationManager(storage, maxSize = 10).markSeenIfNew("m1"))
    }

    @Test
    fun `the cache is bounded and evicts the oldest entries first`() {
        val dedup = DeduplicationManager(storage, maxSize = 5)

        repeat(20) { index -> dedup.markSeenIfNew("m$index") }

        assertEquals(5, dedup.size())
        assertTrue("the most recent must still be remembered", dedup.hasSeen("m19"))
        assertFalse("the oldest must have been evicted", dedup.hasSeen("m0"))
    }

    @Test
    fun `a blank id is treated as new rather than collapsing unrelated messages`() {
        val dedup = DeduplicationManager(storage, maxSize = 10)

        assertTrue(dedup.markSeenIfNew(""))
        assertTrue(dedup.markSeenIfNew(""))
        assertEquals(0, dedup.size())
    }

    @Test
    fun `receipt and open are tracked independently`() {
        val dedup = DeduplicationManager(storage, maxSize = 10)

        assertTrue(dedup.markSeenIfNew("m1"))
        // Opening a message that was already received must not be swallowed as a duplicate.
        assertTrue(dedup.markSeenIfNew("open:m1:"))
        assertTrue(dedup.markSeenIfNew("open:m1:accept"))
        assertFalse(dedup.markSeenIfNew("open:m1:accept"))
    }

    @Test
    fun `clearing forgets everything`() {
        val dedup = DeduplicationManager(storage, maxSize = 10)
        dedup.markSeenIfNew("m1")

        dedup.clear()

        assertTrue(dedup.markSeenIfNew("m1"))
    }
}

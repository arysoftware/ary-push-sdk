package com.ary.push.internal.tag

import com.ary.push.internal.storage.StorageManager
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TagManagerTest {

    private lateinit var storage: StorageManager
    private val synced = mutableListOf<Map<String, String>>()
    private val removed = mutableListOf<Pair<Set<String>, Boolean>>()

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
        synced.clear()
        removed.clear()
    }

    /** Debounce disabled: every write flushes immediately, which keeps assertions direct. */
    private fun immediateManager(scope: kotlinx.coroutines.CoroutineScope) = TagManager(
        storage = storage,
        scope = scope,
        debounceMs = 0,
        onTagsChanged = { synced.add(it) },
        onTagsRemoved = { keys, all -> removed.add(keys to all) }
    )

    @Test
    fun `tags are written locally and reported for synchronisation`() = runTest {
        val manager = immediateManager(backgroundScope)

        manager.addTag("subscription", "premium")

        assertEquals(mapOf("subscription" to "premium"), manager.tags)
        assertEquals(listOf(mapOf("subscription" to "premium")), synced)
    }

    @Test
    fun `local state is correct immediately, before anything reaches a server`() = runTest {
        val manager = immediateManager(backgroundScope)

        manager.addTags(mapOf("language" to "en", "country" to "PK"))

        // An offline device must still read back what it just wrote.
        assertEquals(mapOf("language" to "en", "country" to "PK"), manager.tags)
    }

    @Test
    fun `writing an unchanged value does not produce a request`() = runTest {
        val manager = immediateManager(backgroundScope)

        manager.addTag("subscription", "premium")
        manager.addTag("subscription", "premium")

        assertEquals(1, synced.size)
    }

    @Test
    fun `updating a value replaces it`() = runTest {
        val manager = immediateManager(backgroundScope)

        manager.addTag("subscription", "free")
        manager.addTag("subscription", "premium")

        assertEquals(mapOf("subscription" to "premium"), manager.tags)
        assertEquals(2, synced.size)
    }

    @Test
    fun `a burst of writes inside the debounce window becomes one request`() = runTest {
        val manager = TagManager(
            storage = storage,
            scope = backgroundScope,
            debounceMs = 750,
            onTagsChanged = { synced.add(it) },
            onTagsRemoved = { keys, all -> removed.add(keys to all) }
        )

        manager.addTag("a", "1")
        manager.addTag("b", "2")
        manager.addTag("c", "3")
        runCurrent()
        assertTrue("nothing should be sent during the window", synced.isEmpty())

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(mapOf("a" to "1", "b" to "2", "c" to "3")), synced)
    }

    @Test
    fun `flushNow sends pending work without waiting for the window`() = runTest {
        val manager = TagManager(
            storage = storage,
            scope = backgroundScope,
            debounceMs = 60_000,
            onTagsChanged = { synced.add(it) },
            onTagsRemoved = { keys, all -> removed.add(keys to all) }
        )

        manager.addTag("a", "1")
        manager.flushNow()

        assertEquals(listOf(mapOf("a" to "1")), synced)
    }

    @Test
    fun `removing a tag deletes it locally and reports the removal`() = runTest {
        val manager = immediateManager(backgroundScope)
        manager.addTags(mapOf("a" to "1", "b" to "2"))

        manager.removeTag("a")

        assertEquals(mapOf("b" to "2"), manager.tags)
        assertEquals(listOf(setOf("a") to false), removed)
    }

    @Test
    fun `removing a tag that is not set does nothing`() = runTest {
        val manager = immediateManager(backgroundScope)

        manager.removeTag("missing")

        assertTrue(removed.isEmpty())
    }

    @Test
    fun `removeAllTags clears local state and reports it as a bulk removal`() = runTest {
        val manager = immediateManager(backgroundScope)
        manager.addTags(mapOf("a" to "1", "b" to "2"))

        manager.removeAllTags()

        assertEquals(emptyMap<String, String>(), manager.tags)
        assertEquals(listOf(emptySet<String>() to true), removed)
    }

    @Test
    fun `tags survive a restart`() = runTest {
        immediateManager(backgroundScope).addTag("a", "1")

        assertEquals(mapOf("a" to "1"), immediateManager(backgroundScope).tags)
    }

    @Test
    fun `blank keys are ignored`() = runTest {
        val manager = immediateManager(backgroundScope)

        manager.addTags(mapOf("" to "1", "  " to "2", "ok" to "3"))

        assertEquals(mapOf("ok" to "3"), manager.tags)
    }
}

package com.ary.push.internal.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StorageManagerTest {

    private lateinit var context: Context
    private lateinit var storage: StorageManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = StorageManager(context)
        storage.clearAll()
    }

    @Test
    fun `values round trip`() {
        storage.putString(StorageManager.KEY_USER_ID, "USER_1")
        storage.putBoolean(StorageManager.KEY_TOKEN_SYNCED, true)
        storage.putLong("ary_push.test_long", 42L)

        assertEquals("USER_1", storage.getString(StorageManager.KEY_USER_ID))
        assertTrue(storage.getBoolean(StorageManager.KEY_TOKEN_SYNCED, false))
        assertEquals(42L, storage.getLong("ary_push.test_long", 0L))
    }

    @Test
    fun `writing null removes the entry`() {
        storage.putString(StorageManager.KEY_USER_ID, "USER_1")
        storage.putString(StorageManager.KEY_USER_ID, null)

        assertNull(storage.getString(StorageManager.KEY_USER_ID))
    }

    @Test
    fun `string maps round trip and an empty map clears the entry`() {
        storage.putStringMap(StorageManager.KEY_TAGS, mapOf("a" to "1", "b" to "2"))
        assertEquals(mapOf("a" to "1", "b" to "2"), storage.getStringMap(StorageManager.KEY_TAGS))

        storage.putStringMap(StorageManager.KEY_TAGS, emptyMap())
        assertEquals(emptyMap<String, String>(), storage.getStringMap(StorageManager.KEY_TAGS))
        assertNull(storage.getString(StorageManager.KEY_TAGS))
    }

    @Test
    fun `a stored set is not corrupted by mutating the caller's collection afterwards`() {
        // SharedPreferences does not copy the set it is given, so the manager must.
        val topics = mutableSetOf("sports")
        storage.putStringSet(StorageManager.KEY_TOPICS, topics)
        topics.add("news")

        assertEquals(setOf("sports"), storage.getStringSet(StorageManager.KEY_TOPICS))
    }

    @Test
    fun `unreadable stored JSON degrades to an empty map instead of throwing`() {
        storage.putString(StorageManager.KEY_TAGS, "{not json")

        assertEquals(emptyMap<String, String>(), storage.getStringMap(StorageManager.KEY_TAGS))
    }

    @Test
    fun `every key is namespaced so the SDK can never collide with the host application`() {
        val keys = listOf(
            StorageManager.KEY_INSTALLATION_ID,
            StorageManager.KEY_PUSH_TOKEN,
            StorageManager.KEY_USER_ID,
            StorageManager.KEY_TAGS,
            StorageManager.KEY_TOPICS,
            StorageManager.KEY_PENDING_OPEN,
            StorageManager.KEY_PENDING_OPERATIONS,
            StorageManager.KEY_SEEN_MESSAGE_IDS
        )

        keys.forEach { key ->
            assertTrue("$key is not namespaced", key.startsWith("ary_push."))
        }
    }

    @Test
    fun `SDK state lives in its own preferences file`() {
        storage.putString(StorageManager.KEY_USER_ID, "USER_1")

        val hostPrefs = context.getSharedPreferences("host_app_prefs", Context.MODE_PRIVATE)
        assertNull(hostPrefs.getString(StorageManager.KEY_USER_ID, null))

        val sdkPrefs = context.getSharedPreferences(StorageManager.FILE_NAME, Context.MODE_PRIVATE)
        assertEquals("USER_1", sdkPrefs.getString(StorageManager.KEY_USER_ID, null))
    }
}

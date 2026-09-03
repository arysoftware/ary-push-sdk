package com.ary.push.internal.user

import com.ary.push.internal.storage.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserManagerTest {

    private lateinit var storage: StorageManager
    private val changes = mutableListOf<String?>()

    private fun manager() = UserManager(storage) { changes.add(it) }

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
        changes.clear()
    }

    @Test
    fun `login stores the user locally and reports the change once`() {
        val manager = manager()

        manager.login("USER_1")

        assertEquals("USER_1", manager.userId)
        assertTrue(manager.isLoggedIn)
        assertEquals(listOf("USER_1"), changes)
    }

    @Test
    fun `logging in with the same user again does not re-synchronise`() {
        val manager = manager()

        manager.login("USER_1")
        manager.login("USER_1")

        assertEquals(1, changes.size)
    }

    @Test
    fun `switching users replaces the association rather than merging it`() {
        val manager = manager()

        manager.login("USER_1")
        manager.login("USER_2")

        assertEquals("USER_2", manager.userId)
        assertEquals(listOf("USER_1", "USER_2"), changes)
    }

    @Test
    fun `logout clears the user but keeps the installation and the token`() {
        storage.putString(StorageManager.KEY_INSTALLATION_ID, "install-1")
        storage.putString(StorageManager.KEY_PUSH_TOKEN, "token-1")
        val manager = manager()
        manager.login("USER_1")

        manager.logout()

        assertNull(manager.userId)
        assertFalse(manager.isLoggedIn)
        // Unregistering the device on logout would make win-back campaigns impossible.
        assertEquals("install-1", storage.getString(StorageManager.KEY_INSTALLATION_ID))
        assertEquals("token-1", storage.getString(StorageManager.KEY_PUSH_TOKEN))
        assertEquals(listOf("USER_1", null), changes)
    }

    @Test
    fun `logging out when nobody is logged in does nothing`() {
        manager().logout()

        assertTrue(changes.isEmpty())
    }

    @Test
    fun `the association survives a restart`() {
        manager().login("USER_1")

        assertEquals("USER_1", manager().userId)
    }

    @Test
    fun `whitespace is trimmed and a blank id is rejected`() {
        val manager = manager()

        manager.login("  USER_1  ")
        assertEquals("USER_1", manager.userId)

        val failure = runCatching { manager.login("   ") }
        assertTrue(failure.isFailure)
    }
}

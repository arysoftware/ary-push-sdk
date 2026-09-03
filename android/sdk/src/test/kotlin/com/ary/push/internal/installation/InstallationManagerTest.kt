package com.ary.push.internal.installation

import com.ary.push.internal.storage.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class InstallationManagerTest {

    private lateinit var storage: StorageManager

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
    }

    @Test
    fun `an id is created on first use and is a UUID`() {
        val manager = InstallationManager(storage)

        assertFalse(manager.hasInstallation())
        val id = manager.installationId

        assertTrue(manager.hasInstallation())
        // Parsing proves the format rather than asserting on an opaque string.
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun `the id is stable across reads and across restarts`() {
        val id = InstallationManager(storage).installationId

        assertEquals(id, InstallationManager(storage).installationId)
        assertEquals(id, InstallationManager(storage).installationId)
    }

    @Test
    fun `the id does not change when the push token changes`() {
        val manager = InstallationManager(storage)
        val id = manager.installationId

        storage.putString(StorageManager.KEY_PUSH_TOKEN, "token-1")
        storage.putString(StorageManager.KEY_PUSH_TOKEN, "token-2")

        assertEquals(id, manager.installationId)
    }

    @Test
    fun `the id does not change across login and logout`() {
        val manager = InstallationManager(storage)
        val id = manager.installationId

        storage.putString(StorageManager.KEY_USER_ID, "USER_1")
        storage.putString(StorageManager.KEY_USER_ID, null)

        assertEquals(id, manager.installationId)
    }

    @Test
    fun `concurrent first access still produces exactly one id`() {
        // A cold start can reach this from the host application, a background message and a
        // notification tap at once. Two ids would mean two installations on the backend.
        val manager = InstallationManager(storage)
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.submit {
                start.await()
                results.add(manager.installationId)
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(1, results.size)
    }

    @Test
    fun `reset issues a genuinely new identity`() {
        val manager = InstallationManager(storage)
        val first = manager.installationId

        manager.reset()

        assertNotEquals(first, manager.installationId)
    }
}

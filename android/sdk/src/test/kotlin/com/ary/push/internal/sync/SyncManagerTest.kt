package com.ary.push.internal.sync

import com.ary.push.RetryConfig
import com.ary.push.api.ApiResult
import com.ary.push.backend.FakePushBackend
import com.ary.push.internal.storage.StorageManager
import com.ary.push.model.Installation
import com.ary.push.model.PushProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SyncManagerTest {

    private lateinit var storage: StorageManager
    private lateinit var queue: OperationQueue
    private lateinit var backend: FakePushBackend

    private var online = true

    private val installation = Installation(
        id = "install-1",
        applicationId = "wallet_android",
        platform = "android",
        provider = PushProvider.FCM,
        pushToken = "token-1",
        userId = null,
        appVersion = "1.0",
        appBuild = "1",
        sdkVersion = "1.0.0",
        osVersion = "14",
        deviceModel = "Pixel",
        locale = "en-PK",
        timezone = "Asia/Karachi",
        notificationsEnabled = true
    )

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
        queue = OperationQueue(storage)
        backend = FakePushBackend()
        online = true
    }

    private fun sync(
        scope: CoroutineScope,
        retry: RetryConfig = RetryConfig(maxAttempts = 3, initialBackoffMs = 10, jitterFactor = 0.0)
    ) = SyncManager(
        scope = scope,
        queue = queue,
        storage = storage,
        isOnline = { online },
        retryManager = RetryManager(retry),
        backendProvider = { backend },
        installationProvider = { installation }
    )

    @Test
    fun `a queued operation reaches the backend and leaves the queue`() = runTest {
        val manager = sync(backgroundScope)

        manager.enqueueLogout()
        advanceUntilIdle()

        assertTrue(backend.calls.contains("logout"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `nothing is sent while offline and nothing is lost`() = runTest {
        online = false
        val manager = sync(backgroundScope)

        manager.enqueueIdentify("USER_1")
        advanceUntilIdle()

        assertTrue("no request may be attempted offline", backend.calls.isEmpty())
        assertEquals(1, queue.size())
    }

    @Test
    fun `work queued offline drains once the network returns`() = runTest {
        online = false
        val manager = sync(backgroundScope)
        manager.enqueueIdentify("USER_1")
        advanceUntilIdle()

        online = true
        manager.requestSync()
        advanceUntilIdle()

        assertTrue(backend.calls.contains("identify:USER_1"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `dependent operations register the installation first`() = runTest {
        val manager = sync(backgroundScope)

        manager.enqueueIdentify("USER_1")
        advanceUntilIdle()

        // Identifying a user against an installation the backend has never seen is meaningless.
        assertEquals(listOf("register", "identify:USER_1"), backend.calls)
    }

    @Test
    fun `an unchanged registration is not resent on every launch`() = runTest {
        val manager = sync(backgroundScope)

        repeat(3) {
            manager.registerInstallationIfChanged()
            advanceUntilIdle()
        }

        assertEquals(1, backend.countOf("register"))
    }

    @Test
    fun `forcing re-registration overrides the unchanged check`() = runTest {
        val manager = sync(backgroundScope)
        manager.registerInstallationIfChanged()
        advanceUntilIdle()

        manager.registerInstallationIfChanged(force = true)
        advanceUntilIdle()

        assertEquals(2, backend.countOf("register"))
    }

    @Test
    fun `a transient failure is retried and then succeeds`() = runTest {
        backend.script(
            ApiResult.Error(statusCode = 503),
            ApiResult.Success(Unit, statusCode = 200)
        )
        val manager = sync(backgroundScope)

        manager.enqueue(PendingOperation(type = OperationType.REGISTER_INSTALLATION))
        advanceUntilIdle()

        assertEquals(2, backend.countOf("register"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a permanent failure is dropped instead of retried forever`() = runTest {
        backend.default = ApiResult.Error(statusCode = 422, code = "invalid_installation")
        val manager = sync(backgroundScope)

        manager.enqueue(PendingOperation(type = OperationType.REGISTER_INSTALLATION))
        advanceUntilIdle()

        // One attempt only: a 422 will never succeed, and retrying it would wedge the queue.
        assertEquals(1, backend.countOf("register"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `an operation that exhausts its attempts is dropped`() = runTest {
        backend.default = ApiResult.NetworkError(IOException("no route to host"))
        val manager = sync(
            backgroundScope,
            RetryConfig(maxAttempts = 3, initialBackoffMs = 10, jitterFactor = 0.0)
        )

        manager.enqueue(PendingOperation(type = OperationType.REGISTER_INSTALLATION))
        advanceUntilIdle()

        assertEquals(3, backend.countOf("register"))
        assertTrue("the queue must not grow without bound", queue.isEmpty())
    }

    @Test
    fun `a backend that throws is treated as a transport failure, not a crash`() = runTest {
        backend.throwOnCall = IllegalStateException("host-supplied backend is broken")
        val manager = sync(
            backgroundScope,
            RetryConfig(maxAttempts = 2, initialBackoffMs = 10, jitterFactor = 0.0)
        )

        manager.enqueue(PendingOperation(type = OperationType.REGISTER_INSTALLATION))
        advanceUntilIdle()

        assertEquals(2, backend.countOf("register"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a burst of tag writes becomes a single request`() = runTest {
        val manager = sync(backgroundScope)

        manager.enqueueTagUpdate(mapOf("a" to "1"))
        manager.enqueueTagUpdate(mapOf("b" to "2"))
        manager.enqueueTagUpdate(mapOf("c" to "3"))
        advanceUntilIdle()

        val tagCalls = backend.calls.filter { it.startsWith("tags:") }
        assertEquals(1, tagCalls.size)
        assertTrue(tagCalls.single().contains("a=1"))
        assertTrue(tagCalls.single().contains("b=2"))
        assertTrue(tagCalls.single().contains("c=3"))
    }

    @Test
    fun `events are queued and delivered as a batch`() = runTest {
        val manager = sync(backgroundScope)

        manager.enqueueEvent(com.ary.push.model.PushEvent("notification_opened"))
        advanceUntilIdle()

        assertTrue(backend.calls.any { it.startsWith("events:") })
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `reset clears deferred work and the registration marker`() = runTest {
        val manager = sync(backgroundScope)
        manager.registerInstallationIfChanged()
        advanceUntilIdle()

        manager.reset()

        assertTrue(queue.isEmpty())
        assertNull(storage.getString(StorageManager.KEY_REGISTRATION_HASH))
    }
}

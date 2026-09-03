package com.ary.push.internal.sync

import com.ary.push.internal.PushJson
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
class OperationQueueTest {

    private lateinit var storage: StorageManager
    private lateinit var queue: OperationQueue

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
        queue = OperationQueue(storage)
    }

    private fun tagOperation(vararg tags: Pair<String, String>) = PendingOperation(
        type = OperationType.UPDATE_TAGS,
        payload = mapOf(
            PendingOperation.KEY_TAGS to PushJson.toJsonObject(tags.toMap()).toString()
        )
    )

    @Test
    fun `the queue starts empty`() {
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.size())
    }

    @Test
    fun `queued work survives a new queue instance, as it must survive a process restart`() {
        queue.enqueue(PendingOperation(type = OperationType.LOGOUT_USER))

        val reopened = OperationQueue(storage)

        assertEquals(1, reopened.size())
        assertEquals(OperationType.LOGOUT_USER, reopened.snapshot().single().type)
    }

    @Test
    fun `a newer token replaces an older unsent one`() {
        queue.enqueue(
            PendingOperation(
                type = OperationType.UPDATE_TOKEN,
                payload = mapOf(PendingOperation.KEY_TOKEN to "old")
            )
        )
        queue.enqueue(
            PendingOperation(
                type = OperationType.UPDATE_TOKEN,
                payload = mapOf(PendingOperation.KEY_TOKEN to "new")
            )
        )

        // Sending the stale token first would briefly point the backend at a dead route.
        val operations = queue.snapshot()
        assertEquals(1, operations.size)
        assertEquals("new", operations.single().payload[PendingOperation.KEY_TOKEN])
    }

    @Test
    fun `a burst of tag writes collapses into one merged operation`() {
        queue.enqueue(tagOperation("subscription" to "premium"))
        queue.enqueue(tagOperation("language" to "en"))
        queue.enqueue(tagOperation("subscription" to "gold"))

        val operations = queue.snapshot()
        assertEquals(1, operations.size)

        val merged = PushJson.flattenToStringMap(
            PushJson.parseObject(operations.single().payload[PendingOperation.KEY_TAGS])
        )
        assertEquals(mapOf("subscription" to "gold", "language" to "en"), merged)
    }

    @Test
    fun `logout supersedes an unsent identify and vice versa`() {
        queue.enqueue(
            PendingOperation(
                type = OperationType.IDENTIFY_USER,
                payload = mapOf(PendingOperation.KEY_USER_ID to "USER_1")
            )
        )
        queue.enqueue(PendingOperation(type = OperationType.LOGOUT_USER))

        assertEquals(listOf(OperationType.LOGOUT_USER), queue.snapshot().map { it.type })

        queue.enqueue(
            PendingOperation(
                type = OperationType.IDENTIFY_USER,
                payload = mapOf(PendingOperation.KEY_USER_ID to "USER_2")
            )
        )

        assertEquals(listOf(OperationType.IDENTIFY_USER), queue.snapshot().map { it.type })
    }

    @Test
    fun `snapshots come back in dependency order regardless of insertion order`() {
        queue.enqueue(tagOperation("a" to "1"))
        queue.enqueue(
            PendingOperation(
                type = OperationType.IDENTIFY_USER,
                payload = mapOf(PendingOperation.KEY_USER_ID to "USER_1")
            )
        )
        queue.enqueue(PendingOperation(type = OperationType.REGISTER_INSTALLATION))

        assertEquals(
            listOf(
                OperationType.REGISTER_INSTALLATION,
                OperationType.IDENTIFY_USER,
                OperationType.UPDATE_TAGS
            ),
            queue.snapshot().map { it.type }
        )
    }

    @Test
    fun `attempts are recorded durably so backoff survives a restart`() {
        queue.enqueue(PendingOperation(type = OperationType.LOGOUT_USER))
        val id = queue.snapshot().single().id

        queue.recordAttempt(id)
        queue.recordAttempt(id)

        assertEquals(2, OperationQueue(storage).snapshot().single().attempts)
    }

    @Test
    fun `removing the last operation empties the queue`() {
        queue.enqueue(PendingOperation(type = OperationType.LOGOUT_USER))
        queue.remove(queue.snapshot().single().id)

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `the queue is bounded so a long offline period cannot grow it without limit`() {
        repeat(300) { index ->
            queue.enqueue(
                PendingOperation(
                    type = OperationType.TRACK_EVENTS,
                    payload = mapOf(PendingOperation.KEY_EVENTS to "[]"),
                    createdAt = index.toLong()
                )
            )
        }

        assertTrue("queue grew to ${queue.size()}", queue.size() <= 100)
    }

    @Test
    fun `a corrupted queue is reset rather than blocking every future write`() {
        storage.putString(StorageManager.KEY_PENDING_OPERATIONS, "[[[not json", durable = true)

        assertTrue(queue.snapshot().isEmpty())

        queue.enqueue(PendingOperation(type = OperationType.LOGOUT_USER))
        assertFalse(queue.isEmpty())
    }
}

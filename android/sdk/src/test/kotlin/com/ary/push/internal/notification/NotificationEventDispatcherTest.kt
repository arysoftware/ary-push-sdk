package com.ary.push.internal.notification

import com.ary.push.internal.storage.StorageManager
import com.ary.push.model.PushNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationEventDispatcherTest {

    private lateinit var storage: StorageManager
    private lateinit var dispatcher: NotificationEventDispatcher

    private fun notification(id: String = "n1", action: String? = null) =
        PushNotification(id = id, title = "T", body = "B", actionId = action)

    @Before
    fun setUp() {
        storage = StorageManager(RuntimeEnvironment.getApplication())
        storage.clearAll()
        dispatcher = NotificationEventDispatcher(storage)
    }

    @Test
    fun `received events reach every registered listener`() {
        val seenA = mutableListOf<PushNotification>()
        val seenB = mutableListOf<PushNotification>()
        dispatcher.addReceivedListener { seenA.add(it) }
        dispatcher.addReceivedListener { seenB.add(it) }

        dispatcher.dispatchReceived(notification())

        assertEquals(1, seenA.size)
        assertEquals(1, seenB.size)
    }

    @Test
    fun `a listener that throws does not stop the others`() {
        val survived = mutableListOf<PushNotification>()
        dispatcher.addReceivedListener { error("host listener blew up") }
        dispatcher.addReceivedListener { survived.add(it) }

        dispatcher.dispatchReceived(notification())

        assertEquals(1, survived.size)
    }

    @Test
    fun `a removed listener stops receiving events`() {
        val seen = mutableListOf<PushNotification>()
        val listener: (PushNotification) -> Unit = { seen.add(it) }
        dispatcher.addReceivedListener(listener)
        dispatcher.removeReceivedListener(listener)

        dispatcher.dispatchReceived(notification())

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an open with nobody listening is persisted, not dropped`() {
        dispatcher.dispatchOpened(notification(id = "order-42"))

        // This is the terminated-application case: the tap happens before any host code runs.
        assertNotNull(dispatcher.peekPendingOpen())
        assertEquals("order-42", dispatcher.peekPendingOpen()?.id)
    }

    @Test
    fun `a persisted open is replayed to the first listener that attaches`() {
        dispatcher.dispatchOpened(notification(id = "order-42", action = "track"))

        val seen = mutableListOf<PushNotification>()
        dispatcher.addOpenedListener { seen.add(it) }

        assertEquals(1, seen.size)
        assertEquals("order-42", seen.single().id)
        assertEquals("track", seen.single().actionId)
    }

    @Test
    fun `a replayed open is delivered exactly once`() {
        dispatcher.dispatchOpened(notification(id = "order-42"))

        val first = mutableListOf<PushNotification>()
        val second = mutableListOf<PushNotification>()
        dispatcher.addOpenedListener { first.add(it) }
        dispatcher.addOpenedListener { second.add(it) }

        assertEquals(1, first.size)
        assertTrue("the second listener must not see the same tap again", second.isEmpty())
        assertNull(dispatcher.peekPendingOpen())
    }

    @Test
    fun `a pending open survives a process restart`() {
        dispatcher.dispatchOpened(notification(id = "order-42"))

        val afterRestart = NotificationEventDispatcher(storage)

        assertEquals("order-42", afterRestart.consumePendingOpen()?.id)
    }

    @Test
    fun `an open with a listener attached is delivered live and not persisted`() {
        val seen = mutableListOf<PushNotification>()
        dispatcher.addOpenedListener { seen.add(it) }

        dispatcher.dispatchOpened(notification(id = "order-42"))

        assertEquals(1, seen.size)
        assertNull(dispatcher.peekPendingOpen())
    }

    @Test
    fun `received events are not persisted`() {
        // A message nobody was listening for is stale by the time the app starts, and the
        // notification is already in the shade.
        dispatcher.dispatchReceived(notification())

        assertNull(dispatcher.peekPendingOpen())
    }

    @Test
    fun `consuming the pending open clears it`() {
        dispatcher.dispatchOpened(notification(id = "order-42"))

        assertNotNull(dispatcher.consumePendingOpen())
        assertNull(dispatcher.consumePendingOpen())
    }

    @Test
    fun `clearing listeners detaches everything`() {
        val seen = mutableListOf<PushNotification>()
        dispatcher.addReceivedListener { seen.add(it) }
        dispatcher.addOpenedListener { seen.add(it) }

        dispatcher.clearListeners()
        dispatcher.dispatchReceived(notification())

        assertTrue(seen.isEmpty())
    }
}

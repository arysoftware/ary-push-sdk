package com.ary.push.internal.notification

import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationParserTest {

    private fun message(
        messageId: String? = null,
        data: Map<String, String> = emptyMap()
    ): RemoteMessage = RemoteMessage.Builder("push-api@fcm.googleapis.com")
        .apply {
            messageId?.let { setMessageId(it) }
            data.forEach { (key, value) -> addData(key, value) }
        }
        .build()

    @Test
    fun `a data-only message maps title and body from data keys`() {
        val parsed = NotificationParser.parse(
            message(
                messageId = "m1",
                data = mapOf("title" to "Order shipped", "body" to "On its way")
            ),
            wasForeground = false
        )

        assertEquals("Order shipped", parsed.title)
        assertEquals("On its way", parsed.body)
        assertEquals("m1", parsed.id)
    }

    @Test
    fun `the custom payload is delivered verbatim`() {
        val parsed = NotificationParser.parse(
            message(messageId = "m1", data = mapOf("action" to "open_order", "orderId" to "12345")),
            wasForeground = false
        )

        assertEquals(mapOf("action" to "open_order", "orderId" to "12345"), parsed.data)
        assertEquals("open_order", parsed.action)
        assertEquals("12345", parsed.data["orderId"])
    }

    @Test
    fun `a sender supplied notification id wins over the FCM message id`() {
        // A sender-supplied id groups resends of one logical message; the FCM id is unique per
        // delivery attempt and would defeat deduplication.
        val parsed = NotificationParser.parse(
            message(messageId = "fcm-1", data = mapOf("notification_id" to "order-42")),
            wasForeground = false
        )

        assertEquals("order-42", parsed.id)
    }

    @Test
    fun `messages with no id at all still get a stable identity from their content`() {
        val first = NotificationParser.parse(
            message(data = mapOf("title" to "Hi", "body" to "There")),
            wasForeground = false
        )
        val second = NotificationParser.parse(
            message(data = mapOf("title" to "Hi", "body" to "There")),
            wasForeground = false
        )

        assertTrue(first.id.startsWith("hash-"))
        assertEquals("identical payloads must deduplicate", first.id, second.id)
    }

    @Test
    fun `different content produces different identities`() {
        val first = NotificationParser.parse(message(data = mapOf("body" to "A")), false)
        val second = NotificationParser.parse(message(data = mapOf("body" to "B")), false)

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `the image url is read from either conventional data key`() {
        assertEquals(
            "https://cdn.ary.com/a.png",
            NotificationParser.parse(
                message(data = mapOf("image_url" to "https://cdn.ary.com/a.png")),
                false
            ).imageUrl
        )
        assertEquals(
            "https://cdn.ary.com/b.png",
            NotificationParser.parse(
                message(data = mapOf("image" to "https://cdn.ary.com/b.png")),
                false
            ).imageUrl
        )
    }

    @Test
    fun `foreground state is recorded on the notification`() {
        assertTrue(NotificationParser.parse(message(messageId = "m"), true).wasForeground)
        assertTrue(!NotificationParser.parse(message(messageId = "m"), false).wasForeground)
    }

    @Test
    fun `a received notification never carries an action id`() {
        // actionId only means something on an open event.
        assertNull(NotificationParser.parse(message(messageId = "m"), false).actionId)
    }

    @Test
    fun `the system notification id is stable for a given message`() {
        val parsed = NotificationParser.parse(message(messageId = "m1"), false)

        assertEquals(
            NotificationParser.systemNotificationId(parsed),
            NotificationParser.systemNotificationId(parsed)
        )
    }

    @Test
    fun `messages sharing a collapse key share a system notification slot`() {
        val first = NotificationParser.parse(message(messageId = "m1"), false)
            .copy(collapseKey = "orders")
        val second = NotificationParser.parse(message(messageId = "m2"), false)
            .copy(collapseKey = "orders")

        // A collapse key exists precisely so the newer message replaces the older in the shade.
        assertEquals(
            NotificationParser.systemNotificationId(first),
            NotificationParser.systemNotificationId(second)
        )
    }
}

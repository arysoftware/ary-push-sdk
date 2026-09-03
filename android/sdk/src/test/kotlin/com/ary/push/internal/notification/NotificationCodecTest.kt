package com.ary.push.internal.notification

import com.ary.push.model.PushNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationCodecTest {

    @Test
    fun `a notification survives the round trip through a PendingIntent payload`() {
        val original = PushNotification(
            id = "n1",
            title = "Order shipped",
            body = "Your order #12345 is on its way",
            imageUrl = "https://cdn.ary.com/a.png",
            data = mapOf("action" to "open_order", "orderId" to "12345"),
            receivedAt = 1_700_000_000_000,
            sentAt = 1_699_999_999_000,
            channelId = "orders",
            collapseKey = "orders",
            actionId = "track",
            wasForeground = true
        )

        val restored = NotificationCodec.decode(NotificationCodec.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `optional fields survive being absent`() {
        val minimal = PushNotification(id = "n2", receivedAt = 1_700_000_000_000)

        val restored = NotificationCodec.decode(NotificationCodec.encode(minimal))

        assertEquals("n2", restored?.id)
        assertNull(restored?.title)
        assertNull(restored?.actionId)
        assertEquals(emptyMap<String, String>(), restored?.data)
    }

    @Test
    fun `unreadable payloads decode to null instead of throwing into a system callback`() {
        assertNull(NotificationCodec.decode(null))
        assertNull(NotificationCodec.decode(""))
        assertNull(NotificationCodec.decode("{not json"))
        assertNull(NotificationCodec.decode("""{"title":"no id here"}"""))
    }
}

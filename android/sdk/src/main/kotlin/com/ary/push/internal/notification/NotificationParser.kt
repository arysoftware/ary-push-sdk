package com.ary.push.internal.notification

import com.ary.push.model.PushNotification
import com.google.firebase.messaging.RemoteMessage

/**
 * Maps an FCM [RemoteMessage] onto the platform-neutral [PushNotification].
 *
 * FCM messages arrive in three shapes and the SDK has to normalise all of them:
 *
 *  * **Notification messages** carry a `notification` block. When the app is backgrounded the
 *    system renders these itself and the app is only told on tap.
 *  * **Data messages** carry only `data`, always reach the service, and must be rendered by the
 *    SDK.
 *  * **Mixed messages** carry both.
 *
 * Host applications should prefer data-only messages: they are the only shape whose foreground,
 * background and terminated behaviour the SDK can make identical. See
 * docs/NOTIFICATION_LIFECYCLE.md.
 */
internal object NotificationParser {

    /** Data keys understood as a fallback when there is no `notification` block. */
    private const val KEY_TITLE = "title"
    private const val KEY_BODY = "body"
    private const val KEY_IMAGE = "image"
    private const val KEY_IMAGE_URL = "image_url"
    private const val KEY_CHANNEL_ID = "channel_id"
    private const val KEY_NOTIFICATION_ID = "notification_id"
    private const val KEY_ID = "id"

    fun parse(message: RemoteMessage, wasForeground: Boolean): PushNotification {
        val data = message.data
        val notification = message.notification

        return PushNotification(
            id = resolveId(message),
            title = notification?.title ?: data[KEY_TITLE],
            body = notification?.body ?: data[KEY_BODY],
            imageUrl = notification?.imageUrl?.toString()
                ?: data[KEY_IMAGE_URL]
                ?: data[KEY_IMAGE],
            data = data.toMap(),
            receivedAt = System.currentTimeMillis(),
            sentAt = message.sentTime.takeIf { it > 0 },
            channelId = notification?.channelId ?: data[KEY_CHANNEL_ID],
            collapseKey = message.collapseKey,
            actionId = null,
            wasForeground = wasForeground
        )
    }

    /**
     * A stable identity for deduplication.
     *
     * Preference order matters. A sender-supplied id groups a logical message across resends;
     * FCM's message id is unique per delivery attempt; the content hash is the last resort for
     * senders that supply neither, and is what makes deduplication work at all for them.
     */
    fun resolveId(message: RemoteMessage): String {
        message.data[KEY_NOTIFICATION_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        message.data[KEY_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        message.messageId?.takeIf { it.isNotBlank() }?.let { return it }
        return contentHash(message)
    }

    private fun contentHash(message: RemoteMessage): String {
        val fingerprint = buildString {
            append(message.notification?.title.orEmpty()).append('\u0000')
            append(message.notification?.body.orEmpty()).append('\u0000')
            append(message.collapseKey.orEmpty()).append('\u0000')
            append(message.sentTime).append('\u0000')
            message.data.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append(';')
            }
        }
        return "hash-${fingerprint.hashCode()}"
    }

    /** Android notification ids are ints; this derives a stable one from the message id. */
    fun systemNotificationId(notification: PushNotification): Int {
        // Collapse key first: two messages sharing one deliberately replace each other in the
        // shade, which is what a collapse key is for.
        val basis = notification.collapseKey?.takeIf { it.isNotBlank() } ?: notification.id
        return basis.hashCode()
    }
}

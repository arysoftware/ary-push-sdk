package com.ary.push.internal.notification

import com.ary.push.model.PushNotification

/**
 * Recovers a notification from the extras of the intent that launched the host application.
 *
 * A message carrying a `notification` block is rendered by the system while the application is
 * backgrounded, and FCM never calls the SDK's messaging service for it. Tapping it launches the
 * application's own launcher Activity with the message's data as intent extras, so this is the
 * only place the SDK can see that tap at all — without it, a link on such a message is lost on
 * Android while iOS opens it, because a tap there always reaches the notification delegate.
 *
 * Deliberately takes a plain map rather than a `Bundle`: the rules below are worth testing on
 * their own, and the Android type would make that need an instrumented test.
 */
internal object LaunchIntentParser {

    /** Present on every intent FCM builds, and the signal that this launch was a tap. */
    private const val KEY_MESSAGE_ID = "google.message_id"
    private const val KEY_LEGACY_MESSAGE_ID = "message_id"

    /** Extras the transport adds, which are not part of the sender's data payload. */
    private val TRANSPORT_PREFIXES = listOf("google.", "gcm.", "firebase-")
    private val TRANSPORT_KEYS = setOf("from", "collapse_key", "message_type", "sent_time", "ttl")

    private const val KEY_NOTIFICATION_ID = "notification_id"
    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_BODY = "body"
    private const val GCM_TITLE = "gcm.notification.title"
    private const val GCM_BODY = "gcm.notification.body"

    /**
     * @return the notification the launch intent describes, or null when the intent is an
     *   ordinary launch rather than a notification tap. Anything without a transport message id
     *   is treated as ordinary: the SDK must never mistake a normal cold start for a tap.
     */
    fun parse(extras: Map<String, String>): PushNotification? {
        val messageId = extras[KEY_MESSAGE_ID]?.takeIf { it.isNotBlank() }
            ?: extras[KEY_LEGACY_MESSAGE_ID]?.takeIf { it.isNotBlank() }
            ?: return null

        val data = extras.filterKeys { key -> !isTransportKey(key) }

        return PushNotification(
            // Same preference order the messaging path uses, so one logical message has one
            // identity however it reached the device. That is what stops a tap being handled
            // twice when both paths see it.
            id = data[KEY_NOTIFICATION_ID]?.takeIf { it.isNotBlank() }
                ?: data[KEY_ID]?.takeIf { it.isNotBlank() }
                ?: messageId,
            title = extras[GCM_TITLE] ?: data[KEY_TITLE],
            body = extras[GCM_BODY] ?: data[KEY_BODY],
            data = data,
            // The tap is what brought the application forward, so it was not in the foreground.
            wasForeground = false
        )
    }

    private fun isTransportKey(key: String): Boolean =
        key in TRANSPORT_KEYS || TRANSPORT_PREFIXES.any { prefix -> key.startsWith(prefix) }
}

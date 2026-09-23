package com.ary.push.model

/**
 * Platform-neutral representation of a push notification.
 *
 * Android FCM payloads, iOS APNs payloads and Flutter events are all mapped into this shape so
 * that host application code is identical on every platform.
 *
 * The SDK never interprets [data]. It is delivered verbatim to the host application, which
 * decides what an action means and where to navigate.
 */
public data class PushNotification(
    /**
     * Stable identifier used for deduplication. Derived from the FCM message id, an explicit
     * `notification_id` data key, or a hash of the payload when neither is present.
     */
    public val id: String,

    /** Notification title, when the payload carries one. */
    public val title: String? = null,

    /** Notification body, when the payload carries one. */
    public val body: String? = null,

    /** Remote image to render as a big picture, when the payload carries one. */
    public val imageUrl: String? = null,

    /** The custom data payload, delivered verbatim. */
    public val data: Map<String, String> = emptyMap(),

    /** When the SDK received the message, in epoch milliseconds. */
    public val receivedAt: Long = System.currentTimeMillis(),

    /** When the sender submitted the message, in epoch milliseconds, when reported by FCM. */
    public val sentAt: Long? = null,

    /** Channel the notification was posted to. Null for data-only messages. */
    public val channelId: String? = null,

    /** FCM collapse key, when present. */
    public val collapseKey: String? = null,

    /**
     * Identifier of the action button that was tapped.
     *
     * Null when the notification body itself was tapped, and always null on
     * notification-received events.
     */
    public val actionId: String? = null,

    /** True when the message was received while the application was in the foreground. */
    public val wasForeground: Boolean = false
) {

    /** Convenience accessor for the conventional `action` data key. */
    public val action: String?
        get() = data[KEY_ACTION]

    /**
     * Destination to open when this notification is tapped, from the first of `url`, `deep_link`
     * or `link` that carries a value.
     *
     * The SDK opens this itself on tap. It is exposed so a host that wants to route the link
     * differently can read the same value it would have used.
     */
    public val launchUrl: String?
        get() = LAUNCH_URL_KEYS
            .asSequence()
            .mapNotNull { key -> data[key]?.trim() }
            .firstOrNull { it.isNotEmpty() }

    /** Flat representation used by the Flutter bridge and by persistence. */
    public fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "body" to body,
        "imageUrl" to imageUrl,
        "data" to data,
        "receivedAt" to receivedAt,
        "sentAt" to sentAt,
        "channelId" to channelId,
        "collapseKey" to collapseKey,
        "actionId" to actionId,
        "wasForeground" to wasForeground
    )

    public companion object {
        /** Conventional data key carrying a host-defined action name. */
        public const val KEY_ACTION: String = "action"

        /** Data keys carrying a destination to open on tap, in the order they are consulted. */
        public val LAUNCH_URL_KEYS: List<String> = listOf("url", "deep_link", "link")
    }
}

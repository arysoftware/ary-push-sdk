package com.ary.push.internal.event

import com.ary.push.internal.log.PushLogger
import com.ary.push.model.PushEvent

/**
 * Push-related event reporting.
 *
 * Scope is intentionally narrow. This is not an analytics SDK and must not grow into one: the
 * events that belong here are the ones that describe what happened to a notification, so that
 * delivery and engagement can be attributed on the push backend. Screen views, purchases and
 * funnels belong to whatever analytics product the host application already uses.
 */
internal class EventManager(private val onEvent: (PushEvent) -> Unit) {

    fun track(name: String, properties: Map<String, String> = emptyMap()) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            PushLogger.w { "Ignoring event with a blank name" }
            return
        }
        if (properties.size > MAX_PROPERTIES) {
            PushLogger.w {
                "Event $trimmed carries ${properties.size} properties; only the first " +
                    "$MAX_PROPERTIES are sent"
            }
        }
        val event = PushEvent(
            name = trimmed,
            properties = properties.entries.take(MAX_PROPERTIES)
                .associate { (key, value) -> key to value.take(MAX_VALUE_LENGTH) }
        )
        PushLogger.d { "Event tracked: $trimmed" }
        onEvent(event)
    }

    /** Emitted by the SDK itself when a notification is opened. */
    fun trackNotificationOpened(notificationId: String, actionId: String?) {
        track(
            EVENT_NOTIFICATION_OPENED,
            buildMap {
                put("notificationId", notificationId)
                actionId?.let { put("actionId", it) }
            }
        )
    }

    /** Emitted by the SDK itself when a notification is received and not suppressed. */
    fun trackNotificationReceived(notificationId: String, foreground: Boolean) {
        track(
            EVENT_NOTIFICATION_RECEIVED,
            mapOf("notificationId" to notificationId, "foreground" to foreground.toString())
        )
    }

    internal companion object {
        const val EVENT_NOTIFICATION_RECEIVED = "notification_received"
        const val EVENT_NOTIFICATION_OPENED = "notification_opened"

        private const val MAX_PROPERTIES = 25
        private const val MAX_VALUE_LENGTH = 256
    }
}

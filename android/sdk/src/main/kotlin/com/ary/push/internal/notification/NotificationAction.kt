package com.ary.push.internal.notification

import com.ary.push.internal.log.PushLogger
import org.json.JSONArray

/**
 * An action button attached to a notification.
 *
 * The SDK renders the button and delivers [id] back to the host application when it is tapped.
 * It never interprets the value: what `open_order` means, and which screen it leads to, is the
 * application's business.
 */
internal data class NotificationAction(
    val id: String,
    val title: String
) {
    internal companion object {
        /** Data key carrying a JSON array of `{"id": "...", "title": "..."}` objects. */
        const val DATA_KEY = "actions"

        private const val MAX_ACTIONS = 3 // Android renders at most three.

        fun parse(raw: String?): List<NotificationAction> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until minOf(array.length(), MAX_ACTIONS)) {
                        val json = array.optJSONObject(index) ?: continue
                        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: continue
                        val title = json.optString("title").takeIf { it.isNotEmpty() } ?: id
                        add(NotificationAction(id, title))
                    }
                }
            } catch (t: Throwable) {
                // A malformed actions array must degrade to a plain notification, never to a
                // dropped message.
                PushLogger.w(t) { "Ignoring malformed notification actions payload" }
                emptyList()
            }
        }
    }
}

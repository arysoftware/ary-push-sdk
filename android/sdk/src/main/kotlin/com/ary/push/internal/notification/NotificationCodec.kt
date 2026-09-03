package com.ary.push.internal.notification

import com.ary.push.internal.PushJson
import com.ary.push.internal.log.PushLogger
import com.ary.push.model.PushNotification
import org.json.JSONObject

/**
 * Serialises a [PushNotification] so it can cross a process boundary or outlive one.
 *
 * Needed in two places, both of which rule out passing the object itself:
 *
 *  * the click `PendingIntent`, which the system holds on the SDK's behalf and may deliver long
 *    after this process has died;
 *  * the pending-open record, which has to survive on disk until Dart or host listeners attach.
 *
 * JSON rather than `Parcelable` because the same shape is read back by the Flutter bridge.
 */
internal object NotificationCodec {

    fun encode(notification: PushNotification): String = JSONObject().apply {
        put("id", notification.id)
        notification.title?.let { put("title", it) }
        notification.body?.let { put("body", it) }
        notification.imageUrl?.let { put("imageUrl", it) }
        put("data", PushJson.toJsonObject(notification.data))
        put("receivedAt", notification.receivedAt)
        notification.sentAt?.let { put("sentAt", it) }
        notification.channelId?.let { put("channelId", it) }
        notification.collapseKey?.let { put("collapseKey", it) }
        notification.actionId?.let { put("actionId", it) }
        put("wasForeground", notification.wasForeground)
    }.toString()

    /** Returns null for anything unreadable rather than throwing into a system callback. */
    fun decode(raw: String?): PushNotification? {
        val json = PushJson.parseObject(raw) ?: return null
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return try {
            PushNotification(
                id = id,
                title = json.optString("title").takeIf { it.isNotEmpty() },
                body = json.optString("body").takeIf { it.isNotEmpty() },
                imageUrl = json.optString("imageUrl").takeIf { it.isNotEmpty() },
                data = PushJson.flattenToStringMap(json.optJSONObject("data")),
                receivedAt = json.optLong("receivedAt", System.currentTimeMillis()),
                sentAt = json.optLong("sentAt").takeIf { it > 0 },
                channelId = json.optString("channelId").takeIf { it.isNotEmpty() },
                collapseKey = json.optString("collapseKey").takeIf { it.isNotEmpty() },
                actionId = json.optString("actionId").takeIf { it.isNotEmpty() },
                wasForeground = json.optBoolean("wasForeground", false)
            )
        } catch (t: Throwable) {
            PushLogger.w(t) { "Could not decode a stored notification" }
            null
        }
    }
}

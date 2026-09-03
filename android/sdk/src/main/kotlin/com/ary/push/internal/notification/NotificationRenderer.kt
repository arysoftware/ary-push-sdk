package com.ary.push.internal.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ary.push.ARYPushConfig
import com.ary.push.internal.NotificationOpenActivity
import com.ary.push.internal.log.PushLogger
import com.ary.push.model.PushNotification
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a [PushNotification] into a posted Android notification.
 *
 * The renderer is the only part of the SDK that touches the notification UI, and it deliberately
 * stops at the shade: tapping is routed to [NotificationOpenActivity], which hands the event to
 * the host application. The SDK never opens a screen of its own.
 */
internal class NotificationRenderer(
    context: Context,
    private val config: ARYPushConfig
) {

    private val appContext = context.applicationContext

    private val notificationManager: NotificationManager? =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /**
     * Posts [notification], returning the system notification id, or null when nothing was shown.
     *
     * Called from a background thread: the optional image fetch blocks.
     */
    fun render(notification: PushNotification): Int? {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            PushLogger.i { "Notifications are disabled for this application; nothing rendered" }
            return null
        }

        val channelId = ensureChannel(notification.channelId)
        val systemId = NotificationParser.systemNotificationId(notification)

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(notification.sentAt ?: notification.receivedAt)
            .setContentIntent(openIntent(notification, actionId = null, systemId = systemId))

        config.accentColor?.let { builder.setColor(it) }

        notification.body?.takeIf { it.length > BIG_TEXT_THRESHOLD }?.let { body ->
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        notification.imageUrl?.let { url ->
            downloadBitmap(url)?.let { bitmap ->
                builder.setLargeIcon(bitmap)
                builder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        // Collapsing the large icon avoids the same image appearing twice.
                        .bigLargeIcon(null as Bitmap?)
                )
            }
        }

        NotificationAction.parse(notification.data[NotificationAction.DATA_KEY])
            .forEach { action ->
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        action.title,
                        openIntent(notification, action.id, systemId)
                    ).build()
                )
            }

        return try {
            NotificationManagerCompat.from(appContext).notify(systemId, builder.build())
            PushLogger.d { "Notification rendered on channel $channelId (id=$systemId)" }
            systemId
        } catch (t: Throwable) {
            // Missing POST_NOTIFICATIONS throws SecurityException on API 33+. The message is
            // still delivered as an event, so the application is not left blind.
            PushLogger.w(t) { "Could not post the notification" }
            null
        }
    }

    /** Removes a posted notification, e.g. after the host application handled it in-app. */
    fun cancel(systemNotificationId: Int) {
        runCatching { NotificationManagerCompat.from(appContext).cancel(systemNotificationId) }
    }

    // ------------------------------------------------------------------ channels

    /**
     * Guarantees a usable channel and returns its id.
     *
     * A message may name a channel the application never created, which on API 26+ means the
     * notification is silently dropped by the system. Falling back to the SDK's own channel
     * turns that into a visible notification on a slightly wrong channel, which is strictly
     * better than nothing appearing at all.
     */
    fun ensureChannel(requestedChannelId: String?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return requestedChannelId ?: config.defaultChannelId
        }
        val manager = notificationManager ?: return config.defaultChannelId

        requestedChannelId?.takeIf { it.isNotBlank() }?.let { requested ->
            if (manager.getNotificationChannel(requested) != null) return requested
            PushLogger.w {
                "Channel '$requested' does not exist; falling back to '${config.defaultChannelId}'"
            }
        }

        createDefaultChannel(manager)
        return config.defaultChannelId
    }

    /** Creates the SDK's default channel if it is missing. Safe to call repeatedly. */
    fun createDefaultChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager?.let(::createDefaultChannel)
    }

    private fun createDefaultChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Re-creating an existing channel is a no-op in Android, and importance chosen by the
        // user always wins, so this never overrides a preference.
        if (manager.getNotificationChannel(config.defaultChannelId) != null) return

        val name = config.defaultChannelName
            ?: appContext.getString(com.ary.push.R.string.ary_push_default_channel_name)
        val channel = NotificationChannel(config.defaultChannelId, name, config.defaultChannelImportance)
        channel.description = config.defaultChannelDescription
            ?: appContext.getString(com.ary.push.R.string.ary_push_default_channel_description)
        manager.createNotificationChannel(channel)
        PushLogger.i { "Default notification channel '${config.defaultChannelId}' created" }
    }

    // ------------------------------------------------------------------ intents and images

    /**
     * Builds the click intent.
     *
     * `FLAG_IMMUTABLE` is mandatory from Android 12 and correct everywhere: a mutable
     * `PendingIntent` handed to the system notification shade can be filled in by another
     * application. The request code mixes the action id so that a body tap and each action
     * button get distinct intents instead of overwriting one another.
     */
    private fun openIntent(
        notification: PushNotification,
        actionId: String?,
        systemId: Int
    ): PendingIntent {
        val intent = Intent(appContext, NotificationOpenActivity::class.java).apply {
            action = "${NotificationOpenActivity.ACTION_OPEN}.${notification.id}.${actionId.orEmpty()}"
            putExtra(NotificationOpenActivity.EXTRA_NOTIFICATION, NotificationCodec.encode(notification))
            putExtra(NotificationOpenActivity.EXTRA_ACTION_ID, actionId)
            putExtra(NotificationOpenActivity.EXTRA_SYSTEM_ID, systemId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val requestCode = 31 * systemId + actionId.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, requestCode, intent, flags)
    }

    private fun resolveSmallIcon(): Int {
        if (config.smallIconResId != 0) return config.smallIconResId
        // The launcher icon is a poor notification icon (Android renders it as a grey square if
        // it is not a silhouette) but it is always present, so it beats a crash or a blank icon.
        return appContext.applicationInfo.icon
    }

    /**
     * Fetches a notification image.
     *
     * Bounded on purpose: short timeouts and a size cap, because this runs on the FCM callback
     * thread, which the system will not wait on indefinitely, and because a hostile payload
     * must not be able to make the SDK download an arbitrarily large file.
     */
    private fun downloadBitmap(imageUrl: String): Bitmap? = try {
        val url = URL(imageUrl)
        if (url.protocol !in ALLOWED_IMAGE_SCHEMES) {
            PushLogger.w { "Ignoring notification image with unsupported scheme: ${url.protocol}" }
            null
        } else {
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = IMAGE_TIMEOUT_MS
                readTimeout = IMAGE_TIMEOUT_MS
                instanceFollowRedirects = true
                try {
                    if (responseCode !in 200..299) {
                        PushLogger.w { "Notification image request returned $responseCode" }
                        return@run null
                    }
                    if (contentLength > MAX_IMAGE_BYTES) {
                        PushLogger.w { "Notification image is too large ($contentLength bytes)" }
                        return@run null
                    }
                    inputStream.buffered().use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } finally {
                    disconnect()
                }
            }
        }
    } catch (t: Throwable) {
        // An image that will not load must never cost the user the notification itself.
        PushLogger.w(t) { "Could not load the notification image; rendering without it" }
        null
    }

    private companion object {
        const val BIG_TEXT_THRESHOLD = 48
        const val IMAGE_TIMEOUT_MS = 5_000
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        val ALLOWED_IMAGE_SCHEMES = setOf("https", "http")
    }
}

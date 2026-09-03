package com.ary.push.messaging

import android.content.Context
import com.ary.push.internal.PushCore
import com.ary.push.internal.log.PushLogger
import com.google.firebase.messaging.RemoteMessage

/**
 * The integration point for applications that already own a `FirebaseMessagingService`.
 *
 * FCM delivers `com.google.firebase.MESSAGING_EVENT` to exactly one service. When two are
 * declared, which one wins depends on manifest merge order and is not something an SDK can
 * control, so the SDK does not try: the host application keeps its service and forwards.
 *
 * Two lines, in the service the application already has:
 *
 * ```kotlin
 * class AppMessagingService : FirebaseMessagingService() {
 *
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         if (ARYPushMessaging.handleMessage(this, message)) return
 *         // ... the application's existing handling ...
 *     }
 *
 *     override fun onNewToken(token: String) {
 *         ARYPushMessaging.handleNewToken(this, token)
 *         // ... the application's existing handling ...
 *     }
 * }
 * ```
 *
 * Then remove the SDK's own service from the merged manifest with `tools:node="remove"`, as
 * shown in docs/FIREBASE.md.
 *
 * This API is stable: it is the one seam host applications are asked to implement, so it will
 * not change within a major version.
 */
public object ARYPushMessaging {

    /** Data key the backend stamps on every message it sends. */
    public const val MARKER_KEY: String = "ary_push"

    /**
     * Hands a message to the SDK.
     *
     * @return true when the SDK took ownership of this message and rendered or dispatched it, so
     * the caller should stop. False when the message is not the SDK's concern and the host
     * application should handle it exactly as it always did.
     *
     * Returning false rather than swallowing everything is deliberate: an application that
     * adopts the SDK for campaigns usually still sends its own operational messages, and those
     * must keep working untouched.
     */
    @JvmStatic
    public fun handleMessage(context: Context, message: RemoteMessage): Boolean = try {
        if (!isARYPushMessage(message)) {
            PushLogger.d { "Message ${message.messageId} is not a ARY Push message; ignoring" }
            false
        } else {
            PushCore.ensureInitialized(context.applicationContext).handleRemoteMessage(message)
            true
        }
    } catch (t: Throwable) {
        PushLogger.e(t) { "ARYPushMessaging.handleMessage() failed" }
        false
    }

    /**
     * Hands a refreshed token to the SDK.
     *
     * Safe to call unconditionally, and safe to call alongside the host application's own token
     * registration: the SDK keeps its own copy and ignores unchanged values.
     */
    @JvmStatic
    public fun handleNewToken(context: Context, token: String) {
        try {
            PushCore.ensureInitialized(context.applicationContext).handleNewToken(token)
        } catch (t: Throwable) {
            PushLogger.e(t) { "ARYPushMessaging.handleNewToken() failed" }
        }
    }

    /**
     * Whether a message was sent through the ARY push backend.
     *
     * Recognised by the marker the backend sets on every message, with a fallback to the SDK's
     * conventional keys so that a message composed by hand in the Firebase console during
     * testing is still recognised.
     */
    @JvmStatic
    public fun isARYPushMessage(message: RemoteMessage): Boolean {
        val data = message.data
        return data[MARKER_KEY] != null ||
            data["notification_id"] != null ||
            data["ary_push_id"] != null
    }
}

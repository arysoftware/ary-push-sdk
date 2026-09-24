package com.ary.push.messaging

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.ary.push.internal.PushCore
import com.ary.push.internal.log.PushLogger
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sees every FCM message, whichever `FirebaseMessagingService` FCM chose to deliver it to.
 *
 * FCM starts one messaging service per message. The SDK's is declared at a low priority on
 * purpose, so that a host's own service wins -- but when the winner is `firebase_messaging`'s, as
 * it is in every Flutter application using that plugin, the SDK never saw a message at all, and a
 * notification arriving while the application was open was shown by nobody.
 *
 * Google Play services also broadcasts each message to the application, and a broadcast reaches
 * every receiver rather than one, so this receiver closes that gap with no host code. It hands the
 * message to [PushCore.handleBroadcastMessage], which decides what is safe to touch; anything the
 * SDK's own service also handles is deduplicated on the message's identity, so it happens once.
 *
 * Guarded by `com.google.android.c2dm.permission.SEND`, which only Google Play services holds, so
 * no other application can inject a message through it.
 */
internal class ARYPushMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return

        // Only downstream messages. Deletions and upstream send results arrive on the same action
        // and are the messaging service's business.
        val messageType = extras.getString(EXTRA_MESSAGE_TYPE)
        if (messageType != null && messageType != MESSAGE_TYPE_GCM) return

        val appContext = context.applicationContext
        // Rendering may download an image, which must not run on the main thread; goAsync keeps
        // the process alive until the work is done.
        val pending = goAsync()
        executor.execute {
            try {
                PushCore.ensureInitialized(appContext)
                    .handleBroadcastMessage(RemoteMessage(extras), isSdkServiceDeclared(appContext))
            } catch (t: Throwable) {
                // A message the host application did not send must never crash it.
                PushLogger.e(t) { "Failed to handle a broadcast message" }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Whether the SDK's own messaging service survived manifest merging.
     *
     * A host that removed it with `tools:node="remove"` forwards from its own service through
     * [ARYPushMessaging], which only ever passes on the backend's messages; this path keeps to
     * the same rule for that host.
     */
    private fun isSdkServiceDeclared(context: Context): Boolean = try {
        context.packageManager.getServiceInfo(
            ComponentName(context, ARYPushFirebaseMessagingService::class.java),
            PackageManager.GET_META_DATA
        )
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val EXTRA_MESSAGE_TYPE = "message_type"
        const val MESSAGE_TYPE_GCM = "gcm"

        /** One thread keeps messages in arrival order, as the messaging service does. */
        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ARYPush-receiver").apply { isDaemon = true }
        }
    }
}

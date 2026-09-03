package com.ary.push.messaging

import com.ary.push.internal.PushCore
import com.ary.push.internal.log.PushLogger
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The SDK's `FirebaseMessagingService`.
 *
 * Declared in the SDK's manifest and merged into the host application automatically, so an
 * application with no messaging service of its own needs no code at all to receive messages.
 *
 * Applications that already declare their own service must not rely on both existing: FCM
 * resolves a single service for `com.google.firebase.MESSAGING_EVENT` and which one wins is not
 * guaranteed. Remove this one and forward from yours through [ARYPushMessaging] instead.
 * See docs/FIREBASE.md.
 */
public class ARYPushFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Called on a background thread for every data message, and for notification messages while
     * the application is in the foreground.
     *
     * The process may have been started specifically for this callback, so the SDK is brought up
     * here rather than assumed. Handling is synchronous: the system only guarantees the process
     * stays alive for the duration of this method.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        try {
            PushCore.ensureInitialized(applicationContext).handleRemoteMessage(message)
        } catch (t: Throwable) {
            // Throwing here would surface as a crash in the host application, caused by a
            // message the host application did not send.
            PushLogger.e(t) { "Failed to handle an incoming message" }
        }
    }

    /**
     * Called when FCM issues a new token.
     *
     * The host application does not need to forward this anywhere: the SDK persists it and
     * synchronises it with the backend through the durable queue.
     */
    override fun onNewToken(token: String) {
        try {
            PushCore.ensureInitialized(applicationContext).handleNewToken(token)
        } catch (t: Throwable) {
            PushLogger.e(t) { "Failed to handle a token refresh" }
        }
    }

    /**
     * Called when messages were dropped server-side, typically after a long offline period.
     *
     * FCM does not say which messages were lost, so the correct response is to re-synchronise
     * installation state rather than to guess at content.
     */
    override fun onDeletedMessages() {
        PushLogger.w { "FCM reported deleted messages; re-synchronising installation state" }
        runCatching {
            PushCore.ensureInitialized(applicationContext)
                .syncManager.registerInstallationIfChanged(force = true)
        }
    }

    override fun onSendError(messageId: String, exception: Exception) {
        PushLogger.w(exception) { "Upstream message $messageId failed to send" }
    }
}

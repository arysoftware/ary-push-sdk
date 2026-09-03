package com.ary.push.samplefb

import android.util.Log
import com.ary.push.messaging.ARYPushMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The application's own messaging service, from before the SDK existed.
 *
 * Adopting the SDK adds two lines to it. Everything the application already did keeps working:
 * its operational messages are still handled by its own code, its own token registration still
 * runs, and its existing deep links and analytics are untouched.
 */
class ExistingMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        // Line 1. Returns true only for messages sent through the ARY push backend; the
        // application's own messages fall through to the handling it always had.
        if (ARYPushMessaging.handleMessage(this, message)) return

        Log.i(TAG, "Handling an application-owned message: ${message.data}")
        handleLegacyOperationalMessage(message)
    }

    override fun onNewToken(token: String) {
        // Line 2. The SDK keeps its own copy and synchronises it. Safe to call alongside the
        // application's existing registration; unchanged tokens are ignored.
        ARYPushMessaging.handleNewToken(this, token)

        Log.i(TAG, "Also registering the token with the application's own backend")
        registerTokenWithLegacyBackend(token)
    }

    private fun handleLegacyOperationalMessage(message: RemoteMessage) {
        // The application's pre-existing rendering, deep links and analytics live here, exactly
        // as they did before the SDK was added.
    }

    private fun registerTokenWithLegacyBackend(token: String) {
        // The application's pre-existing token registration lives here.
    }

    private companion object {
        const val TAG = "ExistingMessaging"
    }
}

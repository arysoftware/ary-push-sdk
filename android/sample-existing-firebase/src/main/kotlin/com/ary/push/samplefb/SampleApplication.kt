package com.ary.push.samplefb

import android.app.Application
import android.util.Log
import com.ary.push.ARYPush
import com.ary.push.ARYPushConfig
import com.ary.push.ForegroundDisplayPolicy
import com.ary.push.PushLogLevel
import com.google.firebase.FirebaseApp

/**
 * An application that was already using Firebase before the SDK arrived.
 *
 * Note what is *not* here: the SDK does not initialize Firebase, does not replace the existing
 * FirebaseApp, and does not touch the application's Firebase configuration. It attaches to
 * whatever the host has already set up.
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // The application's own Firebase setup, unchanged and still first.
        FirebaseApp.initializeApp(this)
        Log.i(TAG, "Firebase initialized by the application, as it always was")

        ARYPush.initialize(
            this,
            ARYPushConfig(
                enableLogging = BuildConfig.DEBUG,
                logLevel = PushLogLevel.DEBUG,
                // This application already shows its own in-app banner for foreground messages,
                // so the SDK delivers the event but renders nothing. Without this the user
                // would see the same message twice.
                foregroundDisplay = ForegroundDisplayPolicy.EVENT_ONLY,
                defaultChannelId = "ary_push_campaigns",
                defaultChannelName = "Offers and updates"
            )
        )

        ARYPush.addNotificationOpenedListener { notification ->
            Log.i(TAG, "ARY Push notification opened: ${notification.data}")
            // The application's existing router handles this exactly like its own deep links.
        }

        ARYPush.addNotificationReceivedListener { notification ->
            Log.i(TAG, "ARY Push notification received: ${notification.id}")
            // Show the application's own in-app banner here.
        }
    }

    private companion object {
        const val TAG = "SampleApplication"
    }
}

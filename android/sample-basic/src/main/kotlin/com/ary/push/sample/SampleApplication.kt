package com.ary.push.sample

import android.app.Application
import com.ary.push.ARYPush
import com.ary.push.ARYPushConfig
import com.ary.push.PushBackendConfig
import com.ary.push.PushLogLevel

/**
 * The whole push integration for a brand-new application.
 *
 * Two things happen here and nothing else: the SDK is initialized, and a listener is attached so
 * the application can route notification taps. Permission, tokens, token refreshes, the
 * installation identity, rendering, channels, deduplication, backend registration, the offline
 * queue and retries are all handled by the SDK.
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        ARYPush.initialize(
            this,
            ARYPushConfig(
                enableLogging = BuildConfig.DEBUG,
                logLevel = PushLogLevel.DEBUG,
                // Omit `backend` entirely and everything above still works, with no server.
                backend = PushBackendConfig(
                    baseUrl = "https://push-api-dev.ary.com",
                    applicationId = "sample_android"
                )
            )
        )

        // Registered here, in Application.onCreate, so that a tap which cold-started the process
        // is replayed to this listener rather than lost.
        ARYPush.addNotificationOpenedListener { notification ->
            SampleRouter.route(this, notification)
        }
    }
}

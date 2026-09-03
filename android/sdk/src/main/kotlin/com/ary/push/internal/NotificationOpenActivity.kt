package com.ary.push.internal

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.notification.NotificationCodec

/**
 * Invisible trampoline that turns a notification tap into an SDK event and a launched app.
 *
 * Android 12 removed the ability to start an Activity from a `BroadcastReceiver` or `Service`
 * woken by a notification, so a tap has to land on an Activity. This is that Activity, and it
 * does exactly three things:
 *
 *  1. makes sure the SDK is initialized, because the tap may be what started the process;
 *  2. dispatches the open event, persisting it when no listener has attached yet;
 *  3. hands control to the host application's own launch intent.
 *
 * What it does not do is navigate. It has no idea what `order_id` means, and deciding that is
 * the host application's job. It is translucent, `noHistory`, excluded from recents and has an
 * empty task affinity, so the user never sees it and it never joins their task stack.
 */
internal class NotificationOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
        // No transition: the user should perceive their own app opening, not two activities.
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return

        val raw = intent.getStringExtra(EXTRA_NOTIFICATION)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        val systemId = intent.getIntExtra(EXTRA_SYSTEM_ID, 0)

        val notification = NotificationCodec.decode(raw)
        if (notification == null) {
            PushLogger.w { "Notification open carried no readable payload; launching the app only" }
            launchHostApplication()
            return
        }

        try {
            // The tap may be what started this process, so the core cannot be assumed to exist.
            PushCore.ensureInitialized(applicationContext)
            PushCore.instance?.handleNotificationOpened(
                notification.copy(actionId = actionId),
                systemNotificationId = systemId
            )
        } catch (t: Throwable) {
            // Never let SDK bookkeeping stop the user reaching the application they tapped for.
            PushLogger.e(t) { "Failed to dispatch the notification open event" }
        }

        launchHostApplication()
    }

    /**
     * Brings the host application forward.
     *
     * The package launch intent resumes an existing task when there is one and cold-starts
     * otherwise, which is what a user expects from a notification tap. The SDK deliberately
     * stops here: the host application's own router decides where the user actually lands,
     * driven by the event dispatched above.
     */
    private fun launchHostApplication() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                PushLogger.w { "No launch intent for $packageName; nothing to open" }
                return
            }
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launchIntent)
        } catch (t: Throwable) {
            PushLogger.e(t) { "Could not launch the host application after a notification tap" }
        }
    }

    internal companion object {
        const val ACTION_OPEN: String = "com.ary.push.action.NOTIFICATION_OPEN"
        const val EXTRA_NOTIFICATION: String = "com.ary.push.extra.NOTIFICATION"
        const val EXTRA_ACTION_ID: String = "com.ary.push.extra.ACTION_ID"
        const val EXTRA_SYSTEM_ID: String = "com.ary.push.extra.SYSTEM_ID"
    }
}

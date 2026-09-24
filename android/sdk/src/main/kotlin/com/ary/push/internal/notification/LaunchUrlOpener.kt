package com.ary.push.internal.notification

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ary.push.internal.log.PushLogger

/**
 * Opens the destination a notification carried.
 *
 * Shared by the two ways a tap can reach the SDK: the trampoline Activity, for a notification the
 * SDK rendered itself, and the launch intent of the host application, for one the system rendered
 * from a `notification` block. Both must behave identically, which is why the logic lives once.
 */
internal object LaunchUrlOpener {

    /**
     * Opens [launchUrl], if there is one.
     *
     * The host application is preferred over every other handler: an `https` link the application
     * itself declares an intent filter for opens inside it rather than in a browser, and the user
     * never sees a chooser. Only when nothing in the application can handle it does the link go
     * to whichever app can, which is what makes a plain web link behave the way a user expects.
     *
     * @return true when something was started, so the caller knows not to launch the app as well.
     */
    fun open(context: Context, launchUrl: String?): Boolean {
        if (launchUrl.isNullOrBlank()) return false

        val uri = runCatching { Uri.parse(launchUrl) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank()) {
            // Without a scheme there is nothing for Android to resolve, and guessing one would
            // send the user somewhere the payload never asked for.
            PushLogger.w { "Notification launch URL is not an absolute URI; opening the app instead" }
            return false
        }

        PushLogger.i { "Attempting internal AppLink/Universal Link routing for URL: $launchUrl" }
        return startViewIntent(context, uri, restrictToHostApplication = true) ||
            startViewIntent(context, uri, restrictToHostApplication = false)
    }

    private fun startViewIntent(
        context: Context,
        uri: Uri,
        restrictToHostApplication: Boolean
    ): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (restrictToHostApplication) intent.setPackage(context.packageName)

        return try {
            context.startActivity(intent)
            PushLogger.d {
                "Opened notification link in " + if (restrictToHostApplication) "the app" else "another app"
            }
            true
        } catch (e: ActivityNotFoundException) {
            // Expected on the first attempt whenever the application declares no filter for it.
            false
        } catch (t: Throwable) {
            PushLogger.e(t) { "Could not open the notification link" }
            false
        }
    }
}

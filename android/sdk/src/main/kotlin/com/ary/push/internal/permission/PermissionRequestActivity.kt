package com.ary.push.internal.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.ary.push.internal.log.PushLogger
import com.ary.push.model.PushPermissionStatus
import java.util.concurrent.atomic.AtomicReference

/**
 * Invisible host for the `POST_NOTIFICATIONS` system prompt.
 *
 * Android can only ask for a runtime permission from an Activity, and it only reports the answer
 * to that Activity. Requiring every host application to wire up an `ActivityResultLauncher` and
 * forward the callback would put permission plumbing back into exactly the place the SDK exists
 * to keep it out of, so the SDK brings its own one-shot Activity instead.
 *
 * It is translucent, excluded from recents, has no history and holds no state beyond the
 * in-flight callback, so it never appears in the task stack the user sees and never leaks an
 * Activity reference past the prompt.
 */
internal class PermissionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            finishWithCurrentStatus()
            return
        }

        // A configuration change would otherwise re-issue the prompt on top of the live one.
        if (savedInstanceState != null) return

        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } catch (t: Throwable) {
            PushLogger.e(t) { "Could not present the notification permission prompt" }
            finishWithCurrentStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A prompt the user swipes away never reports a result. Completing here guarantees the
        // caller is answered exactly once rather than left waiting forever.
        if (isFinishing) deliver(statusReader.get()?.invoke() ?: PushPermissionStatus.NOT_DETERMINED)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) finishWithCurrentStatus()
    }

    private fun finishWithCurrentStatus() {
        // The status is re-read from the system rather than inferred from grantResults, so that
        // an app-level notification toggle is reflected too.
        val status = runCatching { statusReader.get()?.invoke() }
            .getOrNull() ?: PushPermissionStatus.NOT_DETERMINED
        deliver(status)
        finish()
        overridePendingTransition(0, 0)
    }

    internal companion object {
        private const val REQUEST_CODE = 0x0BEE

        /** Set by the SDK core so the Activity can read status without holding SDK references. */
        private val statusReader = AtomicReference<(() -> PushPermissionStatus)?>(null)

        private val pendingCallback = AtomicReference<((PushPermissionStatus) -> Unit)?>(null)

        fun install(reader: () -> PushPermissionStatus) {
            statusReader.set(reader)
        }

        /**
         * Launches the prompt.
         *
         * [callback] is invoked exactly once, whatever the user does, including dismissing the
         * prompt or the Activity being destroyed.
         */
        fun start(context: Context, callback: (PushPermissionStatus) -> Unit) {
            // Replacing an in-flight callback would strand the earlier caller, so the previous
            // one is completed with the current status before being replaced.
            pendingCallback.getAndSet(callback)?.let { superseded ->
                runCatching {
                    superseded(statusReader.get()?.invoke() ?: PushPermissionStatus.NOT_DETERMINED)
                }
            }

            val intent = Intent(context.applicationContext, PermissionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try {
                context.applicationContext.startActivity(intent)
            } catch (t: Throwable) {
                PushLogger.e(t) { "Could not start the permission request activity" }
                deliver(statusReader.get()?.invoke() ?: PushPermissionStatus.NOT_DETERMINED)
            }
        }

        private fun deliver(status: PushPermissionStatus) {
            pendingCallback.getAndSet(null)?.let { callback ->
                runCatching { callback(status) }
                    .onFailure { PushLogger.e(it) { "Permission callback threw" } }
            }
        }
    }
}

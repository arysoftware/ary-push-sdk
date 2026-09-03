package com.ary.push.internal.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import com.ary.push.model.PushPermissionStatus

/**
 * Reads and requests the notification permission, hiding the Android 13 split.
 *
 * Two separate things decide whether a notification can be posted, and confusing them is the
 * usual source of "the token is fine but nothing appears":
 *
 *  * the `POST_NOTIFICATIONS` runtime permission, which only exists from API 33;
 *  * the application-level notification toggle, which exists on every version and which the user
 *    can switch off in Settings at any time.
 *
 * [status] reports the combination, so host code never has to branch on API level.
 */
internal class PermissionManager(
    context: Context,
    private val storage: StorageManager
) {

    private val appContext = context.applicationContext

    /** Current normalised permission state. */
    val status: PushPermissionStatus
        get() {
            val enabledAtAppLevel = runCatching {
                NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            }.getOrDefault(true)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // Before API 33 there is nothing to grant: the app-level toggle is the whole story.
                return if (enabledAtAppLevel) {
                    PushPermissionStatus.GRANTED
                } else {
                    PushPermissionStatus.DENIED
                }
            }

            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            return when {
                granted && enabledAtAppLevel -> PushPermissionStatus.GRANTED
                granted -> PushPermissionStatus.DENIED // Granted, then switched off in Settings.
                hasBeenAsked -> PushPermissionStatus.DENIED
                else -> PushPermissionStatus.NOT_DETERMINED
            }
        }

    /** True when notifications can actually be posted right now. */
    val isAuthorized: Boolean get() = status.isAuthorized

    /**
     * Whether the system prompt has already been shown.
     *
     * Android cannot distinguish "never asked" from "asked and denied twice" without an
     * Activity, so the SDK records the fact itself. Without this, a permanently denied user
     * would be reported as [PushPermissionStatus.NOT_DETERMINED] forever.
     */
    var hasBeenAsked: Boolean
        get() = storage.getBoolean(StorageManager.KEY_PERMISSION_REQUESTED, false)
        set(value) = storage.putBoolean(StorageManager.KEY_PERMISSION_REQUESTED, value)

    /** True when a system prompt is still possible; false when only Settings can help. */
    val canRequest: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            status == PushPermissionStatus.NOT_DETERMINED

    /**
     * Records the permission state and reports whether it changed.
     *
     * The backend needs to know when a device stops being reachable, and the OS never tells the
     * application that the user switched notifications off, so this is sampled on every
     * initialization and on every permission request.
     */
    fun recordStatusChange(): Boolean {
        val current = status.name
        val previous = storage.getString(StorageManager.KEY_LAST_PERMISSION_STATE)
        if (previous == current) return false
        storage.putString(StorageManager.KEY_LAST_PERMISSION_STATE, current)
        PushLogger.i { "Permission status: ${previous ?: "unknown"} -> $current" }
        return true
    }

    /** Intent that opens this application's notification settings, for the denied case. */
    fun notificationSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", appContext.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

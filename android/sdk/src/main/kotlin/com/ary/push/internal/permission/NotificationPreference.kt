package com.ary.push.internal.permission

import com.ary.push.internal.storage.StorageManager

/**
 * The user's in-app choice to receive notifications, set through
 * [com.ary.push.ARYPush.setNotificationsEnabled].
 *
 * Separate from the operating system's permission, which only the user can change and only in
 * Settings. Notifications reach the user when both allow it, and that combination is what the
 * backend is told: a user who switches notifications off inside the application stops receiving
 * them even though the system permission is still granted.
 *
 * On until the application turns it off, and persisted, so the choice survives restarts.
 */
internal class NotificationPreference(private val storage: StorageManager) {

    /** The in-app choice; `true` unless the application has turned notifications off. */
    val enabledByUser: Boolean
        get() = storage.getBoolean(StorageManager.KEY_NOTIFICATIONS_ENABLED_BY_USER, true)

    /**
     * Records the in-app choice.
     *
     * @return whether it changed. Setting the current value again is a no-op, so a switch that
     *   re-applies its state on every screen load causes no backend traffic.
     */
    fun setEnabledByUser(enabled: Boolean): Boolean {
        if (enabled == enabledByUser) return false
        storage.putBoolean(StorageManager.KEY_NOTIFICATIONS_ENABLED_BY_USER, enabled)
        return true
    }

    /** What the backend is told: the system permits notifications and the user has not opted out. */
    fun effective(osAuthorized: Boolean): Boolean = osAuthorized && enabledByUser
}

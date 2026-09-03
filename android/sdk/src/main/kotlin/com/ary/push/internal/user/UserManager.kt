package com.ary.push.internal.user

import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager

/**
 * Owns the association between this installation and a user.
 *
 * The three identifiers the SDK handles are deliberately distinct, and mixing them up is the
 * usual cause of "the wrong person got the notification":
 *
 *  * **Installation ID** identifies an app on a device. Stable forever.
 *  * **Push token** identifies a delivery route. Rotates on its own schedule.
 *  * **User ID** identifies a person. Changes at every login and logout, and one person may own
 *    several installations.
 *
 * Logout clears only the last of these.
 */
internal class UserManager(
    private val storage: StorageManager,
    private val onUserChanged: (userId: String?) -> Unit
) {

    /** Currently associated user, or null when logged out. */
    val userId: String? get() = storage.getString(StorageManager.KEY_USER_ID)

    val isLoggedIn: Boolean get() = userId != null

    /**
     * Associates this installation with [userId].
     *
     * Local state is updated first and synchronously, so an offline login is immediately true
     * from the application's point of view; the backend catches up through the queue.
     */
    @Synchronized
    fun login(userId: String) {
        val trimmed = userId.trim()
        require(trimmed.isNotEmpty()) { "userId must not be blank" }

        if (this.userId == trimmed) {
            PushLogger.d { "login() ignored: already associated with this user" }
            return
        }

        // Switching directly from one user to another on a shared device: the previous
        // association is replaced, never merged.
        storage.putString(StorageManager.KEY_USER_ID, trimmed, durable = true)
        PushLogger.i { "User associated with installation" }
        onUserChanged(trimmed)
    }

    /**
     * Clears the user association and nothing else.
     *
     * The installation id, the push token and the device registration all survive. An
     * application that unregisters the device on logout stops being able to reach the user with
     * anything at all, including the "come back" campaigns that logout exists to enable.
     */
    @Synchronized
    fun logout() {
        if (userId == null) {
            PushLogger.d { "logout() ignored: no user is associated" }
            return
        }
        storage.putString(StorageManager.KEY_USER_ID, null, durable = true)
        PushLogger.i { "User association cleared; installation and token retained" }
        onUserChanged(null)
    }
}

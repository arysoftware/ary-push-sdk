package com.ary.push.internal.installation

import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import java.util.UUID

/**
 * Owns the installation identity.
 *
 * The installation id is the SDK's own anchor and is deliberately independent of everything
 * else it might be confused with:
 *
 *  * not the push token, which rotates whenever FCM decides to;
 *  * not the user id, which changes at every login and logout;
 *  * not a device id, which the SDK has no business reading.
 *
 * One user may own many installations (a phone and a tablet); one installation belongs to at
 * most one user at a time. Keeping the id stable across token refreshes and logouts is what lets
 * the backend keep tag history and delivery state attached to a device.
 */
internal class InstallationManager(private val storage: StorageManager) {

    /**
     * Returns the installation id, creating and persisting one on first use.
     *
     * Synchronised because a cold start triggered by a background message can race the host
     * application's own `initialize()` call, and two ids would mean two registrations.
     */
    @get:Synchronized
    val installationId: String
        get() = storage.getString(StorageManager.KEY_INSTALLATION_ID) ?: createAndPersist()

    @Synchronized
    private fun createAndPersist(): String {
        // Re-read inside the lock: another thread may have created it while this one waited.
        storage.getString(StorageManager.KEY_INSTALLATION_ID)?.let { return it }

        val generated = UUID.randomUUID().toString()
        // Durable: an installation id that is lost to process death would orphan the backend
        // record and silently double every device in reporting.
        storage.putString(StorageManager.KEY_INSTALLATION_ID, generated, durable = true)
        PushLogger.i { "Installation ID created" }
        return generated
    }

    /** True when an id already exists, without creating one as a side effect. */
    fun hasInstallation(): Boolean =
        storage.getString(StorageManager.KEY_INSTALLATION_ID) != null

    /**
     * Discards the installation identity.
     *
     * Not part of logout, and not exposed on the public API: this exists for the documented
     * support path where a device's records must genuinely be started over.
     */
    @Synchronized
    fun reset() {
        storage.remove(
            StorageManager.KEY_INSTALLATION_ID,
            StorageManager.KEY_REGISTRATION_HASH
        )
        PushLogger.w { "Installation identity reset" }
    }
}

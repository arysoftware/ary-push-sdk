package com.ary.push.internal.storage

import android.content.Context
import android.content.SharedPreferences
import com.ary.push.internal.PushJson
import com.ary.push.internal.log.PushLogger

/**
 * Namespaced, isolated local storage for SDK state.
 *
 * Two isolation guarantees matter here:
 *
 *  * **From the host application.** Everything lives in a private preferences file of the SDK's
 *    own, under `ary_push.` keys, so the SDK can never collide with, read or clobber an
 *    application's own preferences.
 *  * **Between applications.** `Context.getSharedPreferences` is already per-application private
 *    storage, so two applications embedding this SDK on one device get entirely separate
 *    installation ids, tokens, users, tags and queues with no extra work.
 *
 * Writes that must survive an immediate process death (the pending notification open, the sync
 * queue) are committed synchronously. Everything else is applied asynchronously.
 */
internal class StorageManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- primitives

    fun getString(key: String): String? = guard(null) { prefs.getString(key, null) }

    fun putString(key: String, value: String?, durable: Boolean = false) {
        guard(Unit) {
            val editor = prefs.edit()
            if (value == null) editor.remove(key) else editor.putString(key, value)
            if (durable) editor.commit() else editor.apply()
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        guard(default) { prefs.getBoolean(key, default) }

    fun putBoolean(key: String, value: Boolean) {
        guard(Unit) { prefs.edit().putBoolean(key, value).apply() }
    }

    fun getLong(key: String, default: Long): Long = guard(default) { prefs.getLong(key, default) }

    fun putLong(key: String, value: Long) {
        guard(Unit) { prefs.edit().putLong(key, value).apply() }
    }

    fun getStringSet(key: String): Set<String> =
        guard(emptySet()) { prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet() }

    fun putStringSet(key: String, value: Set<String>) {
        // A defensive copy is required: SharedPreferences does not copy the set it is handed,
        // and mutating it afterwards corrupts the stored value.
        guard(Unit) { prefs.edit().putStringSet(key, LinkedHashSet(value)).apply() }
    }

    fun remove(vararg keys: String) {
        guard(Unit) {
            val editor = prefs.edit()
            keys.forEach(editor::remove)
            editor.apply()
        }
    }

    // ---------------------------------------------------------------- string maps

    /** Reads a string map stored as a JSON object. */
    fun getStringMap(key: String): Map<String, String> = guard(emptyMap()) {
        PushJson.flattenToStringMap(PushJson.parseObject(prefs.getString(key, null)))
    }

    /** Stores a string map as a JSON object, or removes the entry when the map is empty. */
    fun putStringMap(key: String, value: Map<String, String>, durable: Boolean = false) {
        guard(Unit) {
            val editor = prefs.edit()
            if (value.isEmpty()) {
                editor.remove(key)
            } else {
                editor.putString(key, PushJson.toJsonObject(value).toString())
            }
            if (durable) editor.commit() else editor.apply()
        }
    }

    /** Clears every SDK key. Used by tests and by the documented "reset device" support path. */
    fun clearAll() {
        guard(Unit) { prefs.edit().clear().commit() }
    }

    /**
     * Storage must never take the host application down.
     *
     * A disk-full device or a corrupted preferences file degrades the SDK to "no persisted
     * state", which is recoverable, instead of crashing an application that merely wanted to
     * receive a notification.
     */
    private inline fun <T> guard(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        PushLogger.w(t) { "Storage operation failed; continuing without persisted state" }
        fallback
    }

    internal companion object {
        const val FILE_NAME: String = "ary_push_store"

        private const val NS = "ary_push."

        const val KEY_INSTALLATION_ID: String = NS + "installation_id"
        const val KEY_PUSH_TOKEN: String = NS + "push_token"
        const val KEY_PUSH_PROVIDER: String = NS + "push_provider"
        const val KEY_TOKEN_SYNCED: String = NS + "push_token_synced"
        const val KEY_USER_ID: String = NS + "user_id"
        const val KEY_TAGS: String = NS + "tags"
        const val KEY_TOPICS: String = NS + "topics"
        const val KEY_PENDING_OPEN: String = NS + "pending_open"
        const val KEY_PENDING_OPERATIONS: String = NS + "pending_operations"
        const val KEY_SEEN_MESSAGE_IDS: String = NS + "seen_message_ids"
        const val KEY_REGISTRATION_HASH: String = NS + "registration_hash"
        const val KEY_LAST_PERMISSION_STATE: String = NS + "last_permission_state"
        const val KEY_PERMISSION_REQUESTED: String = NS + "permission_requested"
        const val KEY_APP_VERSION: String = NS + "app_version"
        const val KEY_SDK_VERSION: String = NS + "sdk_version"
    }
}

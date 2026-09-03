package com.ary.push.internal.token

import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import com.ary.push.model.PushProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Owns the push token for its whole life: first issue, refresh, replacement, invalidation.
 *
 * A push token is never permanent. FCM rotates it on reinstall, on restore to a new device, on
 * app data clear and occasionally for its own reasons. Every part of the SDK therefore treats
 * the token as a cache of something the transport owns, and the backend is told about every
 * change through the sync queue rather than by the host application.
 */
internal class TokenManager(
    private val storage: StorageManager,
    private val onTokenChanged: (token: String, provider: PushProvider) -> Unit
) {

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** Last known token, or null before one has ever been issued. */
    val currentToken: String? get() = storage.getString(StorageManager.KEY_PUSH_TOKEN)

    /** Transport that issued [currentToken]. */
    val provider: PushProvider
        get() = PushProvider.fromWire(storage.getString(StorageManager.KEY_PUSH_PROVIDER))

    fun addListener(listener: (String) -> Unit) {
        listeners.addIfAbsent(listener)
        // A token that arrived before the host application attached its listener is still news
        // to that listener, so it is replayed rather than dropped.
        currentToken?.let { token -> notifyOne(listener, token) }
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Retrieves the token, asking FCM when the SDK has not cached one yet.
     *
     * Returns null rather than throwing when Firebase is unavailable or unconfigured: an
     * application with a missing `google-services.json` should log one clear error and keep
     * running, not crash on launch.
     */
    suspend fun fetchToken(): String? {
        currentToken?.let { return it }
        return requestFromFirebase()
    }

    /** Forces a fresh read from FCM, bypassing the cached value. */
    suspend fun refreshToken(): String? = requestFromFirebase()

    /**
     * Records a token reported by `FirebaseMessagingService.onNewToken`.
     *
     * Idempotent: an unchanged token is ignored, so an OEM that redelivers the same token on
     * every boot does not produce a backend write on every boot.
     */
    @Synchronized
    fun handleNewToken(token: String, provider: PushProvider = PushProvider.FCM) {
        if (token.isBlank()) {
            PushLogger.w { "Ignoring blank push token" }
            return
        }
        val previous = currentToken
        if (previous == token && this.provider == provider) {
            PushLogger.d { "Push token unchanged (${PushLogger.mask(token)})" }
            return
        }

        storage.putString(StorageManager.KEY_PUSH_TOKEN, token, durable = true)
        storage.putString(StorageManager.KEY_PUSH_PROVIDER, provider.wireValue)
        storage.putBoolean(StorageManager.KEY_TOKEN_SYNCED, false)

        PushLogger.i {
            val verb = if (previous == null) "received" else "refreshed"
            "Push token $verb: ${PushLogger.mask(token)} (${provider.wireValue})"
        }

        onTokenChanged(token, provider)
        notifyAll(token)
    }

    /**
     * Deletes the token at the transport.
     *
     * Not part of logout. A user logging out of an application still owns the device and should
     * keep receiving unauthenticated campaigns; deleting the token here would silently
     * unregister the device.
     */
    suspend fun deleteToken(): Boolean = try {
        firebaseMessaging()?.let { messaging ->
            suspendCancellableCoroutine { continuation ->
                messaging.deleteToken().addOnCompleteListener { task ->
                    if (continuation.isActive) continuation.resume(task.isSuccessful)
                }
            }
        } ?: false
    } catch (t: Throwable) {
        PushLogger.e(t) { "Could not delete the push token" }
        false
    }.also { deleted ->
        if (deleted) {
            storage.remove(StorageManager.KEY_PUSH_TOKEN, StorageManager.KEY_PUSH_PROVIDER)
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun requestFromFirebase(): String? {
        val messaging = firebaseMessaging() ?: return null
        return try {
            val token: String? = suspendCancellableCoroutine { continuation ->
                messaging.token.addOnCompleteListener { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        continuation.resume(task.result)
                    } else {
                        PushLogger.e(task.exception) { "FCM did not return a token" }
                        continuation.resume(null)
                    }
                }
            }
            token?.also { handleNewToken(it, PushProvider.FCM) }
        } catch (t: Throwable) {
            PushLogger.e(t) { "Token request failed" }
            null
        }
    }

    /**
     * Resolves FCM, tolerating an application that has not configured Firebase.
     *
     * `FirebaseMessaging.getInstance()` throws when no `FirebaseApp` has been initialized, which
     * is exactly what happens in an application that added the SDK but has not yet added its own
     * `google-services.json`. That is a configuration mistake with a clear fix, not a crash.
     */
    private fun firebaseMessaging(): FirebaseMessaging? = try {
        FirebaseMessaging.getInstance()
    } catch (t: Throwable) {
        PushLogger.e(t) {
            "Firebase is not initialized in this application, so no push token can be issued. " +
                "Add the host application's own google-services.json and the " +
                "com.google.gms.google-services plugin. See docs/FIREBASE.md."
        }
        null
    }

    private fun notifyAll(token: String) {
        listeners.forEach { listener -> notifyOne(listener, token) }
    }

    private fun notifyOne(listener: (String) -> Unit, token: String) {
        // Host callbacks are foreign code. One that throws must not stop the others, and must
        // never propagate into FCM's service thread.
        runCatching { listener(token) }
            .onFailure { PushLogger.e(it) { "Token refresh listener threw" } }
    }
}

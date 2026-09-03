package com.ary.push.internal.topic

import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Topic subscriptions.
 *
 * Topics are a transport feature: FCM fans a message out to everyone subscribed, with no backend
 * involvement. The SDK still records the subscription set locally and reports it, so that the
 * push backend can show which devices are on which topic without querying Google.
 *
 * Topics are not segments. A topic is something a device opts into; a segment is something the
 * backend computes from tags. See docs/BACKEND.md.
 */
internal class TopicManager(
    private val storage: StorageManager,
    private val onTopicsChanged: (Set<String>) -> Unit
) {

    /** Locally recorded subscriptions. */
    val topics: Set<String> get() = storage.getStringSet(StorageManager.KEY_TOPICS)

    fun subscribe(topic: String, onResult: (Boolean) -> Unit = {}) {
        val normalized = normalize(topic) ?: run {
            PushLogger.w { "Rejected invalid topic name: $topic" }
            onResult(false)
            return
        }

        val messaging = messaging() ?: run { onResult(false); return }
        messaging.subscribeToTopic(normalized).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                persist(topics + normalized)
                PushLogger.i { "Subscribed to topic $normalized" }
            } else {
                PushLogger.w(task.exception) { "Could not subscribe to topic $normalized" }
            }
            onResult(task.isSuccessful)
        }
    }

    fun unsubscribe(topic: String, onResult: (Boolean) -> Unit = {}) {
        val normalized = normalize(topic) ?: run {
            PushLogger.w { "Rejected invalid topic name: $topic" }
            onResult(false)
            return
        }

        val messaging = messaging() ?: run { onResult(false); return }
        messaging.unsubscribeFromTopic(normalized).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                persist(topics - normalized)
                PushLogger.i { "Unsubscribed from topic $normalized" }
            } else {
                PushLogger.w(task.exception) { "Could not unsubscribe from topic $normalized" }
            }
            onResult(task.isSuccessful)
        }
    }

    private fun persist(updated: Set<String>) {
        storage.putStringSet(StorageManager.KEY_TOPICS, updated)
        onTopicsChanged(updated)
    }

    private fun messaging(): FirebaseMessaging? = try {
        FirebaseMessaging.getInstance()
    } catch (t: Throwable) {
        PushLogger.e(t) { "Firebase is not initialized; topic operations are unavailable" }
        null
    }

    internal companion object {
        /**
         * FCM's own topic grammar. Validating here turns a silent server-side rejection into an
         * immediate, logged, local failure.
         */
        private val VALID_TOPIC = Regex("[a-zA-Z0-9-_.~%]{1,900}")

        /** Strips the optional `/topics/` prefix and validates, returning null when invalid. */
        fun normalize(topic: String): String? {
            val bare = topic.trim().removePrefix("/topics/")
            return if (bare.matches(VALID_TOPIC)) bare else null
        }
    }
}

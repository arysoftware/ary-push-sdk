package com.ary.push.internal.notification

import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager

/**
 * Stops the same message being processed twice.
 *
 * Duplicates are normal, not exceptional. FCM guarantees at-least-once delivery and will resend
 * after a transport hiccup; an application with both its own `FirebaseMessagingService` and the
 * SDK's can see one message on two paths; and a notification restored after a process restart
 * can be re-handled. Without this, users see the same alert twice and the backend counts one
 * delivery as two.
 *
 * The cache is an LRU bounded by [maxSize] and persisted, so it survives the process restart
 * that a background message causes. Bounded is the operative word: an unbounded seen-set on a
 * device that receives thousands of messages is a slow storage leak.
 */
internal class DeduplicationManager(
    private val storage: StorageManager,
    private val maxSize: Int
) {

    private val lock = Any()

    /**
     * Records [messageId] and reports whether it is new.
     *
     * Check and insert are one atomic step on purpose: FCM can deliver two copies of a message
     * on two threads, and a separate `contains` then `add` would let both through.
     */
    fun markSeenIfNew(messageId: String): Boolean = synchronized(lock) {
        if (messageId.isBlank()) return true

        val seen = read()
        if (seen.contains(messageId)) {
            PushLogger.d { "Duplicate message ignored: $messageId" }
            return false
        }

        val updated = ArrayList<String>(seen.size + 1)
        updated.addAll(seen)
        updated.add(messageId)
        // Oldest entries fall off the front once the bound is reached.
        val trimmed = if (updated.size > maxSize) {
            updated.subList(updated.size - maxSize, updated.size).toList()
        } else {
            updated
        }
        write(trimmed)
        true
    }

    /** True when the message has already been handled, without recording it. */
    fun hasSeen(messageId: String): Boolean = synchronized(lock) { read().contains(messageId) }

    fun clear(): Unit = synchronized(lock) {
        storage.putString(StorageManager.KEY_SEEN_MESSAGE_IDS, null)
    }

    /** Current cache size. Used by tests to assert the bound actually holds. */
    fun size(): Int = synchronized(lock) { read().size }

    private fun read(): List<String> =
        storage.getString(StorageManager.KEY_SEEN_MESSAGE_IDS)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun write(ids: List<String>) {
        storage.putString(
            StorageManager.KEY_SEEN_MESSAGE_IDS,
            ids.joinToString(SEPARATOR),
            // Durable: a background message is often the last thing to happen before the
            // process is killed, and a lost write means a duplicate on the next delivery.
            durable = true
        )
    }

    private companion object {
        /**
         * A separator that cannot occur inside an FCM message id, so no id can ever be split
         * into two phantom entries.
         */
        const val SEPARATOR = "\u0001"
    }
}

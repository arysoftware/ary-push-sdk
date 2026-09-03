package com.ary.push.internal.tag

import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tags: the attributes the backend builds segments from.
 *
 * Tags are flat key/value attributes owned by the device, e.g. `subscription=premium`,
 * `language=en`. The SDK never evaluates a segment rule: deciding that "Premium Pakistan Users"
 * means `subscription == premium AND country == PK` is backend work, because the rule changes
 * far more often than the app is released.
 *
 * Writes land locally first and are then coalesced: a screen that sets five tags in a row
 * produces one PATCH, not five.
 */
internal class TagManager(
    private val storage: StorageManager,
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val onTagsChanged: (Map<String, String>) -> Unit,
    private val onTagsRemoved: (keys: Set<String>, all: Boolean) -> Unit
) {

    private val lock = Any()

    /** Tags accumulated since the last flush, waiting to be synchronised. */
    private val dirty = LinkedHashMap<String, String>()

    @Volatile
    private var flushJob: Job? = null

    /** Current tags, read from local storage so an offline read is always correct. */
    val tags: Map<String, String> get() = storage.getStringMap(StorageManager.KEY_TAGS)

    fun addTag(key: String, value: String) = addTags(mapOf(key to value))

    fun addTags(newTags: Map<String, String>) {
        val sanitized = newTags.asSequence()
            .mapNotNull { (key, value) ->
                val trimmedKey = key.trim()
                if (trimmedKey.isEmpty()) {
                    PushLogger.w { "Ignoring tag with a blank key" }
                    null
                } else {
                    trimmedKey to value
                }
            }
            .toMap()

        if (sanitized.isEmpty()) return

        synchronized(lock) {
            val current = tags.toMutableMap()
            val changed = sanitized.filter { (key, value) -> current[key] != value }
            if (changed.isEmpty()) {
                PushLogger.d { "addTags() ignored: no values changed" }
                return
            }
            current.putAll(changed)
            storage.putStringMap(StorageManager.KEY_TAGS, current, durable = true)
            dirty.putAll(changed)
        }
        PushLogger.d { "Tags updated locally: ${sanitized.keys}" }
        scheduleFlush()
    }

    fun removeTag(key: String) = removeTags(setOf(key))

    fun removeTags(keys: Set<String>) {
        val trimmed = keys.map(String::trim).filter(String::isNotEmpty).toSet()
        if (trimmed.isEmpty()) return

        val actuallyRemoved: Set<String>
        synchronized(lock) {
            val current = tags.toMutableMap()
            actuallyRemoved = trimmed.filter { current.remove(it) != null }.toSet()
            if (actuallyRemoved.isEmpty()) return
            storage.putStringMap(StorageManager.KEY_TAGS, current, durable = true)
            // A removal supersedes any unsent write of the same key.
            actuallyRemoved.forEach(dirty::remove)
        }
        PushLogger.d { "Tags removed locally: $actuallyRemoved" }
        onTagsRemoved(actuallyRemoved, false)
    }

    fun removeAllTags() {
        synchronized(lock) {
            if (tags.isEmpty()) return
            storage.putStringMap(StorageManager.KEY_TAGS, emptyMap(), durable = true)
            dirty.clear()
        }
        PushLogger.i { "All tags removed locally" }
        onTagsRemoved(emptySet(), true)
    }

    /** Sends anything still pending immediately, cancelling the debounce window. */
    fun flushNow() {
        flushJob?.cancel()
        flushJob = null
        emitDirty()
    }

    private fun scheduleFlush() {
        if (debounceMs <= 0L) {
            emitDirty()
            return
        }
        // Restarting the window is what collapses a burst: only the final call actually sends.
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(debounceMs)
            emitDirty()
        }
    }

    private fun emitDirty() {
        val batch: Map<String, String>
        synchronized(lock) {
            if (dirty.isEmpty()) return
            batch = LinkedHashMap(dirty)
            dirty.clear()
        }
        onTagsChanged(batch)
    }
}

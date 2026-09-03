package com.ary.push.internal.sync

import com.ary.push.internal.PushJson
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import org.json.JSONArray

/**
 * The durable, bounded, self-coalescing queue behind offline support.
 *
 * Three properties matter:
 *
 *  * **Durable.** Every mutation is committed synchronously, so a `login()` made while offline is
 *    still pending after the process is killed and the device rebooted.
 *  * **Coalescing.** Enqueueing collapses redundant work at the source rather than sending it and
 *    hoping the backend copes: three `addTag` calls become one PATCH, and a newer token replaces
 *    an older unsent one instead of queueing behind it.
 *  * **Bounded.** A device that is offline for a week must not accumulate unbounded state.
 *    Beyond [MAX_OPERATIONS] the oldest low-value entries (events) are dropped first.
 *
 * Access is synchronised on the instance; the queue is small and contention is negligible.
 */
internal class OperationQueue(private val storage: StorageManager) {

    private val lock = Any()

    /** Adds an operation, collapsing it into an equivalent pending one where possible. */
    fun enqueue(operation: PendingOperation): Unit = synchronized(lock) {
        val current = readInternal().toMutableList()

        when {
            operation.type.isLatestValueWins ->
                current.removeAll { it.type == operation.type }

            operation.type == OperationType.UPDATE_TAGS -> {
                // Merge into the pending tag write so a burst of addTag calls costs one request.
                val existingIndex = current.indexOfFirst { it.type == OperationType.UPDATE_TAGS }
                if (existingIndex >= 0) {
                    val merged = mergeTagPayloads(current[existingIndex], operation)
                    current[existingIndex] = merged
                    writeInternal(current)
                    return
                }
            }
        }

        // A logout supersedes an unsent identify, and vice versa: sending both is contradictory.
        when (operation.type) {
            OperationType.LOGOUT_USER -> current.removeAll { it.type == OperationType.IDENTIFY_USER }
            OperationType.IDENTIFY_USER -> current.removeAll { it.type == OperationType.LOGOUT_USER }
            else -> Unit
        }

        current += operation
        writeInternal(current.enforceBound())
    }

    /** Snapshot of pending operations in dependency order. */
    fun snapshot(): List<PendingOperation> = synchronized(lock) {
        readInternal().sortedWith(compareBy({ it.type.ordinal }, { it.createdAt }))
    }

    fun isEmpty(): Boolean = synchronized(lock) { readInternal().isEmpty() }

    fun size(): Int = synchronized(lock) { readInternal().size }

    /** Removes a completed or permanently failed operation. */
    fun remove(operationId: String): Unit = synchronized(lock) {
        writeInternal(readInternal().filterNot { it.id == operationId })
    }

    /** Records a failed attempt so that backoff and the permanent-failure cut-off survive restarts. */
    fun recordAttempt(operationId: String): Unit = synchronized(lock) {
        writeInternal(
            readInternal().map {
                if (it.id == operationId) it.copy(attempts = it.attempts + 1) else it
            }
        )
    }

    fun clear(): Unit = synchronized(lock) {
        storage.putString(StorageManager.KEY_PENDING_OPERATIONS, null, durable = true)
    }

    // ------------------------------------------------------------------ persistence

    private fun readInternal(): List<PendingOperation> {
        val raw = storage.getString(StorageManager.KEY_PENDING_OPERATIONS) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val element = array.optJSONObject(index) ?: continue
                    PendingOperation.fromJson(element)?.let(::add)
                }
            }
        } catch (t: Throwable) {
            // A corrupted queue is dropped rather than retried forever. Losing deferred
            // synchronisation is recoverable; a permanently wedged queue is not.
            PushLogger.w(t) { "Pending operation queue was unreadable and has been reset" }
            storage.putString(StorageManager.KEY_PENDING_OPERATIONS, null, durable = true)
            emptyList()
        }
    }

    private fun writeInternal(operations: List<PendingOperation>) {
        if (operations.isEmpty()) {
            storage.putString(StorageManager.KEY_PENDING_OPERATIONS, null, durable = true)
            return
        }
        val array = JSONArray()
        operations.forEach { array.put(it.toJson()) }
        storage.putString(StorageManager.KEY_PENDING_OPERATIONS, array.toString(), durable = true)
    }

    private fun mergeTagPayloads(
        existing: PendingOperation,
        incoming: PendingOperation
    ): PendingOperation {
        val existingTags = PushJson.flattenToStringMap(
            PushJson.parseObject(existing.payload[PendingOperation.KEY_TAGS])
        )
        val incomingTags = PushJson.flattenToStringMap(
            PushJson.parseObject(incoming.payload[PendingOperation.KEY_TAGS])
        )
        val merged = existingTags + incomingTags
        return existing.copy(
            payload = mapOf(
                PendingOperation.KEY_TAGS to PushJson.toJsonObject(merged).toString()
            ),
            // Merging produces a new value, so the attempt counter starts over.
            attempts = 0
        )
    }

    private fun List<PendingOperation>.enforceBound(): List<PendingOperation> {
        if (size <= MAX_OPERATIONS) return this
        PushLogger.w { "Pending operation queue exceeded $MAX_OPERATIONS entries; trimming" }
        val (events, rest) = partition { it.type == OperationType.TRACK_EVENTS }
        val trimmedEvents = events.sortedBy { it.createdAt }
            .drop((size - MAX_OPERATIONS).coerceAtMost(events.size))
        val combined = rest + trimmedEvents
        return if (combined.size <= MAX_OPERATIONS) {
            combined
        } else {
            combined.sortedBy { it.createdAt }.takeLast(MAX_OPERATIONS)
        }
    }

    private companion object {
        /** Deliberately small: this is deferred bookkeeping, not a message store. */
        const val MAX_OPERATIONS = 100
    }
}


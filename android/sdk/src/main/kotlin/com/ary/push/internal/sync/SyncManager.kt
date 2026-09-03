package com.ary.push.internal.sync

import com.ary.push.api.ApiResult
import com.ary.push.backend.PushBackend
import com.ary.push.internal.PushJson
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.storage.StorageManager
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Turns local state changes into backend calls that eventually happen.
 *
 * The contract with the rest of the SDK is deliberately one-directional: managers change local
 * state first and tell the sync manager afterwards. Nothing in the push path ever waits on this
 * class, which is what makes a backend outage invisible to notification delivery.
 *
 * Drain rules:
 *
 *  * Operations run in dependency order ([OperationType] ordinal), never before the installation
 *    they describe has been registered.
 *  * A failure stops the drain rather than skipping ahead, so a later operation can never
 *    overtake the one it depends on.
 *  * Transient failures are retried with jittered backoff and a persisted attempt count.
 *    Permanent failures, and operations that exhaust their attempts, are dropped: a queue that
 *    retries a 422 forever is a queue that never drains again.
 */
internal class SyncManager(
    private val scope: CoroutineScope,
    private val queue: OperationQueue,
    private val storage: StorageManager,
    private val isOnline: () -> Boolean,
    private val retryManager: RetryManager,
    private val backendProvider: () -> PushBackend,
    private val installationProvider: () -> Installation?
) {

    private val drainMutex = Mutex()

    @Volatile
    private var drainJob: Job? = null

    // ------------------------------------------------------------------ public entry points

    fun enqueueTokenUpdate(token: String, provider: PushProvider) = enqueue(
        PendingOperation(
            type = OperationType.UPDATE_TOKEN,
            payload = mapOf(
                PendingOperation.KEY_TOKEN to token,
                PendingOperation.KEY_PROVIDER to provider.wireValue
            )
        )
    )

    fun enqueueIdentify(userId: String) = enqueue(
        PendingOperation(
            type = OperationType.IDENTIFY_USER,
            payload = mapOf(PendingOperation.KEY_USER_ID to userId)
        )
    )

    fun enqueueLogout() = enqueue(PendingOperation(type = OperationType.LOGOUT_USER))

    fun enqueueTagUpdate(tags: Map<String, String>) {
        if (tags.isEmpty()) return
        enqueue(
            PendingOperation(
                type = OperationType.UPDATE_TAGS,
                payload = mapOf(
                    PendingOperation.KEY_TAGS to PushJson.toJsonObject(tags).toString()
                )
            )
        )
    }

    fun enqueueTagRemoval(keys: Set<String>, all: Boolean) = enqueue(
        PendingOperation(
            type = OperationType.REMOVE_TAGS,
            payload = mapOf(
                PendingOperation.KEY_TAG_KEYS to keys.joinToString(","),
                PendingOperation.KEY_REMOVE_ALL to all.toString()
            )
        )
    )

    fun enqueueTopicUpdate(topics: Set<String>) = enqueue(
        PendingOperation(
            type = OperationType.UPDATE_TOPICS,
            payload = mapOf(PendingOperation.KEY_TOPICS to topics.joinToString(","))
        )
    )

    fun enqueuePermissionUpdate(enabled: Boolean) = enqueue(
        PendingOperation(
            type = OperationType.UPDATE_PERMISSION,
            payload = mapOf(PendingOperation.KEY_ENABLED to enabled.toString())
        )
    )

    fun enqueueEvent(event: PushEvent) = enqueue(
        PendingOperation(
            type = OperationType.TRACK_EVENTS,
            payload = mapOf(PendingOperation.KEY_EVENTS to encodeEvents(listOf(event)))
        )
    )

    /**
     * Registers the installation when its contents actually changed.
     *
     * Repeated launches with identical data are the common case, and re-registering on every
     * cold start would multiply the backend's write load by the number of app opens for no
     * information gain.
     */
    fun registerInstallationIfChanged(force: Boolean = false) {
        val installation = installationProvider() ?: return
        val hash = registrationHash(installation)
        if (!force && storage.getString(StorageManager.KEY_REGISTRATION_HASH) == hash) {
            PushLogger.d { "Installation registration unchanged; skipping request" }
            requestSync()
            return
        }
        enqueue(PendingOperation(type = OperationType.REGISTER_INSTALLATION))
    }

    /** Adds an operation and asks the queue to drain. */
    fun enqueue(operation: PendingOperation) {
        queue.enqueue(operation)
        PushLogger.d { "Queued ${operation.type} (pending=${queue.size()})" }
        requestSync()
    }

    /** Drains the queue if it is not already draining. Safe to call from anywhere, at any time. */
    fun requestSync() {
        if (queue.isEmpty()) return
        if (drainJob?.isActive == true) return
        drainJob = scope.launch { drain() }
    }

    // ------------------------------------------------------------------ drain loop

    private suspend fun drain() {
        drainMutex.withLock {
            while (true) {
                val pending = queue.snapshot()
                if (pending.isEmpty()) return

                if (!isOnline()) {
                    // Nothing is lost: the queue is durable and the connectivity callback will
                    // call requestSync() again as soon as a network appears.
                    PushLogger.d { "Offline; ${pending.size} operation(s) stay queued" }
                    return
                }

                val operation = pending.first()
                val outcome = execute(operation)

                when {
                    outcome.isSuccess -> {
                        queue.remove(operation.id)
                        PushLogger.d { "Synced ${operation.type}" }
                    }

                    !outcome.isRetryable -> {
                        queue.remove(operation.id)
                        PushLogger.w {
                            "Dropping ${operation.type}: permanent failure ${describe(outcome)}"
                        }
                    }

                    else -> {
                        queue.recordAttempt(operation.id)
                        val attempts = operation.attempts + 1
                        if (!retryManager.canRetry(attempts)) {
                            queue.remove(operation.id)
                            PushLogger.w {
                                "Dropping ${operation.type} after $attempts attempt(s): " +
                                    describe(outcome)
                            }
                            continue
                        }
                        val waitMs = retryManager.delayMillis(
                            attempts,
                            (outcome as? ApiResult.Error)?.retryAfterMs
                        )
                        PushLogger.d {
                            "Retrying ${operation.type} in ${waitMs}ms (attempt $attempts)"
                        }
                        delay(waitMs)
                        // Ordering matters, so the drain waits for this operation rather than
                        // moving on to one that may depend on it.
                    }
                }
            }
        }
    }

    private suspend fun execute(operation: PendingOperation): ApiResult<Unit> {
        val installation = installationProvider()
            ?: return ApiResult.Error(null, "no_installation", "Installation is not available yet")
        val backend = backendProvider()

        return try {
            when (operation.type) {
                OperationType.REGISTER_INSTALLATION ->
                    backend.registerInstallation(installation).also { result ->
                        if (result.isSuccess) {
                            storage.putString(
                                StorageManager.KEY_REGISTRATION_HASH,
                                registrationHash(installation)
                            )
                        }
                    }

                OperationType.UPDATE_TOKEN -> {
                    val token = operation.payload[PendingOperation.KEY_TOKEN]
                    if (token.isNullOrEmpty()) {
                        ApiResult.Error(null, "missing_token", "Queued token update had no token")
                    } else {
                        requireRegistered(installation, backend) {
                            backend.updateToken(
                                installation.id,
                                token,
                                PushProvider.fromWire(operation.payload[PendingOperation.KEY_PROVIDER])
                            )
                        }
                    }
                }

                OperationType.IDENTIFY_USER -> {
                    val userId = operation.payload[PendingOperation.KEY_USER_ID]
                    if (userId.isNullOrEmpty()) {
                        ApiResult.Error(null, "missing_user", "Queued identify had no user id")
                    } else {
                        requireRegistered(installation, backend) {
                            backend.identify(installation.id, userId)
                        }
                    }
                }

                OperationType.LOGOUT_USER ->
                    requireRegistered(installation, backend) { backend.logout(installation.id) }

                OperationType.UPDATE_TAGS -> {
                    val tags = PushJson.flattenToStringMap(
                        PushJson.parseObject(operation.payload[PendingOperation.KEY_TAGS])
                    )
                    if (tags.isEmpty()) {
                        ApiResult.Success(Unit, 200)
                    } else {
                        requireRegistered(installation, backend) {
                            backend.updateTags(installation.id, tags)
                        }
                    }
                }

                OperationType.REMOVE_TAGS -> {
                    val all = operation.payload[PendingOperation.KEY_REMOVE_ALL].toBoolean()
                    val keys = operation.payload[PendingOperation.KEY_TAG_KEYS]
                        .orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                    requireRegistered(installation, backend) {
                        backend.removeTags(installation.id, keys, all)
                    }
                }

                OperationType.UPDATE_TOPICS -> {
                    val topics = operation.payload[PendingOperation.KEY_TOPICS]
                        .orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                    requireRegistered(installation, backend) {
                        backend.updateTopics(installation.id, topics)
                    }
                }

                OperationType.UPDATE_PERMISSION -> {
                    val enabled = operation.payload[PendingOperation.KEY_ENABLED].toBoolean()
                    requireRegistered(installation, backend) {
                        backend.updateNotificationPermission(installation.id, enabled)
                    }
                }

                OperationType.TRACK_EVENTS -> {
                    val events = decodeEvents(operation.payload[PendingOperation.KEY_EVENTS])
                    if (events.isEmpty()) {
                        ApiResult.Success(Unit, 200)
                    } else {
                        requireRegistered(installation, backend) {
                            backend.trackEvents(installation.id, events)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A backend implementation is host-supplied code. It must not be able to take the
            // SDK, or the application, down.
            PushLogger.e(t) { "Backend threw while executing ${operation.type}" }
            ApiResult.NetworkError(t)
        }
    }

    /**
     * Guarantees the installation exists before a dependent write is attempted.
     *
     * The queue already orders registration first, but an installation can also be created by a
     * background message long before the queue is touched, so the invariant is enforced here as
     * well rather than assumed.
     */
    private suspend fun requireRegistered(
        installation: Installation,
        backend: PushBackend,
        block: suspend () -> ApiResult<Unit>
    ): ApiResult<Unit> {
        val expected = registrationHash(installation)
        if (storage.getString(StorageManager.KEY_REGISTRATION_HASH) == expected) return block()

        val registration = backend.registerInstallation(installation)
        if (!registration.isSuccess) {
            PushLogger.d { "Deferring dependent operation: registration ${describe(registration)}" }
            return registration
        }
        storage.putString(StorageManager.KEY_REGISTRATION_HASH, expected)
        return block()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Identity of a registration payload.
     *
     * Only the fields the backend stores contribute, so a locale change re-registers but a new
     * `receivedAt` timestamp does not.
     */
    private fun registrationHash(installation: Installation): String = listOf(
        installation.id,
        installation.applicationId.orEmpty(),
        installation.provider.wireValue,
        installation.pushToken.orEmpty(),
        installation.userId.orEmpty(),
        installation.appVersion.orEmpty(),
        installation.appBuild.orEmpty(),
        installation.sdkVersion,
        installation.osVersion.orEmpty(),
        installation.deviceModel.orEmpty(),
        installation.locale.orEmpty(),
        installation.timezone.orEmpty(),
        installation.notificationsEnabled.toString()
    ).joinToString("|").hashCode().toString()

    private fun encodeEvents(events: List<PushEvent>): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                PushJson.toJsonObject(
                    mapOf(
                        "name" to event.name,
                        "occurredAt" to event.occurredAt,
                        "properties" to event.properties
                    )
                )
            )
        }
        return array.toString()
    }

    private fun decodeEvents(raw: String?): List<PushEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val name = json.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    add(
                        PushEvent(
                            name = name,
                            properties = PushJson.flattenToStringMap(
                                json.optJSONObject("properties")
                            ),
                            occurredAt = json.optLong("occurredAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            PushLogger.w(t) { "Discarding unreadable queued events" }
            emptyList()
        }
    }

    private fun describe(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "success ${result.statusCode}"
        is ApiResult.Error -> "HTTP ${result.statusCode} ${result.code.orEmpty()} ${result.message.orEmpty()}".trim()
        is ApiResult.NetworkError -> "transport ${result.exception::class.simpleName}"
    }

    /** Clears deferred work. Used by tests and by the documented device-reset support path. */
    fun reset() {
        queue.clear()
        storage.remove(StorageManager.KEY_REGISTRATION_HASH)
    }
}

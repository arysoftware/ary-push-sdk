package com.ary.push.internal.sync

import com.ary.push.internal.PushJson
import org.json.JSONObject
import java.util.UUID

/**
 * One durable unit of backend synchronisation.
 *
 * Operations are persisted, so they must be representable as plain JSON: no lambdas, no object
 * references, nothing that a process restart would lose.
 */
internal data class PendingOperation(
    val id: String = UUID.randomUUID().toString(),
    val type: OperationType,
    /** Operation arguments. Values are strings, or JSON-encoded strings for nested data. */
    val payload: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Attempts already made. Persisted so backoff survives a restart. */
    val attempts: Int = 0
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(FIELD_ID, id)
        put(FIELD_TYPE, type.name)
        put(FIELD_PAYLOAD, PushJson.toJsonObject(payload))
        put(FIELD_CREATED_AT, createdAt)
        put(FIELD_ATTEMPTS, attempts)
    }

    companion object {
        private const val FIELD_ID = "id"
        private const val FIELD_TYPE = "type"
        private const val FIELD_PAYLOAD = "payload"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_ATTEMPTS = "attempts"

        /** Payload keys, shared with [SyncManager]. */
        const val KEY_TOKEN = "token"
        const val KEY_PROVIDER = "provider"
        const val KEY_USER_ID = "userId"
        const val KEY_TAGS = "tags"
        const val KEY_TAG_KEYS = "tagKeys"
        const val KEY_REMOVE_ALL = "removeAll"
        const val KEY_TOPICS = "topics"
        const val KEY_ENABLED = "enabled"
        const val KEY_EVENTS = "events"

        /** Returns null for entries that cannot be understood, so one bad row cannot wedge the queue. */
        fun fromJson(json: JSONObject): PendingOperation? {
            val typeName = json.optString(FIELD_TYPE).takeIf { it.isNotEmpty() } ?: return null
            val type = OperationType.entries.firstOrNull { it.name == typeName } ?: return null
            return PendingOperation(
                id = json.optString(FIELD_ID).takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
                type = type,
                payload = PushJson.flattenToStringMap(json.optJSONObject(FIELD_PAYLOAD)),
                createdAt = json.optLong(FIELD_CREATED_AT, System.currentTimeMillis()),
                attempts = json.optInt(FIELD_ATTEMPTS, 0)
            )
        }
    }
}

/**
 * The operations the SDK can defer.
 *
 * [ordinal] order is also dependency order: the queue never sends an operation before the
 * operation it depends on, because a token update or an identify call is meaningless to a
 * backend that has not been told the installation exists yet.
 */
internal enum class OperationType {
    REGISTER_INSTALLATION,
    UPDATE_TOKEN,
    IDENTIFY_USER,
    LOGOUT_USER,
    UPDATE_TAGS,
    REMOVE_TAGS,
    UPDATE_TOPICS,
    UPDATE_PERMISSION,
    TRACK_EVENTS;

    /**
     * Whether a newly enqueued operation of this type supersedes an identical pending one.
     *
     * Token, identity, topics and permission are "latest value wins" state, so keeping older
     * copies would send known-stale data. Tag writes merge instead, and events accumulate.
     */
    val isLatestValueWins: Boolean
        get() = this == UPDATE_TOKEN || this == IDENTIFY_USER || this == LOGOUT_USER ||
            this == UPDATE_TOPICS || this == UPDATE_PERMISSION || this == REGISTER_INSTALLATION
}

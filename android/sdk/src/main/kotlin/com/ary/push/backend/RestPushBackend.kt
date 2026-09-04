package com.ary.push.backend

import com.ary.push.PushBackendConfig
import com.ary.push.api.ApiResult
import com.ary.push.api.IgnoreBody
import com.ary.push.api.RestClient
import com.ary.push.internal.PushJson
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import com.ary.push.model.Segment
import org.json.JSONObject

/**
 * Maps push operations onto the ARY push API.
 *
 * This class is the only place in the SDK that knows the wire contract. It contains no HTTP
 * mechanics (that is [RestClient]) and no scheduling or retry policy (that is the sync queue),
 * which keeps the contract easy to read against docs/REST_API.md and easy to version.
 *
 * Every request is idempotent by construction: registration is keyed on the installation id,
 * and token, identity, tag and topic writes are full replacements or merges rather than deltas.
 */
internal class RestPushBackend(
    private val client: RestClient,
    private val config: PushBackendConfig
) : PushBackend {

    override suspend fun registerInstallation(installation: Installation): ApiResult<Unit> {
        val body = buildMap<String, Any?> {
            put("applicationId", installation.applicationId ?: config.applicationId)
            put("installationId", installation.id)
            put("platform", installation.platform)
            put("provider", installation.provider.wireValue)
            put("pushToken", installation.pushToken)
            put("userId", installation.userId)
            put("appVersion", installation.appVersion)
            put("appBuild", installation.appBuild)
            put("sdkVersion", installation.sdkVersion)
            put("notificationsEnabled", installation.notificationsEnabled)
            // Optional device block, omitted entirely when device collection is disabled.
            val device = buildMap<String, Any?> {
                installation.osVersion?.let { put("osVersion", it) }
                installation.deviceModel?.let { put("deviceModel", it) }
                installation.locale?.let { put("locale", it) }
                installation.timezone?.let { put("timezone", it) }
            }
            if (device.isNotEmpty()) put("device", device)
        }
        return client.post(PATH_INSTALLATIONS, body, parser = IgnoreBody)
    }

    override suspend fun updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ): ApiResult<Unit> = client.put(
        path = "$PATH_INSTALLATIONS/$installationId/token",
        body = mapOf("token" to token, "provider" to provider.wireValue),
        parser = IgnoreBody
    )

    override suspend fun identify(installationId: String, userId: String): ApiResult<Unit> =
        client.post(
            path = "$PATH_INSTALLATIONS/$installationId/identify",
            body = mapOf("userId" to userId),
            parser = IgnoreBody
        )

    override suspend fun logout(installationId: String): ApiResult<Unit> =
        // Deletes the user association only. The installation, its token and its device
        // registration survive, so the device keeps receiving unauthenticated campaigns.
        client.delete(path = "$PATH_INSTALLATIONS/$installationId/user")

    override suspend fun updateTags(
        installationId: String,
        tags: Map<String, String>
    ): ApiResult<Unit> = client.patch(
        path = "$PATH_INSTALLATIONS/$installationId/tags",
        body = mapOf("tags" to tags),
        parser = IgnoreBody
    )

    override suspend fun removeTags(
        installationId: String,
        keys: Set<String>,
        all: Boolean
    ): ApiResult<Unit> {
        val query = if (all) {
            mapOf("all" to true)
        } else {
            if (keys.isEmpty()) return ApiResult.Success(Unit, statusCode = 200)
            mapOf("keys" to keys.joinToString(","))
        }
        return client.delete(path = "$PATH_INSTALLATIONS/$installationId/tags", query = query)
    }

    override suspend fun updateTopics(
        installationId: String,
        topics: Set<String>
    ): ApiResult<Unit> = client.put(
        path = "$PATH_INSTALLATIONS/$installationId/topics",
        body = mapOf("topics" to topics.toList()),
        parser = IgnoreBody
    )

    override suspend fun updateNotificationPermission(
        installationId: String,
        enabled: Boolean
    ): ApiResult<Unit> = client.patch(
        path = "$PATH_INSTALLATIONS/$installationId",
        body = mapOf("notificationsEnabled" to enabled),
        parser = IgnoreBody
    )

    override suspend fun getSegments(installationId: String): ApiResult<List<Segment>> =
        client.get(path = "$PATH_INSTALLATIONS/$installationId/segments") { raw ->
            parseSegments(raw)
        }

    override suspend fun trackEvents(
        installationId: String,
        events: List<PushEvent>
    ): ApiResult<Unit> {
        if (events.isEmpty()) return ApiResult.Success(Unit, statusCode = 200)
        val body = mapOf(
            "installationId" to installationId,
            "events" to events.map { event ->
                mapOf(
                    "name" to event.name,
                    "occurredAt" to event.occurredAt,
                    "properties" to event.properties
                )
            }
        )
        return client.post(PATH_EVENTS, body, parser = IgnoreBody)
    }

    override fun close() {
        client.close()
    }

    /**
     * Parses the segments payload.
     *
     * Accepts either a bare array or an object with a `segments` key, because a gateway that
     * wraps collection responses is common and neither shape is worth failing over. An entry
     * without an id or name is skipped rather than failing the whole response: one malformed
     * segment should not cost the caller the rest.
     */
    private fun parseSegments(raw: String): List<Segment> {
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?: PushJson.parseObject(raw)?.optJSONArray("segments")
            ?: return emptyList()

        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val json: JSONObject = array.optJSONObject(index) ?: continue
                val id = json.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val name = json.optString("name").takeIf { it.isNotEmpty() } ?: id
                add(
                    Segment(
                        id = id,
                        name = name,
                        description = json.optString("description").takeIf { it.isNotEmpty() },
                        joinedAt = json.optLong("joinedAt").takeIf { it > 0 }
                    )
                )
            }
        }
    }

    private companion object {
        const val PATH_INSTALLATIONS = "installations"
        const val PATH_EVENTS = "events"
    }
}

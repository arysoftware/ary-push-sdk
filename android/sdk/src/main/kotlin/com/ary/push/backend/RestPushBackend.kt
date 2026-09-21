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
import java.net.URLEncoder

/**
 * Maps push operations onto the ARY push API.
 *
 * This class is the only place in the SDK that knows the wire contract. It contains no HTTP
 * mechanics (that is [RestClient]) and no scheduling or retry policy (that is the sync queue).
 *
 * The API has exactly five endpoints:
 *
 * | Operation                  | Request                                           |
 * |----------------------------|---------------------------------------------------|
 * | Register device            | `POST /api/notifications/devices/register`        |
 * | Device token update        | `PUT  /api/notifications/devices/update`          |
 * | Subscribe / unsubscribe    | `PUT  /api/notifications/devices/toggle`          |
 * | Add segment subscriber     | `POST /api/segments/{segmentId}/subscribers`      |
 * | Segment list               | `GET  /api/segments/list`                         |
 *
 * `projectId` and the bearer token are added to every request by the transport.
 *
 * Operations with no endpoint -- user identity, tags, topics and events -- are answered locally
 * and never reach the network. They stay fully functional on the device; the server simply has
 * no API to receive them, and sending them anyway would produce a stream of 404s.
 *
 * @param installationProvider the current installation record. The update and toggle endpoints
 *   need fields their operations do not carry, so they are read from the live record.
 */
internal class RestPushBackend(
    private val client: RestClient,
    private val config: PushBackendConfig,
    private val installationProvider: () -> Installation
) : PushBackend {

    override suspend fun registerInstallation(installation: Installation): ApiResult<Unit> =
        client.post(PATH_REGISTER, installationPayload(installation), parser = IgnoreBody)

    override suspend fun updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ): ApiResult<Unit> {
        val current = installationProvider()
        return client.put(
            path = PATH_UPDATE,
            body = mapOf(
                "installationId" to installationId,
                "newToken" to token,
                "platform" to current.platform,
                "notificationsEnabled" to current.notificationsEnabled,
                "appVersion" to current.appVersion
            ),
            parser = IgnoreBody
        )
    }

    override suspend fun updateNotificationPermission(
        installationId: String,
        enabled: Boolean
    ): ApiResult<Unit> {
        // The toggle endpoint identifies the device by its push token. Before a token exists
        // there is nothing to address, and nothing is lost by waiting: the registration sent
        // once the token arrives carries notificationsEnabled itself.
        val token = installationProvider().pushToken
            ?: return ApiResult.Success(Unit, statusCode = NO_REQUEST)
        return client.put(
            path = PATH_TOGGLE,
            body = mapOf("token" to token, "notificationsEnabled" to enabled),
            parser = IgnoreBody
        )
    }

    override suspend fun subscribeToSegment(
        segmentId: String,
        installation: Installation
    ): ApiResult<Unit> = client.post(
        path = "$PATH_SEGMENTS/${encodePathSegment(segmentId)}/subscribers",
        body = installationPayload(installation),
        parser = IgnoreBody
    )

    override suspend fun getSegments(installationId: String): ApiResult<List<Segment>> =
        client.get(path = PATH_SEGMENT_LIST) { raw -> parseSegments(raw) }

    // ------------------------------------------------------------------ no endpoint

    override suspend fun identify(installationId: String, userId: String): ApiResult<Unit> =
        answeredLocally

    override suspend fun logout(installationId: String): ApiResult<Unit> = answeredLocally

    override suspend fun updateTags(
        installationId: String,
        tags: Map<String, String>
    ): ApiResult<Unit> = answeredLocally

    override suspend fun removeTags(
        installationId: String,
        keys: Set<String>,
        all: Boolean
    ): ApiResult<Unit> = answeredLocally

    override suspend fun updateTopics(
        installationId: String,
        topics: Set<String>
    ): ApiResult<Unit> = answeredLocally

    override suspend fun trackEvents(
        installationId: String,
        events: List<PushEvent>
    ): ApiResult<Unit> = answeredLocally

    override fun close() {
        client.close()
    }

    // ------------------------------------------------------------------ payloads

    /**
     * The installation payload shared by registration and segment subscription.
     *
     * The `device` block is omitted entirely, rather than sent empty, when the host disabled
     * device-information collection.
     */
    private fun installationPayload(installation: Installation): Map<String, Any?> =
        buildMap {
            put("token", installation.pushToken)
            put("platform", installation.platform)
            put("applicationId", installation.applicationId ?: config.applicationId)
            put("installationId", installation.id)
            put("provider", installation.provider.wireValue)
            put("appVersion", installation.appVersion)
            put("appBuild", installation.appBuild)
            put("sdkVersion", installation.sdkVersion)
            put("notificationsEnabled", installation.notificationsEnabled)
            val device = buildMap<String, Any?> {
                installation.osVersion?.let { put("osVersion", it) }
                installation.deviceModel?.let { put("deviceModel", it) }
                installation.locale?.let { put("locale", it) }
                installation.timezone?.let { put("timezone", it) }
            }
            if (device.isNotEmpty()) put("device", device)
        }

    /**
     * Parses the segment list.
     *
     * Accepts a bare array, or an object wrapping one under `segments` or `data`, because
     * gateways that wrap collection responses are common and no shape is worth failing over. An
     * entry without an id is skipped rather than failing the whole response.
     */
    private fun parseSegments(raw: String): List<Segment> {
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?: PushJson.parseObject(raw)?.let { it.optJSONArray("segments") ?: it.optJSONArray("data") }
            ?: return emptyList()

        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val json: JSONObject = array.optJSONObject(index) ?: continue
                val id = (json.optString("id").takeIf { it.isNotEmpty() }
                    ?: json.optString("segmentId").takeIf { it.isNotEmpty() })
                    ?: continue
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

    /** Percent-encodes a path segment. `URLEncoder` is form encoding, so `+` must become `%20`. */
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val PATH_REGISTER = "/api/notifications/devices/register"
        const val PATH_UPDATE = "/api/notifications/devices/update"
        const val PATH_TOGGLE = "/api/notifications/devices/toggle"
        const val PATH_SEGMENTS = "/api/segments"
        const val PATH_SEGMENT_LIST = "/api/segments/list"

        /** Status reported for an operation settled without a request. */
        const val NO_REQUEST = 204

        val answeredLocally: ApiResult<Unit> = ApiResult.Success(Unit, statusCode = NO_REQUEST)
    }
}

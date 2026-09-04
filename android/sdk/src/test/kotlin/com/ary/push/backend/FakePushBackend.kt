package com.ary.push.backend

import com.ary.push.api.ApiResult
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import com.ary.push.model.Segment

/**
 * Records backend calls and returns scripted results.
 *
 * Because the core depends on [PushBackend] rather than on HTTP, the whole synchronisation
 * state machine (ordering, retries, permanent failures, offline behaviour) can be tested
 * without a server.
 */
internal class FakePushBackend : PushBackend {

    val calls = mutableListOf<String>()

    /** Results are popped in order; the last one repeats once the script runs out. */
    private val scripted = ArrayDeque<ApiResult<Unit>>()

    var default: ApiResult<Unit> = ApiResult.Success(Unit, statusCode = 200)

    /** Segments handed back by [getSegments]. */
    var scriptedSegments: List<Segment> = emptyList()

    /** When set, the backend throws instead of returning, to prove the SDK survives it. */
    var throwOnCall: Throwable? = null

    fun script(vararg results: ApiResult<Unit>) {
        scripted.addAll(results)
    }

    private fun next(name: String): ApiResult<Unit> {
        calls += name
        throwOnCall?.let { throw it }
        return if (scripted.isEmpty()) default else scripted.removeFirst()
    }

    fun countOf(name: String): Int = calls.count { it == name }

    override suspend fun registerInstallation(installation: Installation) = next("register")

    override suspend fun updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ) = next("token:$token")

    override suspend fun identify(installationId: String, userId: String) = next("identify:$userId")

    override suspend fun logout(installationId: String) = next("logout")

    override suspend fun updateTags(installationId: String, tags: Map<String, String>) =
        next("tags:${tags.entries.joinToString(",") { "${it.key}=${it.value}" }}")

    override suspend fun removeTags(installationId: String, keys: Set<String>, all: Boolean) =
        next(if (all) "removeAllTags" else "removeTags:${keys.sorted().joinToString(",")}")

    override suspend fun updateTopics(installationId: String, topics: Set<String>) =
        next("topics:${topics.sorted().joinToString(",")}")

    override suspend fun updateNotificationPermission(installationId: String, enabled: Boolean) =
        next("permission:$enabled")

    override suspend fun getSegments(installationId: String): ApiResult<List<Segment>> {
        calls += "segments"
        throwOnCall?.let { throw it }
        return ApiResult.Success(scriptedSegments, statusCode = 200)
    }

    override suspend fun trackEvents(installationId: String, events: List<PushEvent>) =
        next("events:${events.size}")
}

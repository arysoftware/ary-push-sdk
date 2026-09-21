package com.ary.push.backend

import com.ary.push.api.ApiResult
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import com.ary.push.model.Segment

/**
 * The backend used when an application configures no server.
 *
 * Every operation succeeds immediately and does nothing. This is what makes the push half of the
 * SDK genuinely independent of the sync half: an application that only wants to receive and open
 * notifications never sees a network call, and the sync queue stays permanently empty.
 *
 * It is also the substitute installed when a REST backend was requested but could not be built,
 * so that a configuration mistake degrades to "no synchronisation" rather than to "no push".
 */
public object NoopPushBackend : PushBackend {

    private val ok = ApiResult.Success(Unit, statusCode = 200)

    override suspend fun registerInstallation(installation: Installation): ApiResult<Unit> = ok

    override suspend fun updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ): ApiResult<Unit> = ok

    override suspend fun identify(installationId: String, userId: String): ApiResult<Unit> = ok

    override suspend fun logout(installationId: String): ApiResult<Unit> = ok

    override suspend fun updateTags(
        installationId: String,
        tags: Map<String, String>
    ): ApiResult<Unit> = ok

    override suspend fun removeTags(
        installationId: String,
        keys: Set<String>,
        all: Boolean
    ): ApiResult<Unit> = ok

    override suspend fun updateTopics(
        installationId: String,
        topics: Set<String>
    ): ApiResult<Unit> = ok

    override suspend fun updateNotificationPermission(
        installationId: String,
        enabled: Boolean
    ): ApiResult<Unit> = ok

    override suspend fun getSegments(installationId: String): ApiResult<List<Segment>> =
        // No server, so no segments exist. An empty list is the truthful answer.
        ApiResult.Success(emptyList(), statusCode = 200)

    override suspend fun subscribeToSegment(
        segmentId: String,
        installation: Installation
    ): ApiResult<Unit> =
        // Unlike a tag, a segment subscription has no local meaning at all: it exists only on a
        // server. Reporting success here would tell the host it had subscribed when nothing did.
        ApiResult.Error(
            statusCode = null,
            code = "no_backend",
            message = "No backend is configured, so there is nothing to subscribe on"
        )

    override suspend fun trackEvents(
        installationId: String,
        events: List<PushEvent>
    ): ApiResult<Unit> = ok
}

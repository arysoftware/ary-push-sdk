package com.ary.push.backend

import com.ary.push.api.ApiResult
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import com.ary.push.model.Segment

/**
 * Everything the SDK needs from a server, expressed without any reference to HTTP.
 *
 * The notification engine depends on this interface, never on a transport. That keeps the core
 * testable, lets an application run with no server at all ([NoopPushBackend]), and lets a team
 * swap in a bespoke transport without touching push handling.
 *
 * Implementations must be safe to call concurrently and must never throw: every failure is
 * reported as an [ApiResult]. Every operation must be idempotent, because the sync queue will
 * retry it.
 */
public interface PushBackend {

    /**
     * Creates or refreshes the installation record.
     *
     * Must be idempotent on the installation id: repeated calls with unchanged data are
     * expected and must not create duplicates.
     */
    public suspend fun registerInstallation(installation: Installation): ApiResult<Unit>

    /** Replaces the push token held for an installation. */
    public suspend fun updateToken(
        installationId: String,
        token: String,
        provider: PushProvider
    ): ApiResult<Unit>

    /** Associates an installation with a user. A user may own many installations. */
    public suspend fun identify(installationId: String, userId: String): ApiResult<Unit>

    /**
     * Clears the user association.
     *
     * The installation itself, its token and its device registration survive: logging out of an
     * application must not unregister the device from push.
     */
    public suspend fun logout(installationId: String): ApiResult<Unit>

    /** Merges tag values into the installation record. */
    public suspend fun updateTags(
        installationId: String,
        tags: Map<String, String>
    ): ApiResult<Unit>

    /** Deletes the named tags. An empty [keys] set with [all] true clears every tag. */
    public suspend fun removeTags(
        installationId: String,
        keys: Set<String>,
        all: Boolean = false
    ): ApiResult<Unit>

    /** Reports the installation's current topic subscriptions. */
    public suspend fun updateTopics(
        installationId: String,
        topics: Set<String>
    ): ApiResult<Unit>

    /** Reports whether the OS currently permits notifications for this installation. */
    public suspend fun updateNotificationPermission(
        installationId: String,
        enabled: Boolean
    ): ApiResult<Unit>

    /**
     * Reads the segments the backend has computed for this installation.
     *
     * Read-only by design: the SDK reports tags and identity, the backend decides membership.
     * See [com.ary.push.model.Segment].
     */
    public suspend fun getSegments(installationId: String): ApiResult<List<Segment>>

    /** Submits a batch of push-related events. */
    public suspend fun trackEvents(
        installationId: String,
        events: List<PushEvent>
    ): ApiResult<Unit>

    /** Releases any transport resources held by this backend. */
    public fun close() {}
}

package com.ary.push.api

/**
 * Supplies the host application's access token to the SDK's REST client.
 *
 * The SDK never stores, refreshes or owns credentials: it asks the host application for a token
 * when it needs one. Implementations must be thread-safe and must return quickly, or suspend.
 *
 * When [getAccessToken] returns null the request is sent unauthenticated.
 */
public interface AuthProvider {

    /** Returns the current access token, or null when the user is not authenticated. */
    public suspend fun getAccessToken(): String?

    /**
     * Called once when the backend answers `401 Unauthorized`.
     *
     * Return true if a fresh token is now available, in which case the SDK retries the request
     * exactly once. Return false to fail the request. The SDK never loops on refresh.
     *
     * The default implementation performs no refresh.
     */
    public suspend fun refreshAccessToken(): Boolean = false

    /** Scheme prefixed to the token in the `Authorization` header. */
    public val scheme: String get() = "Bearer"
}

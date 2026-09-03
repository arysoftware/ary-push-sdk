package com.ary.push.api

/**
 * Transport contract used by every SDK component that talks to the push API.
 *
 * Deliberately free of push semantics: it knows about HTTP verbs, JSON, headers, timeouts and
 * retries, and nothing about installations, tokens or tags. Push business logic lives in
 * [com.ary.push.backend.PushBackend] implementations.
 *
 * Paths are relative to the configured base URL and API version, e.g. `installations`.
 * All methods are cancellation-aware: cancelling the calling coroutine cancels the HTTP call.
 */
public interface RestClient {

    public suspend fun <T> get(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        parser: (String) -> T
    ): ApiResult<T>

    public suspend fun <T> post(
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
        parser: (String) -> T
    ): ApiResult<T>

    public suspend fun <T> put(
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
        parser: (String) -> T
    ): ApiResult<T>

    public suspend fun <T> patch(
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
        parser: (String) -> T
    ): ApiResult<T>

    public suspend fun delete(
        path: String,
        query: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): ApiResult<Unit>

    /** Releases transport resources. Called when the SDK is torn down in tests. */
    public fun close()
}

/** Parser for endpoints whose response body is irrelevant. */
public val IgnoreBody: (String) -> Unit = { }

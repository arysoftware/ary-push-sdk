package com.ary.push.api

/**
 * Outcome of a single REST call.
 *
 * The SDK models transport failures ([NetworkError]) separately from server-reported failures
 * ([Error]) because only the former, plus a specific set of status codes, may be retried.
 */
public sealed class ApiResult<out T> {

    /** A 2xx response whose body parsed successfully. */
    public data class Success<T>(
        public val data: T,
        public val statusCode: Int,
        public val headers: Map<String, String> = emptyMap()
    ) : ApiResult<T>()

    /** A non-2xx response, or a 2xx response whose body could not be parsed. */
    public data class Error(
        public val statusCode: Int?,
        public val code: String? = null,
        public val message: String? = null,
        public val details: String? = null,
        /** Server-supplied `Retry-After` in milliseconds, when present and parseable. */
        public val retryAfterMs: Long? = null
    ) : ApiResult<Nothing>()

    /** The request never produced a response: DNS, TLS, timeout, connection reset, cancellation. */
    public data class NetworkError(
        public val exception: Throwable
    ) : ApiResult<Nothing>()

    public val isSuccess: Boolean get() = this is Success

    /** The parsed body, or null for any failure. */
    public fun getOrNull(): T? = (this as? Success)?.data

    /** Maps a successful body, leaving failures untouched. */
    public inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data), statusCode, headers)
        is Error -> this
        is NetworkError -> this
    }

    /**
     * Whether re-sending this exact request could plausibly succeed.
     *
     * Transient by definition: 408, 429 and 5xx, plus every transport failure. Permanent client
     * errors (400, 401, 403, 404, 409, 422) are not retried here; 401 has its own single-shot
     * refresh path in the REST client.
     */
    public val isRetryable: Boolean
        get() = when (this) {
            is Success -> false
            is NetworkError -> true
            is Error -> when (statusCode) {
                null -> true
                408, 425, 429 -> true
                in 500..599 -> true
                else -> false
            }
        }
}

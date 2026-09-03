package com.ary.push.api

import com.ary.push.NetworkConfig
import com.ary.push.PushBackendConfig
import com.ary.push.RetryConfig
import com.ary.push.internal.PushJson
import com.ary.push.internal.device.DeviceInfoProvider
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.sync.RetryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The SDK's REST transport.
 *
 * Responsibilities stop at HTTP: URLs, headers, JSON, timeouts, cancellation, retry classification
 * and a single authenticated retry after a 401. It knows nothing about installations or tags, so
 * push logic can be tested without a socket and swapped without touching the notification engine.
 *
 * TLS is deliberately left at OkHttp's defaults. The SDK never installs a permissive trust
 * manager, never disables hostname verification and never ignores certificate errors: an SDK that
 * ships an SSL bypass ships it to every application that embeds it.
 */
internal class OkHttpRestClient(
    private val backendConfig: PushBackendConfig,
    networkConfig: NetworkConfig,
    retryConfig: RetryConfig,
    private val authProvider: AuthProvider?,
    private val device: DeviceInfoProvider,
    private val installationIdProvider: () -> String?,
    client: OkHttpClient? = null
) : RestClient {

    private val retryManager = RetryManager(retryConfig)

    private val ownsClient = client == null

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(networkConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(networkConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(networkConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(networkConfig.callTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    init {
        if (backendConfig.isPlaintext) {
            PushLogger.w {
                "Backend base URL uses plaintext HTTP. This is acceptable only for local " +
                    "development; production traffic must use HTTPS."
            }
        }
    }

    override suspend fun <T> get(
        path: String,
        query: Map<String, Any?>,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> = send("GET", path, query, null, headers, parser)

    override suspend fun <T> post(
        path: String,
        body: Any?,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> = send("POST", path, emptyMap(), body, headers, parser)

    override suspend fun <T> put(
        path: String,
        body: Any?,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> = send("PUT", path, emptyMap(), body, headers, parser)

    override suspend fun <T> patch(
        path: String,
        body: Any?,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> = send("PATCH", path, emptyMap(), body, headers, parser)

    override suspend fun delete(
        path: String,
        query: Map<String, Any?>,
        headers: Map<String, String>
    ): ApiResult<Unit> = send("DELETE", path, query, null, headers, IgnoreBody)

    override fun close() {
        if (!ownsClient) return
        runCatching {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            http.cache?.close()
        }
    }

    // ------------------------------------------------------------------ request pipeline

    private suspend fun <T> send(
        method: String,
        path: String,
        query: Map<String, Any?>,
        body: Any?,
        extraHeaders: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> {
        val url = buildUrl(path, query)
            ?: return ApiResult.Error(
                statusCode = null,
                code = "invalid_url",
                message = "Could not build a URL from base=${backendConfig.normalizedBaseUrl} path=$path"
            )

        val requestId = UUID.randomUUID().toString()
        var attempt = 1
        var refreshedOnce = false

        while (true) {
            // The auth header is rebuilt on every attempt so that a token refreshed between
            // attempts is actually used.
            val headers = buildHeaders(extraHeaders, requestId, authToken())
            val request = Request.Builder()
                .url(url)
                .method(method, body.toRequestBodyOrNull(method))
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()

            PushLogger.d {
                "$method ${url.encodedPath} attempt=$attempt/${retryManager.maxAttempts} " +
                    "requestId=$requestId headers=${PushLogger.safeHeaders(headers)}"
            }

            val result: ApiResult<T> = try {
                http.newCall(request).await().use { response -> response.toApiResult(parser) }
            } catch (e: CancellationException) {
                // Cancellation is the caller's decision, never a transport failure. The call has
                // already been cancelled by invokeOnCancellation, so no state is left in flight.
                throw e
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            } catch (t: Throwable) {
                ApiResult.NetworkError(t)
            }

            // A single, non-recursive refresh attempt. It deliberately does not consume a retry
            // attempt, and it can only ever happen once per request.
            if (result is ApiResult.Error && result.statusCode == 401 &&
                !refreshedOnce && authProvider != null
            ) {
                refreshedOnce = true
                val refreshed = runCatching { authProvider?.refreshAccessToken() ?: false }
                    .getOrDefault(false)
                PushLogger.d { "Received 401; token refresh ${if (refreshed) "succeeded" else "failed"}" }
                if (refreshed) continue
            }

            if (!result.isRetryable || !retryManager.canRetry(attempt)) {
                logOutcome(method, url, requestId, attempt, result)
                return result
            }

            val retryAfter = (result as? ApiResult.Error)?.retryAfterMs
            val waitMs = retryManager.delayMillis(attempt, retryAfter)
            PushLogger.d { "Retrying $method ${url.encodedPath} in ${waitMs}ms" }
            delay(waitMs)
            attempt++
        }
    }

    private fun logOutcome(
        method: String,
        url: HttpUrl,
        requestId: String,
        attempts: Int,
        result: ApiResult<*>
    ) {
        when (result) {
            is ApiResult.Success -> PushLogger.i {
                "$method ${url.encodedPath} -> ${result.statusCode} (requestId=$requestId)"
            }

            is ApiResult.Error -> PushLogger.w {
                "$method ${url.encodedPath} -> ${result.statusCode} ${result.code.orEmpty()} " +
                    "after $attempts attempt(s) (requestId=$requestId)"
            }

            is ApiResult.NetworkError -> PushLogger.w(result.exception) {
                "$method ${url.encodedPath} failed after $attempts attempt(s) (requestId=$requestId)"
            }
        }
    }

    // ------------------------------------------------------------------ request construction

    private fun buildUrl(path: String, query: Map<String, Any?>): HttpUrl? {
        val base = "${backendConfig.normalizedBaseUrl}/${backendConfig.apiVersion}/" +
            path.trimStart('/')
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return null
        query.forEach { (key, value) ->
            if (value != null) builder.addQueryParameter(key, value.toString())
        }
        return builder.build()
    }

    private suspend fun authToken(): String? = try {
        authProvider?.getAccessToken()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // A failing AuthProvider must degrade to an unauthenticated request, not to a crash
        // inside whichever host coroutine happened to trigger synchronisation.
        PushLogger.w(t) { "AuthProvider.getAccessToken() failed; sending request unauthenticated" }
        null
    }

    private fun buildHeaders(
        extra: Map<String, String>,
        requestId: String,
        token: String?
    ): Map<String, String> {
        val names = backendConfig.headerNames
        val headers = LinkedHashMap<String, String>(12)

        headers["Accept"] = JSON_MEDIA_TYPE
        headers[names.sdkVersion] = device.sdkVersion
        headers[names.platform] = PLATFORM
        headers[names.requestId] = requestId
        device.appVersion?.let { headers[names.appVersion] = it }
        backendConfig.applicationId?.let { headers[names.applicationId] = it }
        installationIdProvider()?.let { headers[names.installationId] = it }

        headers.putAll(backendConfig.defaultHeaders)
        headers.putAll(extra)

        if (token != null && token.isNotBlank()) {
            val scheme = authProvider?.scheme?.takeIf { it.isNotBlank() } ?: "Bearer"
            headers[names.authorization] = "$scheme $token"
        }
        return headers
    }

    private fun Any?.toRequestBodyOrNull(method: String): RequestBody? {
        val requiresBody = method == "POST" || method == "PUT" || method == "PATCH"
        if (this == null) {
            // OkHttp requires a body for these verbs; an empty JSON object is the least
            // surprising thing to send.
            return if (requiresBody) "{}".toRequestBody(JSON_MEDIA_TYPE.toMediaType()) else null
        }
        return PushJson.encode(this).toRequestBody(JSON_MEDIA_TYPE.toMediaType())
    }

    // ------------------------------------------------------------------ response handling

    private fun <T> Response.toApiResult(parser: (String) -> T): ApiResult<T> {
        val raw = runCatching { body?.string().orEmpty() }.getOrDefault("")

        if (!isSuccessful) {
            val parsed = PushJson.parseObject(raw)
            val errorObject = parsed?.optJSONObject("error") ?: parsed
            return ApiResult.Error(
                statusCode = code,
                code = errorObject?.optString("code")?.takeIf { it.isNotEmpty() },
                message = errorObject?.optString("message")?.takeIf { it.isNotEmpty() }
                    ?: message.takeIf { it.isNotEmpty() },
                details = raw.take(MAX_LOGGED_ERROR_BODY).takeIf { it.isNotEmpty() },
                retryAfterMs = HttpHeaderParsing.parseRetryAfterMillis(header("Retry-After"))
            )
        }

        return try {
            ApiResult.Success(parser(raw), code, headers.toMap())
        } catch (t: Throwable) {
            // A 2xx the SDK cannot understand is a contract violation, not a transient fault:
            // reported as an error so the queue drops it instead of retrying forever.
            ApiResult.Error(
                statusCode = code,
                code = "invalid_response_body",
                message = "Could not parse a ${code} response: ${t.message}"
            )
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> =
        (0 until size).associate { index -> name(index) to value(index) }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val PLATFORM = "android"
        const val MAX_LOGGED_ERROR_BODY = 512
    }
}

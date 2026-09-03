package com.ary.push.backend

import com.ary.push.api.ApiResult
import com.ary.push.api.RestClient

/**
 * Records requests instead of sending them.
 *
 * Having the backend depend on [RestClient] rather than on HTTP is what makes this possible:
 * the whole wire contract can be asserted with no socket, no server and no flakiness.
 */
internal class FakeRestClient : RestClient {

    data class Call(
        val method: String,
        val path: String,
        val body: Any? = null,
        val query: Map<String, Any?> = emptyMap()
    )

    val calls = mutableListOf<Call>()

    /** Result handed back for the next call, so failure paths can be exercised too. */
    var nextResult: ApiResult<Any?> = ApiResult.Success(Unit, statusCode = 200)

    var closed: Boolean = false
        private set

    @Suppress("UNCHECKED_CAST")
    private fun <T> respond(): ApiResult<T> = nextResult as ApiResult<T>

    override suspend fun <T> get(
        path: String,
        query: Map<String, Any?>,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> {
        calls += Call("GET", path, query = query)
        return respond()
    }

    override suspend fun <T> post(
        path: String,
        body: Any?,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> {
        calls += Call("POST", path, body)
        return respond()
    }

    override suspend fun <T> put(
        path: String,
        body: Any?,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> {
        calls += Call("PUT", path, body)
        return respond()
    }

    override suspend fun <T> patch(
        path: String,
        body: Any?,
        headers: Map<String, String>,
        parser: (String) -> T
    ): ApiResult<T> {
        calls += Call("PATCH", path, body)
        return respond()
    }

    override suspend fun delete(
        path: String,
        query: Map<String, Any?>,
        headers: Map<String, String>
    ): ApiResult<Unit> {
        calls += Call("DELETE", path, query = query)
        return respond()
    }

    override fun close() {
        closed = true
    }

    fun only(): Call = calls.single()

    @Suppress("UNCHECKED_CAST")
    fun Call.bodyMap(): Map<String, Any?> = body as Map<String, Any?>
}

package com.ary.push.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ApiResultTest {

    @Test
    fun `success carries the parsed body and status`() {
        val result = ApiResult.Success("body", statusCode = 201)

        assertTrue(result.isSuccess)
        assertEquals("body", result.getOrNull())
        assertFalse(result.isRetryable)
    }

    @Test
    fun `transient statuses are retryable`() {
        // These are the only server answers that mean "the same request may work later".
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { status ->
            assertTrue("$status should be retryable", ApiResult.Error(status).isRetryable)
        }
    }

    @Test
    fun `permanent client errors are not retryable`() {
        // Retrying these forever is how a queue wedges itself and never drains again.
        listOf(400, 401, 403, 404, 409, 422).forEach { status ->
            assertFalse("$status should not be retryable", ApiResult.Error(status).isRetryable)
        }
    }

    @Test
    fun `transport failures are always retryable`() {
        assertTrue(ApiResult.NetworkError(IOException("connection reset")).isRetryable)
        assertTrue(ApiResult.Error(statusCode = null).isRetryable)
    }

    @Test
    fun `map transforms a success and passes failures through untouched`() {
        val mapped = ApiResult.Success(2, statusCode = 200).map { it * 21 }
        assertEquals(42, mapped.getOrNull())

        val error: ApiResult<Int> = ApiResult.Error(500, code = "boom")
        val mappedError = error.map { it * 2 }
        assertNull(mappedError.getOrNull())
        assertEquals("boom", (mappedError as ApiResult.Error).code)
    }
}

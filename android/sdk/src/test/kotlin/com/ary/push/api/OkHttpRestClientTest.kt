package com.ary.push.api

import com.ary.push.NetworkConfig
import com.ary.push.PushBackendConfig
import com.ary.push.RetryConfig
import com.ary.push.internal.device.DeviceInfoProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OkHttpRestClientTest {

    private lateinit var server: MockWebServer
    private lateinit var device: DeviceInfoProvider

    /** Fast, jitter-free retries so tests assert behaviour rather than wait on backoff. */
    private val retry = RetryConfig(maxAttempts = 3, initialBackoffMs = 1, jitterFactor = 0.0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        device = DeviceInfoProvider(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        auth: AuthProvider? = null,
        applicationId: String? = "wallet_android",
        projectId: String? = null,
        authToken: String? = null
    ) = OkHttpRestClient(
        backendConfig = PushBackendConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            applicationId = applicationId,
            projectId = projectId,
            authToken = authToken
        ),
        networkConfig = NetworkConfig(),
        retryConfig = retry,
        authProvider = auth,
        device = device,
        installationIdProvider = { "install-1" }
    )

    @Test
    fun `a successful response is parsed and reported with its status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))

        val result = client().post("installations", mapOf("a" to 1)) { it }

        assertTrue(result is ApiResult.Success)
        assertEquals(201, (result as ApiResult.Success).statusCode)
        assertEquals("""{"ok":true}""", result.data)
    }

    @Test
    fun `requests are sent to the versioned path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client().post("installations", mapOf("a" to 1), parser = IgnoreBody)

        assertEquals("/v1/installations", server.takeRequest().path)
    }

    @Test
    fun `an absolute path is resolved from the base URL with no version prefix`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client().post("/api/notifications/devices/register", null, parser = IgnoreBody)

        assertEquals("/api/notifications/devices/register", server.takeRequest().path)
    }

    @Test
    fun `projectId is added to every request, whatever the verb`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("[]")) }
        val client = client(projectId = "proj-42")

        client.post("/api/notifications/devices/register", mapOf("a" to 1), parser = IgnoreBody)
        client.put("/api/notifications/devices/update", mapOf("a" to 1), parser = IgnoreBody)
        client.get("/api/segments/list") { it }

        assertEquals("/api/notifications/devices/register?projectId=proj-42", server.takeRequest().path)
        assertEquals("/api/notifications/devices/update?projectId=proj-42", server.takeRequest().path)
        assertEquals("/api/segments/list?projectId=proj-42", server.takeRequest().path)
    }

    @Test
    fun `projectId coexists with a request's own query parameters`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        client(projectId = "proj-42").get("/api/segments/list", query = mapOf("page" to 2)) { it }

        val url = server.takeRequest().requestUrl!!
        assertEquals("proj-42", url.queryParameter("projectId"))
        assertEquals("2", url.queryParameter("page"))
    }

    @Test
    fun `no projectId parameter is sent when none is configured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client().post("/api/notifications/devices/register", null, parser = IgnoreBody)

        assertNull(server.takeRequest().requestUrl!!.queryParameter("projectId"))
    }

    @Test
    fun `a static authToken is sent as a bearer token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client(authToken = "static-token").post("/api/x", null, parser = IgnoreBody)

        assertEquals("Bearer static-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `an AuthProvider wins over the static token, because it can refresh`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val provider = object : AuthProvider {
            override suspend fun getAccessToken(): String = "dynamic-token"
        }

        client(auth = provider, authToken = "static-token").post("/api/x", null, parser = IgnoreBody)

        assertEquals("Bearer dynamic-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the static token covers an AuthProvider that has nothing to offer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val provider = object : AuthProvider {
            override suspend fun getAccessToken(): String? = null
        }

        client(auth = provider, authToken = "static-token").post("/api/x", null, parser = IgnoreBody)

        assertEquals("Bearer static-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the backend configuration never prints its bearer token`() {
        val config = PushBackendConfig(baseUrl = "https://push.example", authToken = "secret-value")

        assertTrue("token must be masked", !config.toString().contains("secret-value"))
        assertTrue(config.toString().contains("authToken=***"))
    }

    @Test
    fun `identifying headers are attached to every request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client().post("installations", null, parser = IgnoreBody)

        val request = server.takeRequest()
        assertEquals("android", request.getHeader("X-Platform"))
        assertEquals("wallet_android", request.getHeader("X-Application-Id"))
        assertEquals("install-1", request.getHeader("X-Installation-Id"))
        assertEquals("application/json", request.getHeader("Accept")?.substringBefore(';'))
        assertTrue(request.getHeader("X-Request-ID")!!.isNotBlank())
    }

    @Test
    fun `no Authorization header is sent when no AuthProvider is configured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client().post("installations", null, parser = IgnoreBody)

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a configured AuthProvider supplies a bearer token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = object : AuthProvider {
            override suspend fun getAccessToken(): String = "abc123"
        }

        client(auth).post("installations", null, parser = IgnoreBody)

        assertEquals("Bearer abc123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `query parameters are appended`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client().delete("installations/install-1/tags", query = mapOf("keys" to "a,b"))

        assertEquals("/v1/installations/install-1/tags?keys=a%2Cb", server.takeRequest().path)
    }

    @Test
    fun `a permanent client error is returned without retrying`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"error":{"code":"invalid_token","message":"bad token"}}""")
        )

        val result = client().post("installations", null, parser = IgnoreBody)

        assertTrue(result is ApiResult.Error)
        assertEquals(422, (result as ApiResult.Error).statusCode)
        assertEquals("invalid_token", result.code)
        assertEquals("bad token", result.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a transient failure is retried until it succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client().post("installations", null, parser = IgnoreBody)

        assertTrue(result.isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retries stop at the configured attempt limit`() = runTest {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(500)) }

        val result = client().post("installations", null, parser = IgnoreBody)

        assertTrue(result is ApiResult.Error)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a Retry-After header is honoured and the request eventually succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = client().post("installations", null, parser = IgnoreBody)

        assertTrue(result.isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 401 triggers exactly one refresh and one retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        var refreshes = 0
        var token = "stale"
        val auth = object : AuthProvider {
            override suspend fun getAccessToken(): String = token
            override suspend fun refreshAccessToken(): Boolean {
                refreshes++
                token = "fresh"
                return true
            }
        }

        val result = client(auth).post("installations", null, parser = IgnoreBody)

        assertTrue(result.isSuccess)
        assertEquals(1, refreshes)
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a repeated 401 does not loop forever`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(401)) }

        val auth = object : AuthProvider {
            override suspend fun getAccessToken(): String = "token"
            override suspend fun refreshAccessToken(): Boolean = true
        }

        val result = client(auth).post("installations", null, parser = IgnoreBody)

        assertTrue(result is ApiResult.Error)
        // The original request plus exactly one post-refresh retry: 401 is not retryable, and
        // the refresh path is single-shot by construction.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an AuthProvider that throws degrades to an unauthenticated request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val auth = object : AuthProvider {
            override suspend fun getAccessToken(): String = error("keystore unavailable")
        }

        val result = client(auth).post("installations", null, parser = IgnoreBody)

        assertTrue(result.isSuccess)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a 2xx body the SDK cannot parse is a permanent error, not a retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not a number"))

        val result = client().post("installations", null) { it.toInt() }

        assertTrue(result is ApiResult.Error)
        assertEquals("invalid_response_body", (result as ApiResult.Error).code)
        assertEquals(1, server.requestCount)
    }
}

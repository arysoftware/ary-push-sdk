package com.ary.push.backend

import com.ary.push.PushBackendConfig
import com.ary.push.api.ApiResult
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RestPushBackendTest {

    private lateinit var client: FakeRestClient
    private lateinit var backend: RestPushBackend

    private val config = PushBackendConfig(
        baseUrl = "https://push-api.ary.com",
        applicationId = "wallet_android"
    )

    private val installation = Installation(
        id = "install-1",
        applicationId = "wallet_android",
        platform = "android",
        provider = PushProvider.FCM,
        pushToken = "token-1",
        userId = "USER_123",
        appVersion = "5.2.0",
        appBuild = "520",
        sdkVersion = "1.0.0",
        osVersion = "14",
        deviceModel = "Google Pixel 8",
        locale = "en-PK",
        timezone = "Asia/Karachi",
        notificationsEnabled = true
    )

    @Before
    fun setUp() {
        client = FakeRestClient()
        backend = RestPushBackend(client, config)
    }

    @Test
    fun `registration posts the documented body to the versioned collection`() = runTest {
        backend.registerInstallation(installation)

        val call = client.only()
        assertEquals("POST", call.method)
        assertEquals("installations", call.path)

        @Suppress("UNCHECKED_CAST")
        val body = call.body as Map<String, Any?>
        assertEquals("install-1", body["installationId"])
        assertEquals("wallet_android", body["applicationId"])
        assertEquals("android", body["platform"])
        assertEquals("fcm", body["provider"])
        assertEquals("token-1", body["pushToken"])
        assertEquals("USER_123", body["userId"])
        assertEquals("1.0.0", body["sdkVersion"])
        assertEquals(true, body["notificationsEnabled"])

        @Suppress("UNCHECKED_CAST")
        val device = body["device"] as Map<String, Any?>
        assertEquals("Asia/Karachi", device["timezone"])
    }

    @Test
    fun `the device block is omitted entirely when nothing was collected`() = runTest {
        backend.registerInstallation(
            installation.copy(osVersion = null, deviceModel = null, locale = null, timezone = null)
        )

        @Suppress("UNCHECKED_CAST")
        val body = client.only().body as Map<String, Any?>
        assertTrue("device must be absent, not empty", !body.containsKey("device"))
    }

    @Test
    fun `token updates PUT to the installation token resource`() = runTest {
        backend.updateToken("install-1", "token-2", PushProvider.FCM)

        val call = client.only()
        assertEquals("PUT", call.method)
        assertEquals("installations/install-1/token", call.path)
        assertEquals(mapOf("token" to "token-2", "provider" to "fcm"), call.body)
    }

    @Test
    fun `identify posts the user association`() = runTest {
        backend.identify("install-1", "USER_9")

        val call = client.only()
        assertEquals("POST", call.method)
        assertEquals("installations/install-1/identify", call.path)
        assertEquals(mapOf("userId" to "USER_9"), call.body)
    }

    @Test
    fun `logout deletes only the user association`() = runTest {
        backend.logout("install-1")

        val call = client.only()
        assertEquals("DELETE", call.method)
        // Not /installations/install-1: the device registration and token must survive.
        assertEquals("installations/install-1/user", call.path)
    }

    @Test
    fun `tags are merged with PATCH`() = runTest {
        backend.updateTags("install-1", mapOf("subscription" to "premium"))

        val call = client.only()
        assertEquals("PATCH", call.method)
        assertEquals("installations/install-1/tags", call.path)
        assertEquals(mapOf("tags" to mapOf("subscription" to "premium")), call.body)
    }

    @Test
    fun `removing named tags sends them as a query parameter`() = runTest {
        backend.removeTags("install-1", setOf("a", "b"), all = false)

        val call = client.only()
        assertEquals("DELETE", call.method)
        assertEquals("installations/install-1/tags", call.path)
        assertEquals("a,b", call.query["keys"])
    }

    @Test
    fun `removing all tags sends the all flag`() = runTest {
        backend.removeTags("install-1", emptySet(), all = true)

        assertEquals(true, client.only().query["all"])
    }

    @Test
    fun `removing an empty key set makes no request at all`() = runTest {
        val result = backend.removeTags("install-1", emptySet(), all = false)

        assertTrue(result.isSuccess)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `an empty event batch makes no request`() = runTest {
        val result = backend.trackEvents("install-1", emptyList())

        assertTrue(result.isSuccess)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `events are batched into one request`() = runTest {
        backend.trackEvents(
            "install-1",
            listOf(
                PushEvent("notification_received", mapOf("notificationId" to "n1")),
                PushEvent("notification_opened", mapOf("notificationId" to "n1"))
            )
        )

        val call = client.only()
        assertEquals("POST", call.method)
        assertEquals("events", call.path)

        @Suppress("UNCHECKED_CAST")
        val events = (call.body as Map<String, Any?>)["events"] as List<Map<String, Any?>>
        assertEquals(2, events.size)
        assertEquals("notification_received", events[0]["name"])
    }

    @Test
    fun `a backend failure is reported rather than thrown`() = runTest {
        client.nextResult = ApiResult.Error(statusCode = 503, code = "unavailable")

        val result = backend.registerInstallation(installation)

        assertTrue(result.isRetryable)
        assertEquals(503, (result as ApiResult.Error).statusCode)
    }

    @Test
    fun `closing the backend closes its transport`() {
        backend.close()

        assertTrue(client.closed)
    }
}

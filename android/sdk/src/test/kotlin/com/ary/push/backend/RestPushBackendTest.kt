package com.ary.push.backend

import com.ary.push.PushBackendConfig
import com.ary.push.api.ApiResult
import com.ary.push.model.Installation
import com.ary.push.model.PushEvent
import com.ary.push.model.PushProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RestPushBackendTest {

    private lateinit var client: FakeRestClient
    private lateinit var backend: RestPushBackend

    private val config = PushBackendConfig(
        baseUrl = "https://push-api.ary.com",
        applicationId = "wallet_android",
        projectId = "proj-42"
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

    /** What the backend reads as the live record; replaced by tests that need a variant. */
    private var current: Installation = installation

    @Before
    fun setUp() {
        client = FakeRestClient()
        current = installation
        backend = RestPushBackend(client, config) { current }
    }

    @Suppress("UNCHECKED_CAST")
    private fun FakeRestClient.Call.map(): Map<String, Any?> = body as Map<String, Any?>

    // ------------------------------------------------------------------ 1. register

    @Test
    fun `registration posts the full installation payload`() = runTest {
        backend.registerInstallation(installation)

        val call = client.only()
        assertEquals("POST", call.method)
        assertEquals("/api/notifications/devices/register", call.path)

        val body = call.map()
        assertEquals("token-1", body["token"])
        assertEquals("android", body["platform"])
        assertEquals("wallet_android", body["applicationId"])
        assertEquals("install-1", body["installationId"])
        assertEquals("fcm", body["provider"])
        assertEquals("5.2.0", body["appVersion"])
        assertEquals("520", body["appBuild"])
        assertEquals("1.0.0", body["sdkVersion"])
        assertEquals(true, body["notificationsEnabled"])

        @Suppress("UNCHECKED_CAST")
        val device = body["device"] as Map<String, Any?>
        assertEquals("14", device["osVersion"])
        assertEquals("Google Pixel 8", device["deviceModel"])
        assertEquals("en-PK", device["locale"])
        assertEquals("Asia/Karachi", device["timezone"])
    }

    @Test
    fun `registration sends exactly the documented fields`() = runTest {
        backend.registerInstallation(installation)

        assertEquals(
            setOf(
                "token", "platform", "applicationId", "installationId", "provider",
                "appVersion", "appBuild", "sdkVersion", "notificationsEnabled", "device"
            ),
            client.only().map().keys
        )
    }

    @Test
    fun `the device block is omitted entirely when nothing was collected`() = runTest {
        backend.registerInstallation(
            installation.copy(osVersion = null, deviceModel = null, locale = null, timezone = null)
        )

        assertFalse("device must be absent, not empty", client.only().map().containsKey("device"))
    }

    @Test
    fun `the configured applicationId stands in when the record has none`() = runTest {
        backend.registerInstallation(installation.copy(applicationId = null))

        assertEquals("wallet_android", client.only().map()["applicationId"])
    }

    // ------------------------------------------------------------------ 2. token update

    @Test
    fun `a token update PUTs the new token with the live device state`() = runTest {
        current = installation.copy(notificationsEnabled = false, appVersion = "5.3.0")

        backend.updateToken("install-1", "token-2", PushProvider.FCM)

        val call = client.only()
        assertEquals("PUT", call.method)
        assertEquals("/api/notifications/devices/update", call.path)
        assertEquals(
            mapOf(
                "installationId" to "install-1",
                "newToken" to "token-2",
                "platform" to "android",
                "notificationsEnabled" to false,
                "appVersion" to "5.3.0"
            ),
            call.map()
        )
    }

    // ------------------------------------------------------------------ 3. toggle

    @Test
    fun `a permission change PUTs the toggle keyed by push token`() = runTest {
        backend.updateNotificationPermission("install-1", enabled = false)

        val call = client.only()
        assertEquals("PUT", call.method)
        assertEquals("/api/notifications/devices/toggle", call.path)
        assertEquals(mapOf("token" to "token-1", "notificationsEnabled" to false), call.map())
    }

    @Test
    fun `a permission change before any token exists makes no request`() = runTest {
        current = installation.copy(pushToken = null)

        val result = backend.updateNotificationPermission("install-1", enabled = true)

        assertTrue(result.isSuccess)
        assertTrue(client.calls.isEmpty())
    }

    // ------------------------------------------------------------------ 4. segment subscriber

    @Test
    fun `subscribing to a segment posts the full installation payload`() = runTest {
        backend.subscribeToSegment("seg_premium", installation)

        val call = client.only()
        assertEquals("POST", call.method)
        assertEquals("/api/segments/seg_premium/subscribers", call.path)
        assertEquals("install-1", call.map()["installationId"])
        assertEquals("token-1", call.map()["token"])
    }

    @Test
    fun `a segment id is percent-encoded as a path segment`() = runTest {
        backend.subscribeToSegment("premium users/pk", installation)

        assertEquals("/api/segments/premium%20users%2Fpk/subscribers", client.only().path)
    }

    // ------------------------------------------------------------------ 5. segment list

    @Test
    fun `the segment list is read from the project collection`() = runTest {
        backend.getSegments("install-1")

        val call = client.only()
        assertEquals("GET", call.method)
        assertEquals("/api/segments/list", call.path)
    }

    // ------------------------------------------------------------------ no endpoint

    @Test
    fun `operations with no endpoint succeed locally and send nothing`() = runTest {
        val results = listOf(
            backend.identify("install-1", "USER_9"),
            backend.logout("install-1"),
            backend.updateTags("install-1", mapOf("a" to "b")),
            backend.removeTags("install-1", setOf("a"), all = false),
            backend.updateTopics("install-1", setOf("news")),
            backend.trackEvents("install-1", listOf(PushEvent("notification_opened")))
        )

        assertTrue("every one must settle as a success", results.all { it.isSuccess })
        assertTrue("none may reach the network", client.calls.isEmpty())
    }

    // ------------------------------------------------------------------ failures

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

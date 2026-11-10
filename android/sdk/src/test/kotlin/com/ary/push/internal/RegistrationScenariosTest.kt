package com.ary.push.internal

import android.os.Looper
import com.ary.push.ARYPushConfig
import com.ary.push.PushBackendConfig
import com.ary.push.RetryConfig
import com.ary.push.internal.storage.StorageManager
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.Locale
import java.util.TimeZone

/**
 * End-to-end registration scenarios through the real [PushCore]: storage, queue, sync manager,
 * OkHttp transport and a mock push server.
 *
 * Each "launch" tears the singleton down and starts it again over the same on-disk storage, which
 * is exactly what a process restart does. A Flutter launch is modelled the way the plugin behaves:
 * an early start with no configuration (the manifest has none), then the app's own configuration
 * a moment later.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegistrationScenariosTest {

    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** Status the server answers register with; everything else gets 200. */
    @Volatile
    private var registerStatus = 201

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var savedTimeZone: TimeZone
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedTimeZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Karachi"))
        Locale.setDefault(Locale.forLanguageTag("en-PK"))

        // Robolectric starts with no validated network; the SDK would (correctly) hold everything.
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, capabilities)

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val status = if (request.path.orEmpty().contains("/devices/register")) registerStatus else 200
                return MockResponse().setResponseCode(status).setBody("{}")
            }
        }
        server.start()
        PushCore.resetForTesting()
        StorageManager(context).clearAll()
    }

    @After
    fun tearDown() {
        PushCore.resetForTesting()
        server.shutdown()
        TimeZone.setDefault(savedTimeZone)
        Locale.setDefault(savedLocale)
    }

    // ------------------------------------------------------------------ helpers

    private fun config(
        baseUrl: String = server.url("/").toString(),
        projectId: String = "project-a",
        applicationId: String = "Sahulat",
        authToken: String = "token-v1"
    ) = ARYPushConfig(
        backend = PushBackendConfig(
            baseUrl = baseUrl,
            applicationId = applicationId,
            projectId = projectId,
            authToken = authToken
        ),
        retry = RetryConfig(maxAttempts = 2, initialBackoffMs = 20, maxBackoffMs = 50, jitterFactor = 0.0)
    )

    /** A cold start of a Flutter app: plugin start without configuration, then Dart's. */
    private fun flutterLaunch(appConfig: ARYPushConfig = config()): PushCore {
        PushCore.resetForTesting()
        PushCore.initialize(context, null, explicit = true)   // plugin: ARYPush.initialize(context), manifest has nothing
        settle()
        val core = PushCore.initialize(context, appConfig, explicit = true) // splash initialize(config)
        settle()
        return core
    }

    /** A cold start of a native app calling initialize(context, config) in Application.onCreate. */
    private fun nativeLaunch(appConfig: ARYPushConfig = config()): PushCore {
        PushCore.resetForTesting()
        val core = PushCore.initialize(context, appConfig, explicit = true)
        settle()
        return core
    }

    /** Waits until no request has arrived for a while: the SDK's work happens on its own threads. */
    private fun settle(quietMs: Long = 700, maxMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + maxMs
        var lastCount = -1
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val count = requests.size
            if (count != lastCount) {
                lastCount = count
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMs) {
                return
            }
            Thread.sleep(25)
        }
    }

    private fun registers(): List<RecordedRequest> =
        synchronized(requests) { requests.filter { it.path.orEmpty().contains("/devices/register") } }

    private fun registerCount() = registers().size

    private fun installationIdOf(request: RecordedRequest): String =
        Regex("\"installationId\"\\s*:\\s*\"([^\"]+)\"").find(request.body.clone().readUtf8())!!.groupValues[1]

    // ------------------------------------------------------------------ Flutter scenarios

    @Test
    fun `S01 flutter first launch after install registers once`() {
        flutterLaunch()
        assertEquals(1, registerCount())
    }

    @Test
    fun `S02 flutter relaunches with nothing changed send no register`() {
        flutterLaunch()
        repeat(3) { flutterLaunch() }
        assertEquals(1, registerCount())
    }

    @Test
    fun `S03 the early start alone sends nothing`() {
        PushCore.initialize(context, null, explicit = true)
        settle()
        assertEquals(0, requests.size)
    }

    @Test
    fun `S04 initialize called again with the same config in one launch sends nothing`() {
        flutterLaunch()
        // Firebase onValue firing again: Dart calls initialize(config) a second time.
        PushCore.initialize(context, config(), explicit = true)
        settle()
        assertEquals(1, registerCount())
    }

    @Test
    fun `S05 rotating only the auth token sends no register and the new token is used`() {
        flutterLaunch()
        flutterLaunch(config(authToken = "token-v2"))
        assertEquals(1, registerCount())

        PushCore.ensureInitialized(context).handleNewToken("fcm-after-rotation")
        settle()
        assertEquals("Bearer token-v2", registers().last().getHeader("Authorization"))
    }

    @Test
    fun `S06 a new FCM token registers once`() {
        val core = flutterLaunch()
        core.handleNewToken("fcm-token-2")
        settle()
        assertEquals(2, registerCount())
        assertTrue(registers().last().body.clone().readUtf8().contains("fcm-token-2"))

        core.handleNewToken("fcm-token-2")   // the same token redelivered
        settle()
        assertEquals(2, registerCount())
    }

    @Test
    fun `S07 a timezone change registers once on the next launch`() {
        flutterLaunch()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"))
        flutterLaunch()
        flutterLaunch()
        assertEquals(2, registerCount())
        assertTrue(registers().last().body.clone().readUtf8().replace("\\/", "/").contains("Asia/Dubai"))
    }

    @Test
    fun `S08 a language change registers once on the next launch`() {
        flutterLaunch()
        Locale.setDefault(Locale.forLanguageTag("ur-PK"))
        flutterLaunch()
        flutterLaunch()
        assertEquals(2, registerCount())
    }

    @Test
    fun `S09 moving to another project registers once with the new project`() {
        flutterLaunch()
        flutterLaunch(config(projectId = "project-b"))
        flutterLaunch(config(projectId = "project-b"))
        assertEquals(2, registerCount())
        assertTrue(registers().last().path!!.contains("projectId=project-b"))
    }

    @Test
    fun `S10 moving to another server registers there once`() {
        flutterLaunch()
        val other = MockWebServer().apply {
            dispatcher = server.dispatcher
            start()
        }
        try {
            flutterLaunch(config(baseUrl = other.url("/").toString()))
            flutterLaunch(config(baseUrl = other.url("/").toString()))
            assertEquals(2, registerCount())
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `S11 changing the application id registers once`() {
        flutterLaunch()
        flutterLaunch(config(applicationId = "Sahulat Wallet"))
        flutterLaunch(config(applicationId = "Sahulat Wallet"))
        assertEquals(2, registerCount())
    }

    @Test
    fun `S12 work queued during the early start is delivered after the config arrives`() {
        PushCore.resetForTesting()
        val early = PushCore.initialize(context, null, explicit = true)
        early.handleNewToken("fcm-from-early-start")   // e.g. onNewToken before Dart has run
        settle()
        assertEquals(0, requests.size)

        PushCore.initialize(context, config(), explicit = true)
        settle()
        assertEquals(1, registerCount())
        assertTrue(registers().single().body.clone().readUtf8().contains("fcm-from-early-start"))
    }

    @Test
    fun `S13 a failed register is retried on the next launch`() {
        registerStatus = 503
        flutterLaunch()
        val failedAttempts = registerCount()
        assertTrue("register was attempted", failedAttempts >= 1)

        registerStatus = 201
        flutterLaunch()
        assertEquals(failedAttempts + 1, registerCount())

        flutterLaunch()   // now registered: nothing more
        assertEquals(failedAttempts + 1, registerCount())
    }

    @Test
    fun `S14 a reinstall registers a new installation`() {
        flutterLaunch()
        val first = installationIdOf(registers().single())

        PushCore.resetForTesting()
        StorageManager(context).clearAll()   // app data cleared / reinstalled
        flutterLaunch()

        assertEquals(2, registerCount())
        assertNotEquals(first, installationIdOf(registers().last()))
    }

    @Test
    fun `S15 the same installation id is sent on every register`() {
        flutterLaunch()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dubai"))
        flutterLaunch()
        val ids = registers().map(::installationIdOf).toSet()
        assertEquals(1, ids.size)
    }

    // ------------------------------------------------------------------ native scenarios

    @Test
    fun `S16 native first launch registers once and relaunches send nothing`() {
        nativeLaunch()
        repeat(3) { nativeLaunch() }
        assertEquals(1, registerCount())
    }

    // ------------------------------------------------------------------ in-app notifications switch

    private fun grantNotificationPermission() {
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toggles(): List<RecordedRequest> =
        synchronized(requests) { requests.filter { it.path.orEmpty().contains("/devices/toggle") } }

    private fun bodyOf(request: RecordedRequest) = request.body.clone().readUtf8()

    @Test
    fun `S17 turning notifications off tells the server once`() {
        grantNotificationPermission()
        val core = flutterLaunch()
        core.handleNewToken("fcm-token-1")   // the toggle endpoint addresses the device by token
        settle()
        val registersBefore = registerCount()
        assertTrue(bodyOf(registers().last()).contains("\"notificationsEnabled\":true"))

        com.ary.push.ARYPush.setNotificationsEnabled(false)
        settle()

        assertEquals(registersBefore + 1, registerCount())
        assertTrue(bodyOf(registers().last()).contains("\"notificationsEnabled\":false"))
        assertEquals(1, toggles().size)
        assertTrue(bodyOf(toggles().single()).contains("\"notificationsEnabled\":false"))
        assertTrue(bodyOf(toggles().single()).contains("fcm-token-1"))
    }

    @Test
    fun `S18 the choice survives relaunches and is not resent`() {
        grantNotificationPermission()
        flutterLaunch()
        com.ary.push.ARYPush.setNotificationsEnabled(false)
        settle()
        val sent = requests.size

        repeat(2) { flutterLaunch() }

        assertEquals(sent, requests.size)
        assertEquals(false, com.ary.push.ARYPush.isNotificationsEnabled())
    }

    @Test
    fun `S19 setting the same value again sends nothing`() {
        grantNotificationPermission()
        flutterLaunch()
        val sent = requests.size

        com.ary.push.ARYPush.setNotificationsEnabled(true)   // already on
        settle()
        com.ary.push.ARYPush.setNotificationsEnabled(false)
        settle()
        val afterOff = requests.size
        com.ary.push.ARYPush.setNotificationsEnabled(false)  // already off
        settle()

        assertTrue(afterOff > sent)
        assertEquals(afterOff, requests.size)
    }

    @Test
    fun `S20 turning notifications back on tells the server`() {
        grantNotificationPermission()
        flutterLaunch()
        com.ary.push.ARYPush.setNotificationsEnabled(false)
        settle()
        val registersWhenOff = registerCount()

        com.ary.push.ARYPush.setNotificationsEnabled(true)
        settle()

        assertEquals(registersWhenOff + 1, registerCount())
        assertTrue(bodyOf(registers().last()).contains("\"notificationsEnabled\":true"))
        assertEquals(true, com.ary.push.ARYPush.isNotificationsEnabled())
    }

    @Test
    fun `S21 with the system permission off the switch changes nothing on the server`() {
        flutterLaunch()   // no POST_NOTIFICATIONS grant: the server already sees notifications off
        val sent = requests.size

        com.ary.push.ARYPush.setNotificationsEnabled(false)
        settle()

        assertEquals(sent, requests.size)
        assertEquals(false, com.ary.push.ARYPush.isNotificationsEnabled())   // still remembered
    }

    @Test
    fun `S22 nothing is shown while off but the app still receives the message`() {
        grantNotificationPermission()
        val core = flutterLaunch()
        val received = mutableListOf<String>()
        com.ary.push.ARYPush.addNotificationReceivedListener { received += it.id }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)

        com.ary.push.ARYPush.setNotificationsEnabled(false)
        core.handleRemoteMessage(dataMessage("m-off"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shadowOf(manager).allNotifications.size)

        com.ary.push.ARYPush.setNotificationsEnabled(true)
        core.handleRemoteMessage(dataMessage("m-on"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(manager).allNotifications.size)

        assertEquals(listOf("m-off", "m-on"), received)
    }

    @Test
    fun `S23 calls before initialize are ignored without crashing`() {
        PushCore.resetForTesting()
        com.ary.push.ARYPush.setNotificationsEnabled(false)
        assertEquals(true, com.ary.push.ARYPush.isNotificationsEnabled())
        assertEquals(0, requests.size)
    }

    private fun dataMessage(id: String) =
        com.google.firebase.messaging.RemoteMessage.Builder("push-api@fcm.googleapis.com")
            .setMessageId(id)
            .addData("title", "Sale")
            .addData("body", "50% off")
            .build()
}

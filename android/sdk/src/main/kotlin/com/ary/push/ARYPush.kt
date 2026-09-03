package com.ary.push

import android.content.Context
import com.ary.push.internal.PushCore
import com.ary.push.internal.log.PushLogger
import com.ary.push.model.PushNotification
import com.ary.push.model.PushPermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The ARY Push SDK.
 *
 * Adding push to an existing application is one dependency and one line:
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ARYPush.initialize(this)
 *     }
 * }
 * ```
 *
 * Everything else is optional. The SDK handles permission, the FCM token and its refreshes, the
 * installation identity, receiving, displaying and opening notifications, channels, topics, tags,
 * user identity, local storage, backend synchronisation, the offline queue, retries and
 * deduplication.
 *
 * What it never does is navigate. Taps arrive as [addNotificationOpenedListener] callbacks
 * carrying the payload, and the host application's own router decides where the user lands.
 *
 * Every method here is thread-safe, and every method is safe to call before
 * [initialize]: the call is logged and ignored rather than throwing, because a push SDK must
 * never be the reason an application crashes.
 */
public object ARYPush {

    // ------------------------------------------------------------------ initialization

    /**
     * Initializes the SDK.
     *
     * Idempotent, thread-safe and cheap: repeated calls reuse the existing instance and never
     * create a second set of listeners, services, notification handlers, installation ids or
     * event streams. Nothing blocking happens on the calling thread.
     *
     * Passing a [config] to an already-initialized SDK reconfigures it in place, which is what
     * lets an application let automatic initialization start the SDK and then supply its
     * environment once it knows which one it is in.
     *
     * @param context any context; only the application context is retained.
     * @param config optional configuration. When omitted, manifest `meta-data` is used.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, config: ARYPushConfig? = null) {
        try {
            PushCore.initialize(context, config, explicit = true)
        } catch (t: Throwable) {
            PushLogger.e(t) { "ARYPush.initialize() failed" }
        }
    }

    /** True once [initialize] has run in this process. */
    @JvmStatic
    public val isInitialized: Boolean
        get() = PushCore.isInitialized

    // ------------------------------------------------------------------ permission

    /**
     * Requests the notification permission, showing the system prompt when one is possible.
     *
     * No `ActivityResultLauncher` and no `onRequestPermissionsResult` override are needed: the
     * SDK hosts the prompt itself. [callback] is invoked on the main thread exactly once, with
     * the resulting status.
     *
     * On Android 12 and below, and whenever a prompt is no longer possible (already granted, or
     * permanently denied), the current status is reported immediately without showing anything.
     */
    @JvmStatic
    @JvmOverloads
    public fun requestPermission(callback: ((PushPermissionStatus) -> Unit)? = null) {
        val core = requireCore("requestPermission") ?: run {
            callback?.invoke(PushPermissionStatus.NOT_DETERMINED)
            return
        }
        core.requestPermission { status -> callback?.invoke(status) }
    }

    /**
     * Current notification permission state.
     *
     * Reflects both the Android 13 runtime permission and the application-level notification
     * toggle, so [PushPermissionStatus.DENIED] genuinely means "nothing will be shown".
     */
    @JvmStatic
    public fun getPermissionStatus(): PushPermissionStatus =
        requireCore("getPermissionStatus")?.permissionManager?.status
            ?: PushPermissionStatus.NOT_DETERMINED

    /**
     * Opens this application's notification settings.
     *
     * The escape hatch for a permanently denied user, who can no longer be shown a prompt.
     */
    @JvmStatic
    public fun openNotificationSettings(context: Context) {
        val core = requireCore("openNotificationSettings") ?: return
        runCatching { context.startActivity(core.permissionManager.notificationSettingsIntent()) }
            .onFailure { PushLogger.e(it) { "Could not open notification settings" } }
    }

    // ------------------------------------------------------------------ identity and token

    /**
     * The SDK's installation identifier for this app on this device.
     *
     * Stable across token refreshes, logins and logouts. Created on first use, so this is never
     * null once the SDK is initialized.
     */
    @JvmStatic
    public fun getInstallationId(): String? =
        requireCore("getInstallationId")?.installationManager?.installationId

    /**
     * The current push token, requesting one from FCM if the SDK has not cached one.
     *
     * Returns null when Firebase is not configured in the host application, or when FCM declines
     * to issue a token. The host application never has to send this to the backend: the SDK
     * registers and re-registers it automatically.
     */
    @JvmStatic
    public suspend fun getPushToken(): String? =
        withContext(Dispatchers.IO) { requireCore("getPushToken")?.pushToken() }

    /**
     * Callback form of [getPushToken], for Java callers and non-coroutine code.
     *
     * [callback] is invoked on the main thread exactly once.
     */
    @JvmStatic
    public fun getPushToken(callback: (String?) -> Unit) {
        val core = requireCore("getPushToken") ?: run { callback(null); return }
        core.fetchTokenAsync(callback)
    }

    /** Registers a listener invoked on the main thread whenever the push token changes. */
    @JvmStatic
    public fun addTokenRefreshListener(listener: (String) -> Unit) {
        requireCore("addTokenRefreshListener")?.tokenManager?.addListener(listener)
    }

    @JvmStatic
    public fun removeTokenRefreshListener(listener: (String) -> Unit) {
        requireCore("removeTokenRefreshListener")?.tokenManager?.removeListener(listener)
    }

    // ------------------------------------------------------------------ user identity

    /**
     * Associates this installation with a user.
     *
     * Local state changes immediately, so an offline login is true from the application's point
     * of view straight away; the backend is told through the durable queue. One user may own
     * several installations.
     */
    @JvmStatic
    public fun login(userId: String) {
        val core = requireCore("login") ?: return
        runCatching { core.userManager.login(userId) }
            .onFailure { PushLogger.e(it) { "login() failed" } }
    }

    /**
     * Clears the user association.
     *
     * Deliberately narrow: the installation id, the push token and the device registration all
     * survive, so the device keeps receiving unauthenticated campaigns. Logout is not
     * unregistration.
     */
    @JvmStatic
    public fun logout() {
        val core = requireCore("logout") ?: return
        runCatching { core.userManager.logout() }
            .onFailure { PushLogger.e(it) { "logout() failed" } }
    }

    /** The currently associated user, or null when logged out. */
    @JvmStatic
    public fun getUserId(): String? = requireCore("getUserId")?.userManager?.userId

    // ------------------------------------------------------------------ tags

    /**
     * Sets one tag.
     *
     * Tags are attributes the backend builds segments from: `subscription=premium`,
     * `language=en`, `country=PK`. Consecutive calls are coalesced into a single request.
     */
    @JvmStatic
    public fun addTag(key: String, value: String) {
        requireCore("addTag")?.tagManager?.addTag(key, value)
    }

    /** Sets several tags at once. */
    @JvmStatic
    public fun addTags(tags: Map<String, String>) {
        requireCore("addTags")?.tagManager?.addTags(tags)
    }

    @JvmStatic
    public fun removeTag(key: String) {
        requireCore("removeTag")?.tagManager?.removeTag(key)
    }

    @JvmStatic
    public fun removeTags(keys: Set<String>) {
        requireCore("removeTags")?.tagManager?.removeTags(keys)
    }

    @JvmStatic
    public fun removeAllTags() {
        requireCore("removeAllTags")?.tagManager?.removeAllTags()
    }

    /** Tags currently held for this installation, read from local storage. */
    @JvmStatic
    public fun getTags(): Map<String, String> =
        requireCore("getTags")?.tagManager?.tags ?: emptyMap()

    // ------------------------------------------------------------------ topics

    /**
     * Subscribes this device to an FCM topic.
     *
     * Topic names are validated against FCM's grammar before the call is made, so an invalid
     * name fails locally and visibly instead of being silently dropped by the server.
     *
     * A topic is not a segment: topics are opted into by the device, segments are computed by
     * the backend from tags.
     */
    @JvmStatic
    @JvmOverloads
    public fun subscribeToTopic(topic: String, callback: ((Boolean) -> Unit)? = null) {
        val core = requireCore("subscribeToTopic") ?: run { callback?.invoke(false); return }
        core.topicManager.subscribe(topic) { success -> callback?.invoke(success) }
    }

    @JvmStatic
    @JvmOverloads
    public fun unsubscribeFromTopic(topic: String, callback: ((Boolean) -> Unit)? = null) {
        val core = requireCore("unsubscribeFromTopic") ?: run { callback?.invoke(false); return }
        core.topicManager.unsubscribe(topic) { success -> callback?.invoke(success) }
    }

    /** Topics this device is recorded as subscribed to. */
    @JvmStatic
    public fun getSubscribedTopics(): Set<String> =
        requireCore("getSubscribedTopics")?.topicManager?.topics ?: emptySet()

    // ------------------------------------------------------------------ notification events

    /**
     * Called when a message arrives while the process is running.
     *
     * Not called for messages the system rendered on its own while the application was
     * backgrounded: Android does not tell an application about those until they are tapped.
     * See docs/NOTIFICATION_LIFECYCLE.md.
     */
    @JvmStatic
    public fun addNotificationReceivedListener(listener: (PushNotification) -> Unit) {
        requireCore("addNotificationReceivedListener")?.dispatcher?.addReceivedListener(listener)
    }

    @JvmStatic
    public fun removeNotificationReceivedListener(listener: (PushNotification) -> Unit) {
        requireCore("removeNotificationReceivedListener")?.dispatcher?.removeReceivedListener(listener)
    }

    /**
     * Called when the user taps a notification, or one of its action buttons.
     *
     * A tap that happened while the application was terminated is not lost: it is persisted and
     * replayed to the first listener that registers, so attaching this in `Application.onCreate`
     * always sees it.
     *
     * The SDK does not navigate. Read [PushNotification.data] and route from here.
     */
    @JvmStatic
    public fun addNotificationOpenedListener(listener: (PushNotification) -> Unit) {
        requireCore("addNotificationOpenedListener")?.dispatcher?.addOpenedListener(listener)
    }

    @JvmStatic
    public fun removeNotificationOpenedListener(listener: (PushNotification) -> Unit) {
        requireCore("removeNotificationOpenedListener")?.dispatcher?.removeOpenedListener(listener)
    }

    /**
     * The notification that launched the application, if it has not been delivered yet.
     *
     * Most applications should register [addNotificationOpenedListener] instead, which replays
     * the same event. This exists for frameworks that pull rather than subscribe.
     */
    @JvmStatic
    public fun consumeInitialNotification(): PushNotification? =
        requireCore("consumeInitialNotification")?.dispatcher?.consumePendingOpen()

    // ------------------------------------------------------------------ events

    /**
     * Records a push-related event.
     *
     * Scope is push: delivery and engagement attribution on the push backend. This is not an
     * analytics SDK and should not be used as one.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackEvent(name: String, properties: Map<String, String> = emptyMap()) {
        requireCore("trackEvent")?.eventManager?.track(name, properties)
    }

    // ------------------------------------------------------------------ maintenance

    /**
     * Sends anything the SDK is holding back.
     *
     * Tag writes are debounced and queued operations wait for connectivity, so an application
     * that is about to be killed (a logout screen, a test) can call this to stop waiting.
     */
    @JvmStatic
    public fun flush() {
        val core = requireCore("flush") ?: return
        core.tagManager.flushNow()
        core.syncManager.requestSync()
    }

    private fun requireCore(operation: String) = PushCore.instance.also {
        if (it == null) {
            PushLogger.w {
                "$operation() called before ARYPush.initialize(); the call was ignored"
            }
        }
    }
}

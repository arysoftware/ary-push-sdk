package com.ary.push.internal

import android.content.Context
import com.ary.push.ARYPushConfig
import com.ary.push.ForegroundDisplayPolicy
import com.ary.push.api.OkHttpRestClient
import com.ary.push.api.RestClient
import com.ary.push.backend.NoopPushBackend
import com.ary.push.backend.PushBackend
import com.ary.push.backend.RestPushBackend
import com.ary.push.internal.device.DeviceInfoProvider
import com.ary.push.internal.event.EventManager
import com.ary.push.internal.installation.InstallationManager
import com.ary.push.internal.log.PushLogger
import com.ary.push.internal.net.ConnectivityMonitor
import com.ary.push.internal.notification.DeduplicationManager
import com.ary.push.internal.notification.NotificationEventDispatcher
import com.ary.push.internal.notification.NotificationParser
import com.ary.push.internal.notification.NotificationRenderer
import com.ary.push.internal.permission.PermissionManager
import com.ary.push.internal.permission.PermissionRequestActivity
import com.ary.push.internal.storage.StorageManager
import com.ary.push.internal.sync.OperationQueue
import com.ary.push.internal.sync.RetryManager
import com.ary.push.internal.sync.SyncManager
import com.ary.push.internal.tag.TagManager
import com.ary.push.internal.token.TokenManager
import com.ary.push.internal.topic.TopicManager
import com.ary.push.internal.user.UserManager
import com.ary.push.model.Installation
import com.ary.push.model.PushNotification
import com.ary.push.model.PushPermissionStatus
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The engine behind the [com.ary.push.ARYPush] facade.
 *
 * Everything stateful lives here exactly once. The facade is a thin, null-safe wrapper so that
 * the public API can never be the thing that owns state, and so that a host application calling
 * an API before initialization gets a logged warning rather than an exception.
 *
 * Two independent halves meet in this class and are deliberately not allowed to depend on each
 * other: the **push engine** (FCM, rendering, events) and **backend synchronisation** (queue,
 * REST). Nothing in the push path awaits the sync path, which is why a backend outage cannot
 * stop a notification being received, displayed or opened.
 */
internal class PushCore private constructor(
    context: Context,
    initialConfig: ARYPushConfig
) {

    private val appContext: Context = context.applicationContext

    @Volatile
    var config: ARYPushConfig = initialConfig
        private set

    /**
     * The SDK's own scope.
     *
     * A [SupervisorJob] so that one failed synchronisation cannot cancel unrelated SDK work, and
     * [Dispatchers.IO] because everything launched here is storage or network bound. Nothing the
     * SDK does runs on the main thread except the listener callbacks it owes the host
     * application.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("ARYPush")
    )

    val storage: StorageManager = StorageManager(appContext)
    val device: DeviceInfoProvider = DeviceInfoProvider(appContext)
    val installationManager: InstallationManager = InstallationManager(storage)
    val permissionManager: PermissionManager = PermissionManager(appContext, storage)
    val dispatcher: NotificationEventDispatcher = NotificationEventDispatcher(storage)
    val foregroundTracker: AppForegroundTracker = AppForegroundTracker.attach(appContext)

    private val deduplication = DeduplicationManager(storage, initialConfig.deduplicationCacheSize)
    private val queue = OperationQueue(storage)

    @Volatile
    private var renderer: NotificationRenderer = NotificationRenderer(appContext, initialConfig)

    @Volatile
    private var restClient: RestClient? = null

    @Volatile
    private var backend: PushBackend = NoopPushBackend

    private val connectivity = ConnectivityMonitor(appContext) { syncManager.requestSync() }

    val syncManager: SyncManager = SyncManager(
        scope = scope,
        queue = queue,
        storage = storage,
        isOnline = { connectivity.isOnline },
        retryManager = RetryManager(initialConfig.retry),
        backendProvider = { backend },
        installationProvider = { buildInstallation() }
    )

    val tokenManager: TokenManager = TokenManager(storage) { token, provider ->
        syncManager.enqueueTokenUpdate(token, provider)
    }

    val userManager: UserManager = UserManager(storage) { userId ->
        if (userId != null) syncManager.enqueueIdentify(userId) else syncManager.enqueueLogout()
    }

    val tagManager: TagManager = TagManager(
        storage = storage,
        scope = scope,
        debounceMs = initialConfig.tagSyncDebounceMs,
        onTagsChanged = { tags -> syncManager.enqueueTagUpdate(tags) },
        onTagsRemoved = { keys, all -> syncManager.enqueueTagRemoval(keys, all) }
    )

    val topicManager: TopicManager = TopicManager(storage) { topics ->
        syncManager.enqueueTopicUpdate(topics)
    }

    val eventManager: EventManager = EventManager { event -> syncManager.enqueueEvent(event) }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Brings the SDK up.
     *
     * Ordering is chosen so that the cheap, always-correct work happens synchronously (logger,
     * channel, permission sampling) and everything that can block (token, network) is deferred
     * to [scope]. `initialize()` must never make an application's cold start slower.
     */
    private fun start() {
        PushLogger.configure(config.enableLogging, config.logLevel)
        PushLogger.i { "Initialization started ($config)" }

        applyBackend(config)
        renderer.createDefaultChannel()
        PermissionRequestActivity.install { permissionManager.status }
        connectivity.start()

        val installationId = installationManager.installationId
        PushLogger.i { "Installation ID loaded" }
        PushLogger.i { "Permission status: ${permissionManager.status}" }

        if (permissionManager.recordStatusChange()) {
            syncManager.enqueuePermissionUpdate(permissionManager.isAuthorized)
        }

        scope.launch {
            // The token is requested before registration so the first registration already
            // carries it, which saves an immediate follow-up token update.
            tokenManager.fetchToken()
            syncManager.registerInstallationIfChanged()
            syncManager.requestSync()
        }

        if (config.autoRequestPermission && permissionManager.canRequest) {
            requestPermission {}
        }

        PushLogger.i { "Initialization complete (installation=$installationId)" }
    }

    /**
     * Applies a configuration supplied after the SDK was already running.
     *
     * The common cause is an application that lets automatic initialization bring the SDK up and
     * then calls `initialize(context, config)` from its own startup path once it knows which
     * environment it is in. Managers and listeners are preserved; only configuration-derived
     * components are rebuilt.
     */
    @Synchronized
    fun reconfigure(newConfig: ARYPushConfig) {
        val previous = config
        config = newConfig
        PushLogger.configure(newConfig.enableLogging, newConfig.logLevel)
        PushLogger.i { "Reconfigured ($newConfig)" }

        if (previous.defaultChannelId != newConfig.defaultChannelId ||
            previous.smallIconResId != newConfig.smallIconResId ||
            previous.accentColor != newConfig.accentColor ||
            previous.defaultChannelName != newConfig.defaultChannelName
        ) {
            renderer = NotificationRenderer(appContext, newConfig).also { it.createDefaultChannel() }
        }

        if (previous.backend != newConfig.backend ||
            previous.customBackend !== newConfig.customBackend ||
            previous.authProvider !== newConfig.authProvider
        ) {
            applyBackend(newConfig)
            // A new environment means the backend has never seen this installation.
            syncManager.registerInstallationIfChanged(force = true)
        }

        syncManager.requestSync()
    }

    /**
     * Chooses the backend for a configuration.
     *
     * A custom backend wins; a REST configuration builds a transport; anything else runs
     * server-less. A REST client that cannot be built degrades to [NoopPushBackend] rather than
     * failing initialization, because a bad base URL must not cost the application its push.
     */
    private fun applyBackend(target: ARYPushConfig) {
        restClient?.let { existing -> runCatching { existing.close() } }
        restClient = null

        val custom = target.customBackend
        if (custom != null) {
            backend = custom
            PushLogger.i { "Using a host-supplied PushBackend implementation" }
            return
        }

        val backendConfig = target.backend
        if (backendConfig == null) {
            backend = NoopPushBackend
            PushLogger.i { "No backend configured; running without server synchronisation" }
            return
        }

        backend = try {
            val client = OkHttpRestClient(
                backendConfig = backendConfig,
                networkConfig = target.network,
                retryConfig = target.retry,
                authProvider = target.authProvider,
                device = device,
                installationIdProvider = {
                    // Read without creating: header construction must never be what generates
                    // the installation id.
                    storage.getString(StorageManager.KEY_INSTALLATION_ID)
                }
            )
            restClient = client
            RestPushBackend(client, backendConfig)
        } catch (t: Throwable) {
            PushLogger.e(t) { "Could not build the REST backend; synchronisation is disabled" }
            NoopPushBackend
        }
    }

    // ------------------------------------------------------------------ message handling

    /**
     * Handles one FCM message.
     *
     * Runs on FCM's service thread. It is synchronous on purpose: the system keeps the process
     * alive for the duration of `onMessageReceived`, so work handed to another thread can be
     * killed before it finishes. Only backend synchronisation is deferred, and that is durable.
     */
    fun handleRemoteMessage(message: RemoteMessage) {
        val inForeground = foregroundTracker.isForeground
        val notification = try {
            NotificationParser.parse(message, inForeground)
        } catch (t: Throwable) {
            PushLogger.e(t) { "Could not parse an incoming message" }
            return
        }

        if (!deduplication.markSeenIfNew(notification.id)) return

        PushLogger.d {
            "Message ${notification.id} received (foreground=$inForeground, " +
                "dataKeys=${notification.data.keys})"
        }

        val policy = if (inForeground) config.foregroundDisplay else ForegroundDisplayPolicy.SHOW
        if (policy == ForegroundDisplayPolicy.SUPPRESS) {
            PushLogger.d { "Foreground policy is SUPPRESS; message ${notification.id} dropped" }
            return
        }

        // Only data messages reach this code while the app is backgrounded: a message with a
        // `notification` block is rendered by the system itself and never surfaces here. That
        // asymmetry is the OS's, not the SDK's, and is documented in NOTIFICATION_LIFECYCLE.md.
        val shouldRender = config.displayNotifications &&
            policy == ForegroundDisplayPolicy.SHOW &&
            (notification.title != null || notification.body != null)

        if (shouldRender) {
            runCatching { renderer.render(notification) }
                .onFailure { PushLogger.e(it) { "Rendering failed for ${notification.id}" } }
        }

        dispatcher.dispatchReceived(notification)
        eventManager.trackNotificationReceived(notification.id, inForeground)
    }

    /** Records a token reported by a messaging service, whether the SDK's or the host's. */
    fun handleNewToken(token: String) {
        tokenManager.handleNewToken(token)
        syncManager.registerInstallationIfChanged()
    }

    /**
     * Handles a notification tap.
     *
     * Called from [NotificationOpenActivity], which may have started this process. Opens are
     * deduplicated separately from receipts, keyed on the action as well, so that tapping the
     * body and then an action button are two distinct events but a redelivered intent is not.
     */
    fun handleNotificationOpened(notification: PushNotification, systemNotificationId: Int) {
        val openKey = "open:${notification.id}:${notification.actionId.orEmpty()}"
        if (!deduplication.markSeenIfNew(openKey)) return

        if (systemNotificationId != 0) renderer.cancel(systemNotificationId)

        dispatcher.dispatchOpened(notification)
        eventManager.trackNotificationOpened(notification.id, notification.actionId)
    }

    // ------------------------------------------------------------------ operations

    fun requestPermission(callback: (PushPermissionStatus) -> Unit) {
        val current = permissionManager.status
        if (!permissionManager.canRequest) {
            // Already granted, permanently denied, or pre-API-33 where there is nothing to ask.
            PushLogger.i { "requestPermission(): no prompt is possible, status is $current" }
            callback(current)
            return
        }

        permissionManager.hasBeenAsked = true
        PermissionRequestActivity.start(appContext) { status ->
            PushLogger.i { "Permission result: $status" }
            if (permissionManager.recordStatusChange()) {
                syncManager.enqueuePermissionUpdate(permissionManager.isAuthorized)
            }
            if (status.isAuthorized) {
                // A device that just became reachable is worth registering immediately.
                scope.launch {
                    tokenManager.fetchToken()
                    syncManager.registerInstallationIfChanged()
                }
            }
            callback(status)
        }
    }

    suspend fun pushToken(): String? = tokenManager.fetchToken()

    /**
     * Reads segment membership from the backend, answering on the main thread exactly once.
     *
     * Deliberately not cached: membership changes on the server whenever tags change, and a
     * stale cached list is worse than a fresh call the caller chose to make.
     */
    fun fetchSegmentsAsync(callback: (List<com.ary.push.model.Segment>) -> Unit) {
        scope.launch {
            val installationId = installationManager.installationId
            val result = runCatching { backend.getSegments(installationId) }
                .getOrElse { error ->
                    PushLogger.e(error) { "Segment lookup threw" }
                    null
                }
            val segments = result?.getOrNull().orEmpty()
            if (result != null && !result.isSuccess) {
                PushLogger.w { "Segment lookup failed; reporting an empty list" }
            }
            withContext(Dispatchers.Main) {
                runCatching { callback(segments) }
                    .onFailure { PushLogger.e(it) { "Segment callback threw" } }
            }
        }
    }

    /** Callback form of [pushToken], answering on the main thread exactly once. */
    fun fetchTokenAsync(callback: (String?) -> Unit) {
        scope.launch {
            val token = tokenManager.fetchToken()
            withContext(Dispatchers.Main) {
                runCatching { callback(token) }
                    .onFailure { PushLogger.e(it) { "Token callback threw" } }
            }
        }
    }

    /** Assembles the record the backend stores for this device. */
    fun buildInstallation(): Installation {
        val collectDeviceInfo = config.collectDeviceInfo
        return Installation(
            id = installationManager.installationId,
            applicationId = config.backend?.applicationId,
            platform = PLATFORM,
            provider = tokenManager.provider,
            pushToken = tokenManager.currentToken,
            userId = userManager.userId,
            appVersion = device.appVersion,
            appBuild = device.appBuild,
            sdkVersion = device.sdkVersion,
            osVersion = if (collectDeviceInfo) device.osVersion else null,
            deviceModel = if (collectDeviceInfo) device.deviceModel else null,
            locale = if (collectDeviceInfo) device.locale else null,
            timezone = if (collectDeviceInfo) device.timezone else null,
            notificationsEnabled = permissionManager.isAuthorized
        )
    }

    /** Flushes pending work and releases resources. Used by tests and by Flutter engine teardown. */
    fun shutdown() {
        runCatching { tagManager.flushNow() }
        runCatching { connectivity.stop() }
        runCatching { backend.close() }
        runCatching { restClient?.close() }
        dispatcher.clearListeners()
        PushLogger.i { "SDK shut down" }
    }

    internal companion object {
        private const val PLATFORM = "android"

        @Volatile
        private var core: PushCore? = null

        private val initLock = Any()

        /** The live core, or null when the SDK has never been initialized in this process. */
        val instance: PushCore? get() = core

        val isInitialized: Boolean get() = core != null

        /**
         * Creates the core, or reconfigures the existing one.
         *
         * Idempotent and thread-safe, which matters more than it sounds: a background message,
         * a notification tap and the host application's own `onCreate` can all reach this method
         * within milliseconds of each other on a cold start. Exactly one core, one set of
         * listeners, one installation id and one event stream must come out of that race.
         */
        fun initialize(
            context: Context,
            config: ARYPushConfig?,
            explicit: Boolean
        ): PushCore = synchronized(initLock) {
            val existing = core
            if (existing != null) {
                if (explicit && config != null && config !== existing.config) {
                    existing.reconfigure(config)
                } else {
                    PushLogger.d { "initialize() called again; existing instance reused" }
                }
                return existing
            }

            val effectiveConfig = config ?: ManifestConfigReader.read(context)
            val created = PushCore(context, effectiveConfig)
            core = created
            try {
                created.start()
            } catch (t: Throwable) {
                // Initialization must be crash-safe. A partially started SDK is still better
                // than an application that cannot launch, and every public API is null-safe.
                PushLogger.e(t) { "Initialization failed; the SDK is running in a degraded state" }
            }
            return created
        }

        /**
         * Guarantees a core exists, without requiring the host application to have run yet.
         *
         * This is what makes background messages and terminated-state taps work: the system
         * starts the process for the SDK's own service or trampoline Activity, and neither can
         * assume any host code has executed. Configuration comes from the manifest in that case.
         */
        fun ensureInitialized(context: Context): PushCore =
            core ?: initialize(context, config = null, explicit = false)

        /** Drops the singleton. Test-only: production code has no reason to tear the SDK down. */
        internal fun resetForTesting() = synchronized(initLock) {
            core?.shutdown()
            core = null
        }
    }
}

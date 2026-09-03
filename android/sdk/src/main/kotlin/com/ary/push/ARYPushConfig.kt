package com.ary.push

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.ary.push.api.AuthProvider
import com.ary.push.backend.PushBackend

/**
 * Optional configuration for [ARYPush.initialize].
 *
 * Every value has a working default, so the minimal integration is a single line:
 *
 * ```kotlin
 * ARYPush.initialize(this)
 * ```
 *
 * Anything not set in code can also be supplied as `meta-data` in the host application's
 * manifest, which lets an application configure the SDK without touching its `Application`
 * class at all. Values passed here always win over manifest values. The manifest keys are
 * listed in docs/ANDROID.md.
 */
public class ARYPushConfig @JvmOverloads constructor(

    /** Emit SDK logs. Keep this off in release builds. */
    public val enableLogging: Boolean = false,

    /** Minimum level emitted when [enableLogging] is true. */
    public val logLevel: PushLogLevel = PushLogLevel.INFO,

    /**
     * Ask for notification permission during initialization.
     *
     * Off by default: prompting on first launch, out of context, is the most common cause of a
     * permanent denial. Call [ARYPush.requestPermission] at a moment that makes sense to
     * the user instead.
     */
    public val autoRequestPermission: Boolean = false,

    /** Identifier of the channel used when a message does not name one. */
    public val defaultChannelId: String = DEFAULT_CHANNEL_ID,

    /** User-visible channel name. Falls back to the `ary_push_default_channel_name` string. */
    public val defaultChannelName: String? = null,

    /** User-visible channel description. */
    public val defaultChannelDescription: String? = null,

    /** Channel importance, using the `NotificationManager.IMPORTANCE_*` scale. */
    public val defaultChannelImportance: Int = IMPORTANCE_DEFAULT,

    /**
     * Small icon for rendered notifications.
     *
     * Defaults to the host application icon. Supply a white-on-transparent silhouette to avoid
     * the grey square Android draws for non-compliant icons.
     */
    @DrawableRes
    public val smallIconResId: Int = 0,

    /** Accent colour applied to rendered notifications. */
    @ColorInt
    public val accentColor: Int? = null,

    /** What to do with a message that arrives while the application is in the foreground. */
    public val foregroundDisplay: ForegroundDisplayPolicy = ForegroundDisplayPolicy.SHOW,

    /**
     * Whether the SDK renders notifications at all.
     *
     * Set to false when the host application already renders notifications from the raw payload;
     * the SDK then only handles tokens, identity, tags and events.
     */
    public val displayNotifications: Boolean = true,

    /** Backend environment. Omit for a fully local, server-less integration. */
    public val backend: PushBackendConfig? = null,

    /** Transport timeouts. */
    public val network: NetworkConfig = NetworkConfig(),

    /** Backoff policy for the sync queue and transient HTTP failures. */
    public val retry: RetryConfig = RetryConfig(),

    /** Supplies the host application's access token to SDK requests. */
    public val authProvider: AuthProvider? = null,

    /**
     * Replaces the built-in REST backend entirely.
     *
     * Use this to plug in a bespoke transport, or a fake in tests, without touching the
     * notification engine. Wins over [backend] when both are present.
     */
    public val customBackend: PushBackend? = null,

    /** How many recently seen message identifiers are remembered. Bounded by design. */
    public val deduplicationCacheSize: Int = 200,

    /**
     * Send device model, OS version, locale and timezone with the installation record.
     *
     * Turn this off to register with the bare minimum: platform, versions and token.
     */
    public val collectDeviceInfo: Boolean = true,

    /**
     * Debounce window for tag writes, in milliseconds.
     *
     * Several `addTag` calls in a row collapse into one PATCH instead of a request storm.
     */
    public val tagSyncDebounceMs: Long = 750L
) {

    init {
        require(defaultChannelId.isNotBlank()) { "defaultChannelId must not be blank" }
        require(deduplicationCacheSize > 0) { "deduplicationCacheSize must be positive" }
        require(tagSyncDebounceMs >= 0) { "tagSyncDebounceMs must not be negative" }
    }

    /** Returns a copy with the supplied overrides applied. */
    public fun copyWith(
        enableLogging: Boolean = this.enableLogging,
        logLevel: PushLogLevel = this.logLevel,
        autoRequestPermission: Boolean = this.autoRequestPermission,
        defaultChannelId: String = this.defaultChannelId,
        defaultChannelName: String? = this.defaultChannelName,
        smallIconResId: Int = this.smallIconResId,
        accentColor: Int? = this.accentColor,
        foregroundDisplay: ForegroundDisplayPolicy = this.foregroundDisplay,
        displayNotifications: Boolean = this.displayNotifications,
        backend: PushBackendConfig? = this.backend
    ): ARYPushConfig = ARYPushConfig(
        enableLogging = enableLogging,
        logLevel = logLevel,
        autoRequestPermission = autoRequestPermission,
        defaultChannelId = defaultChannelId,
        defaultChannelName = defaultChannelName,
        defaultChannelDescription = defaultChannelDescription,
        defaultChannelImportance = defaultChannelImportance,
        smallIconResId = smallIconResId,
        accentColor = accentColor,
        foregroundDisplay = foregroundDisplay,
        displayNotifications = displayNotifications,
        backend = backend,
        network = network,
        retry = retry,
        authProvider = authProvider,
        customBackend = customBackend,
        deduplicationCacheSize = deduplicationCacheSize,
        collectDeviceInfo = collectDeviceInfo,
        tagSyncDebounceMs = tagSyncDebounceMs
    )

    override fun toString(): String = buildString {
        append("ARYPushConfig(logging=").append(enableLogging).append('/').append(logLevel)
        append(", channel=").append(defaultChannelId)
        append(", display=").append(displayNotifications)
        append(", foreground=").append(foregroundDisplay)
        append(", backend=").append(backend?.normalizedBaseUrl ?: "none")
        append(", applicationId=").append(backend?.applicationId ?: "none")
        append(", auth=").append(if (authProvider != null) "provided" else "none")
        append(')')
    }

    /** Java-friendly builder. Kotlin callers should use named constructor arguments instead. */
    public class Builder {
        private var enableLogging: Boolean = false
        private var logLevel: PushLogLevel = PushLogLevel.INFO
        private var autoRequestPermission: Boolean = false
        private var defaultChannelId: String = DEFAULT_CHANNEL_ID
        private var defaultChannelName: String? = null
        private var defaultChannelDescription: String? = null
        private var defaultChannelImportance: Int = IMPORTANCE_DEFAULT
        private var smallIconResId: Int = 0
        private var accentColor: Int? = null
        private var foregroundDisplay: ForegroundDisplayPolicy = ForegroundDisplayPolicy.SHOW
        private var displayNotifications: Boolean = true
        private var backend: PushBackendConfig? = null
        private var network: NetworkConfig = NetworkConfig()
        private var retry: RetryConfig = RetryConfig()
        private var authProvider: AuthProvider? = null
        private var customBackend: PushBackend? = null
        private var deduplicationCacheSize: Int = 200
        private var collectDeviceInfo: Boolean = true
        private var tagSyncDebounceMs: Long = 750L

        public fun enableLogging(value: Boolean): Builder = apply { enableLogging = value }
        public fun logLevel(value: PushLogLevel): Builder = apply { logLevel = value }
        public fun autoRequestPermission(value: Boolean): Builder = apply { autoRequestPermission = value }
        public fun defaultChannelId(value: String): Builder = apply { defaultChannelId = value }
        public fun defaultChannelName(value: String?): Builder = apply { defaultChannelName = value }
        public fun defaultChannelDescription(value: String?): Builder = apply { defaultChannelDescription = value }
        public fun defaultChannelImportance(value: Int): Builder = apply { defaultChannelImportance = value }
        public fun smallIconResId(@DrawableRes value: Int): Builder = apply { smallIconResId = value }
        public fun accentColor(@ColorInt value: Int?): Builder = apply { accentColor = value }
        public fun foregroundDisplay(value: ForegroundDisplayPolicy): Builder = apply { foregroundDisplay = value }
        public fun displayNotifications(value: Boolean): Builder = apply { displayNotifications = value }
        public fun backend(value: PushBackendConfig?): Builder = apply { backend = value }
        public fun network(value: NetworkConfig): Builder = apply { network = value }
        public fun retry(value: RetryConfig): Builder = apply { retry = value }
        public fun authProvider(value: AuthProvider?): Builder = apply { authProvider = value }
        public fun customBackend(value: PushBackend?): Builder = apply { customBackend = value }
        public fun deduplicationCacheSize(value: Int): Builder = apply { deduplicationCacheSize = value }
        public fun collectDeviceInfo(value: Boolean): Builder = apply { collectDeviceInfo = value }
        public fun tagSyncDebounceMs(value: Long): Builder = apply { tagSyncDebounceMs = value }

        public fun build(): ARYPushConfig = ARYPushConfig(
            enableLogging = enableLogging,
            logLevel = logLevel,
            autoRequestPermission = autoRequestPermission,
            defaultChannelId = defaultChannelId,
            defaultChannelName = defaultChannelName,
            defaultChannelDescription = defaultChannelDescription,
            defaultChannelImportance = defaultChannelImportance,
            smallIconResId = smallIconResId,
            accentColor = accentColor,
            foregroundDisplay = foregroundDisplay,
            displayNotifications = displayNotifications,
            backend = backend,
            network = network,
            retry = retry,
            authProvider = authProvider,
            customBackend = customBackend,
            deduplicationCacheSize = deduplicationCacheSize,
            collectDeviceInfo = collectDeviceInfo,
            tagSyncDebounceMs = tagSyncDebounceMs
        )
    }

    public companion object {
        /** Channel used when neither the payload nor the configuration names one. */
        public const val DEFAULT_CHANNEL_ID: String = "ary_push_default"

        /** Mirrors `NotificationManager.IMPORTANCE_DEFAULT` without requiring an API-26 import. */
        public const val IMPORTANCE_DEFAULT: Int = 3
    }
}

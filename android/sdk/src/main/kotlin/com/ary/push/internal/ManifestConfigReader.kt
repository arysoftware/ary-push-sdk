package com.ary.push.internal

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.ary.push.ARYPushConfig
import com.ary.push.ForegroundDisplayPolicy
import com.ary.push.PushBackendConfig
import com.ary.push.PushLogLevel
import com.ary.push.internal.log.PushLogger

/**
 * Builds a [ARYPushConfig] from `meta-data` in the host application's manifest.
 *
 * This exists for two reasons:
 *
 *  * An application can configure the SDK without touching its `Application` class, which
 *    matters when the SDK is dropped into a large existing app whose startup path is contested.
 *  * A background message or a notification tap can start the process with no host code having
 *    run at all. The SDK still has to know which channel to use and which backend to talk to,
 *    and the manifest is the only configuration available that early.
 *
 * Values passed to `ARYPush.initialize(context, config)` always win over these.
 */
internal object ManifestConfigReader {

    private const val PREFIX = "com.ary.push."

    const val KEY_AUTO_INIT: String = PREFIX + "auto_init"
    private const val KEY_LOGGING = PREFIX + "logging_enabled"
    private const val KEY_LOG_LEVEL = PREFIX + "log_level"
    private const val KEY_CHANNEL_ID = PREFIX + "default_channel_id"
    private const val KEY_CHANNEL_NAME = PREFIX + "default_channel_name"
    private const val KEY_CHANNEL_DESCRIPTION = PREFIX + "default_channel_description"
    private const val KEY_ICON = PREFIX + "notification_icon"
    private const val KEY_COLOR = PREFIX + "notification_color"
    private const val KEY_BASE_URL = PREFIX + "backend_base_url"
    private const val KEY_APPLICATION_ID = PREFIX + "application_id"
    private const val KEY_API_VERSION = PREFIX + "backend_api_version"
    private const val KEY_FOREGROUND_DISPLAY = PREFIX + "foreground_display"
    private const val KEY_DISPLAY_NOTIFICATIONS = PREFIX + "display_notifications"
    private const val KEY_AUTO_REQUEST_PERMISSION = PREFIX + "auto_request_permission"
    private const val KEY_COLLECT_DEVICE_INFO = PREFIX + "collect_device_info"

    /** True when the application opted into AndroidX Startup initialization. */
    fun isAutoInitEnabled(context: Context): Boolean =
        metaData(context)?.getBoolean(KEY_AUTO_INIT, false) ?: false

    /** Reads whatever the manifest declares, falling back to SDK defaults for the rest. */
    fun read(context: Context): ARYPushConfig {
        val meta = metaData(context) ?: return ARYPushConfig()
        val defaults = ARYPushConfig()

        val baseUrl = meta.getString(KEY_BASE_URL)?.takeIf { it.isNotBlank() }
        val backend = baseUrl?.let {
            runCatching {
                PushBackendConfig(
                    baseUrl = it,
                    applicationId = meta.getString(KEY_APPLICATION_ID)?.takeIf(String::isNotBlank),
                    apiVersion = meta.getString(KEY_API_VERSION)?.takeIf(String::isNotBlank) ?: "v1"
                )
            }.onFailure { error ->
                PushLogger.e(error) { "Invalid $KEY_BASE_URL in the manifest; ignoring it" }
            }.getOrNull()
        }

        return ARYPushConfig(
            enableLogging = meta.getBoolean(KEY_LOGGING, defaults.enableLogging),
            logLevel = parseLogLevel(meta.getString(KEY_LOG_LEVEL)) ?: defaults.logLevel,
            autoRequestPermission = meta.getBoolean(
                KEY_AUTO_REQUEST_PERMISSION,
                defaults.autoRequestPermission
            ),
            defaultChannelId = meta.getString(KEY_CHANNEL_ID)?.takeIf { it.isNotBlank() }
                ?: defaults.defaultChannelId,
            defaultChannelName = meta.getString(KEY_CHANNEL_NAME)?.takeIf { it.isNotBlank() },
            defaultChannelDescription = meta.getString(KEY_CHANNEL_DESCRIPTION)
                ?.takeIf { it.isNotBlank() },
            smallIconResId = meta.getInt(KEY_ICON, 0),
            accentColor = meta.getInt(KEY_COLOR, 0).takeIf { it != 0 },
            foregroundDisplay = parseForegroundPolicy(meta.getString(KEY_FOREGROUND_DISPLAY))
                ?: defaults.foregroundDisplay,
            displayNotifications = meta.getBoolean(
                KEY_DISPLAY_NOTIFICATIONS,
                defaults.displayNotifications
            ),
            collectDeviceInfo = meta.getBoolean(KEY_COLLECT_DEVICE_INFO, defaults.collectDeviceInfo),
            backend = backend
        )
    }

    private fun metaData(context: Context): Bundle? = try {
        context.applicationContext.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
    } catch (t: Throwable) {
        PushLogger.w(t) { "Could not read application meta-data; using SDK defaults" }
        null
    }

    private fun parseLogLevel(value: String?): PushLogLevel? =
        value?.uppercase()?.let { name -> PushLogLevel.entries.firstOrNull { it.name == name } }

    private fun parseForegroundPolicy(value: String?): ForegroundDisplayPolicy? =
        value?.uppercase()?.let { name ->
            ForegroundDisplayPolicy.entries.firstOrNull { it.name == name }
        }
}

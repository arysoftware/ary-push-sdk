package com.ary.push.flutter

import android.content.Context
import android.util.Log
import com.ary.push.ARYPushConfig
import com.ary.push.ForegroundDisplayPolicy
import com.ary.push.PushBackendConfig
import com.ary.push.PushLogLevel

/**
 * Translates the Dart configuration map into a native [ARYPushConfig].
 *
 * Every field is optional and every unrecognised value falls back to the native default. A
 * configuration mistake in Dart should degrade to "the SDK ran with defaults", never to a
 * failed initialization: an application that cannot start because a log level was misspelled is
 * a worse outcome than one that logs at the wrong level.
 */
internal object FlutterConfigMapper {

    fun from(arguments: Any?, context: Context): ARYPushConfig {
        val map = arguments as? Map<*, *> ?: return ARYPushConfig()
        val defaults = ARYPushConfig()

        return ARYPushConfig(
            enableLogging = map.bool("enableLogging") ?: defaults.enableLogging,
            logLevel = logLevel(map.string("logLevel")) ?: defaults.logLevel,
            autoRequestPermission = map.bool("autoRequestPermission")
                ?: defaults.autoRequestPermission,
            defaultChannelId = map.string("defaultChannelId")?.takeIf { it.isNotBlank() }
                ?: defaults.defaultChannelId,
            defaultChannelName = map.string("defaultChannelName")?.takeIf { it.isNotBlank() },
            foregroundDisplay = foregroundPolicy(map.string("foregroundDisplay"))
                ?: defaults.foregroundDisplay,
            displayNotifications = map.bool("displayNotifications")
                ?: defaults.displayNotifications,
            collectDeviceInfo = map.bool("collectDeviceInfo") ?: defaults.collectDeviceInfo,
            smallIconResId = drawable(context, map.string("androidNotificationIcon")),
            backend = backend(map["backend"])
        )
    }

    /**
     * Resolves the application's own drawable by name, so Dart can choose the notification icon
     * without a resource id. Looked up in `drawable`, then `mipmap`; 0, meaning "use the launcher
     * icon", when the name is unset or matches nothing.
     */
    private fun drawable(context: Context, name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val resources = context.resources
        val id = resources.getIdentifier(name, "drawable", context.packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(name, "mipmap", context.packageName)
        if (id == 0) Log.w("ARYPush", "androidNotificationIcon '$name' not found; using the launcher icon")
        return id
    }

    private fun backend(value: Any?): PushBackendConfig? {
        val map = value as? Map<*, *> ?: return null
        val baseUrl = map.string("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            PushBackendConfig(
                baseUrl = baseUrl,
                applicationId = map.string("applicationId")?.takeIf { it.isNotBlank() },
                projectId = map.string("projectId")?.takeIf { it.isNotBlank() },
                authToken = map.string("authToken")?.takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    private fun logLevel(value: String?): PushLogLevel? = when (value?.lowercase()) {
        "verbose" -> PushLogLevel.VERBOSE
        "debug" -> PushLogLevel.DEBUG
        "info" -> PushLogLevel.INFO
        "warning" -> PushLogLevel.WARN
        "error" -> PushLogLevel.ERROR
        "none" -> PushLogLevel.NONE
        else -> null
    }

    private fun foregroundPolicy(value: String?): ForegroundDisplayPolicy? =
        when (value?.lowercase()) {
            "show" -> ForegroundDisplayPolicy.SHOW
            "eventonly" -> ForegroundDisplayPolicy.EVENT_ONLY
            "suppress" -> ForegroundDisplayPolicy.SUPPRESS
            else -> null
        }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.bool(key: String): Boolean? = this[key] as? Boolean
}

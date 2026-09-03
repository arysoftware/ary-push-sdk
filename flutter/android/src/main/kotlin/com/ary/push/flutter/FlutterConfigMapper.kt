package com.ary.push.flutter

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

    fun from(arguments: Any?): ARYPushConfig {
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
            backend = backend(map["backend"])
        )
    }

    private fun backend(value: Any?): PushBackendConfig? {
        val map = value as? Map<*, *> ?: return null
        val baseUrl = map.string("baseUrl")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            PushBackendConfig(
                baseUrl = baseUrl,
                applicationId = map.string("applicationId")?.takeIf { it.isNotBlank() },
                apiVersion = map.string("apiVersion")?.takeIf { it.isNotBlank() } ?: "v1"
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

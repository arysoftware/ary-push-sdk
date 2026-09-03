package com.ary.push.internal.log

import android.util.Log
import com.ary.push.PushLogLevel

/**
 * The SDK's logger.
 *
 * Two rules, both enforced here rather than left to call sites:
 *
 *  1. Silent unless the host application opts in. A library that writes to logcat by default is
 *     a library that leaks its users' data into bug reports.
 *  2. Secrets never reach logcat. Tokens and authorization headers are masked by [mask] before
 *     they are formatted, so a careless call site cannot leak one.
 */
internal object PushLogger {

    private const val TAG = "ARYPush"

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var minLevel: PushLogLevel = PushLogLevel.INFO

    fun configure(enabled: Boolean, level: PushLogLevel) {
        this.enabled = enabled && level != PushLogLevel.NONE
        this.minLevel = level
    }

    val isEnabled: Boolean get() = enabled

    fun v(message: () -> String) = log(PushLogLevel.VERBOSE, null, message)
    fun d(message: () -> String) = log(PushLogLevel.DEBUG, null, message)
    fun i(message: () -> String) = log(PushLogLevel.INFO, null, message)
    fun w(throwable: Throwable? = null, message: () -> String) =
        log(PushLogLevel.WARN, throwable, message)

    fun e(throwable: Throwable? = null, message: () -> String) =
        log(PushLogLevel.ERROR, throwable, message)

    private fun log(level: PushLogLevel, throwable: Throwable?, message: () -> String) {
        if (!enabled || level.priority < minLevel.priority) return
        // The message lambda is only invoked when the line will actually be emitted, so string
        // building costs nothing in release builds.
        val text = runCatching(message).getOrElse { "<log message threw ${it::class.simpleName}>" }
        val line = "[ARYPush] $text"
        when (level) {
            PushLogLevel.VERBOSE -> Log.v(TAG, line, throwable)
            PushLogLevel.DEBUG -> Log.d(TAG, line, throwable)
            PushLogLevel.INFO -> Log.i(TAG, line, throwable)
            PushLogLevel.WARN -> Log.w(TAG, line, throwable)
            PushLogLevel.ERROR -> Log.e(TAG, line, throwable)
            PushLogLevel.NONE -> Unit
        }
    }

    /**
     * Renders a sensitive value as a short, non-reversible hint.
     *
     * Enough to tell two tokens apart in a bug report, never enough to use one.
     */
    fun mask(secret: String?): String = when {
        secret == null -> "null"
        secret.isEmpty() -> "empty"
        secret.length <= 8 -> "***(${secret.length})"
        else -> "${secret.take(4)}***${secret.takeLast(2)}(${secret.length})"
    }

    /** Header names whose values must never be written to logcat. */
    private val REDACTED_HEADERS = setOf("authorization", "proxy-authorization", "cookie")

    /** Formats headers for logging with credential values removed. */
    fun safeHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString(prefix = "{", postfix = "}") { (name, value) ->
            val shown = if (name.lowercase() in REDACTED_HEADERS) "<redacted>" else value
            "$name=$shown"
        }
}

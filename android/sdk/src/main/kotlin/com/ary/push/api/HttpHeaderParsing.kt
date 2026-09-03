package com.ary.push.api

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Parsing helpers shared by REST client implementations. */
internal object HttpHeaderParsing {

    private const val MAX_SANE_RETRY_AFTER_MS = 24L * 60 * 60 * 1000

    /**
     * Parses `Retry-After`, which RFC 9110 allows to be either delta-seconds or an HTTP date.
     *
     * Returns null for absent or unparseable values, and for values far enough in the future to
     * be a server bug rather than an instruction.
     */
    fun parseRetryAfterMillis(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null

        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return (seconds * 1000).coerceAtMost(MAX_SANE_RETRY_AFTER_MS)
        }

        val date = runCatching { httpDateFormat().parse(raw) }.getOrNull() ?: return null
        val delta = date.time - nowMs
        return if (delta <= 0) 0L else delta.coerceAtMost(MAX_SANE_RETRY_AFTER_MS)
    }

    private fun httpDateFormat(): SimpleDateFormat =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

    /** Formats an instant as an HTTP date. Used by tests and by request diagnostics. */
    fun formatHttpDate(epochMillis: Long): String = httpDateFormat().format(Date(epochMillis))
}

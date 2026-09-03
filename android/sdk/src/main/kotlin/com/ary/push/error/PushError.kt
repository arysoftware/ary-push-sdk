package com.ary.push.error

/**
 * Structured SDK errors.
 *
 * These are reported through logs and listener callbacks. The SDK never throws them across a
 * public API boundary as a crash: a push or network failure must not take the host application
 * down with it.
 */
public sealed class PushError(
    message: String,
    public val cause: Throwable? = null
) {
    /** Human-readable description, safe to log. */
    public val message: String = message

    /** The SDK was used before, or failed during, initialization. */
    public class Initialization(message: String, cause: Throwable? = null) :
        PushError(message, cause)

    /** Notification permission could not be requested or read. */
    public class Permission(message: String, cause: Throwable? = null) :
        PushError(message, cause)

    /** A push token could not be obtained, refreshed or deleted. */
    public class Token(message: String, cause: Throwable? = null) :
        PushError(message, cause)

    /** A message could not be parsed or rendered. */
    public class Notification(message: String, cause: Throwable? = null) :
        PushError(message, cause)

    /** Local persistence failed. */
    public class Storage(message: String, cause: Throwable? = null) :
        PushError(message, cause)

    /** The request never reached the backend. */
    public class Network(message: String, cause: Throwable? = null) :
        PushError(message, cause)

    /** The backend answered with an error. */
    public class Backend(
        message: String,
        public val statusCode: Int? = null,
        public val code: String? = null,
        cause: Throwable? = null
    ) : PushError(message, cause)

    override fun toString(): String {
        val type = this::class.simpleName
        val suffix = cause?.let { " (cause: ${it::class.simpleName}: ${it.message})" }.orEmpty()
        return "PushError.$type: $message$suffix"
    }
}

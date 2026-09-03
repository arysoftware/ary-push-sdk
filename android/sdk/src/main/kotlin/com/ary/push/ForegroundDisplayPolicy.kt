package com.ary.push

/**
 * What the SDK does with a notification that arrives while the application is in the foreground.
 *
 * Background and terminated messages are always rendered by the system or by the SDK; this
 * policy only affects the foreground case, which is the only case an application can control.
 */
public enum class ForegroundDisplayPolicy {

    /** Render the notification and emit `onNotificationReceived`. This is the default. */
    SHOW,

    /**
     * Do not render anything, but still emit `onNotificationReceived`.
     *
     * Use this when the host application already shows its own in-app banner, so that the user
     * never sees the same message twice.
     */
    EVENT_ONLY,

    /**
     * Do not render anything and do not emit an event.
     *
     * Use this only when the host application handles the raw payload itself, for example
     * through its own `FirebaseMessagingService`.
     */
    SUPPRESS
}

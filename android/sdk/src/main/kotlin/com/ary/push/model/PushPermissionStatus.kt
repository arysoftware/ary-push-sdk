package com.ary.push.model

/**
 * Notification permission state, normalised across Android and iOS.
 *
 * Android only ever reports [NOT_DETERMINED], [GRANTED] or [DENIED]. [PROVISIONAL],
 * [EPHEMERAL] and [RESTRICTED] exist so that the same model can describe iOS, keeping host
 * application code portable.
 */
public enum class PushPermissionStatus {

    /** The user has not been asked yet. */
    NOT_DETERMINED,

    /** Notifications may be posted. */
    GRANTED,

    /** The user declined, or notifications are switched off for the application. */
    DENIED,

    /** iOS only: quiet delivery granted without an explicit prompt. */
    PROVISIONAL,

    /** iOS only: temporary authorization granted to an App Clip. */
    EPHEMERAL,

    /** iOS only: notifications are restricted by policy and cannot be requested. */
    RESTRICTED;

    /** True when the SDK is allowed to post notifications, quietly or otherwise. */
    public val isAuthorized: Boolean
        get() = this == GRANTED || this == PROVISIONAL || this == EPHEMERAL
}

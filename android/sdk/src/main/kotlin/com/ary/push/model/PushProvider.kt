package com.ary.push.model

/** Transport that issued the current push token. */
public enum class PushProvider(public val wireValue: String) {

    /** Firebase Cloud Messaging. */
    FCM("fcm"),

    /** Apple Push Notification service, used directly without Firebase. */
    APNS("apns");

    public companion object {
        /** Parses a persisted or wire value, defaulting to [FCM] on Android. */
        public fun fromWire(value: String?): PushProvider =
            entries.firstOrNull { it.wireValue == value } ?: FCM
    }
}

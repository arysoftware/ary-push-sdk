package com.ary.push.model

/**
 * A push-related analytics event.
 *
 * The SDK is not a general analytics product: events exist so that notification delivery and
 * engagement can be attributed on the push backend. Keep them focused on push.
 */
public data class PushEvent(
    /** Event name, e.g. `notification_opened`. */
    public val name: String,

    /** Arbitrary, non-sensitive properties. */
    public val properties: Map<String, String> = emptyMap(),

    /** When the event occurred, in epoch milliseconds. */
    public val occurredAt: Long = System.currentTimeMillis()
)

package com.ary.push.model

/**
 * A backend-defined group this installation belongs to.
 *
 * Segments are computed on the server from the tags, user identity and device attributes the SDK
 * reports. The SDK never evaluates a segment rule, and this type is read-only for exactly that
 * reason: "Premium Pakistan Users" means `subscription == premium AND country == PK` today and
 * something else next quarter, and a rule compiled into a shipped app cannot follow that.
 *
 * The flow is one-directional:
 *
 * ```
 * addTags(...)  ->  backend recomputes membership  ->  getSegments() reads it back
 * ```
 *
 * To change which segments a device lands in, change its tags.
 */
public data class Segment(
    /** Stable backend identifier. */
    public val id: String,

    /** Human-readable name as defined on the backend, e.g. `Premium Pakistan Users`. */
    public val name: String,

    /** Optional description, when the backend supplies one. */
    public val description: String? = null,

    /** When this installation entered the segment, in epoch milliseconds, when known. */
    public val joinedAt: Long? = null
) {
    /** Flat representation used by the Flutter bridge. */
    public fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "description" to description,
        "joinedAt" to joinedAt
    )
}

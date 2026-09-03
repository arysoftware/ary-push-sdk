package com.ary.push

/**
 * Transport-level timeouts for backend synchronisation.
 *
 * Defaults are deliberately short: backend synchronisation is secondary to push delivery, and a
 * slow network must never keep SDK work alive longer than necessary.
 */
public data class NetworkConfig @JvmOverloads constructor(
    public val connectTimeoutMs: Long = 10_000L,
    public val readTimeoutMs: Long = 15_000L,
    public val writeTimeoutMs: Long = 15_000L,

    /** Upper bound for a whole call including redirects and retries. */
    public val callTimeoutMs: Long = 30_000L
) {
    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(writeTimeoutMs > 0) { "writeTimeoutMs must be positive" }
        require(callTimeoutMs > 0) { "callTimeoutMs must be positive" }
    }
}

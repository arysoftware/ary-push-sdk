package com.ary.push

/** Verbosity of the SDK logger. Ordered from most to least verbose. */
public enum class PushLogLevel(internal val priority: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),

    /** Emits nothing at all. */
    NONE(Int.MAX_VALUE)
}

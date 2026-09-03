import Foundation

/// A digest that is identical in every process.
///
/// Swift's `hashValue` is seeded per process launch, so two runs of the same app hash the same
/// string differently. That is fine for in-memory dictionaries and completely wrong for anything
/// the SDK persists: a notification identity derived from `hashValue` would fail to deduplicate
/// after a relaunch, and a registration hash would report "changed" on every cold start.
///
/// FNV-1a is used because it is stable, short, dependency-free and sufficient here. Nothing in
/// the SDK relies on it being cryptographic.
enum StableHash {

    static func digest(_ value: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x0000_0100_0000_01b3
        }
        return String(hash, radix: 16)
    }
}

import Foundation

/// A backend-defined group this installation belongs to.
///
/// Segments are computed on the server from the tags, user identity and device attributes the
/// SDK reports. The SDK never evaluates a segment rule, and this type is read-only for exactly
/// that reason: "Premium Pakistan Users" means `subscription == premium AND country == PK` today
/// and something else next quarter, and a rule compiled into a shipped app cannot follow that.
///
/// The flow is one-directional:
///
/// ```
/// addTags(...)  ->  backend recomputes membership  ->  getSegments() reads it back
/// ```
///
/// To change which segments a device lands in, change its tags.
public struct Segment: Equatable {

    /// Stable backend identifier.
    public let id: String

    /// Human-readable name as defined on the backend, e.g. `Premium Pakistan Users`.
    public let name: String

    /// Optional description, when the backend supplies one.
    public let description: String?

    /// When this installation entered the segment, when the backend reports it.
    public let joinedAt: Date?

    public init(id: String, name: String, description: String? = nil, joinedAt: Date? = nil) {
        self.id = id
        self.name = name
        self.description = description
        self.joinedAt = joinedAt
    }

    /// Flat representation used by the Flutter bridge.
    public func toDictionary() -> [String: Any] {
        var result: [String: Any] = ["id": id, "name": name]
        if let description { result["description"] = description }
        if let joinedAt { result["joinedAt"] = Int(joinedAt.timeIntervalSince1970 * 1000) }
        return result
    }

    /// Parses one entry of the segments payload, returning nil for anything unusable.
    static func from(json: [String: Any]) -> Segment? {
        guard let id = json["id"] as? String, !id.isEmpty else { return nil }
        let joinedAt = (json["joinedAt"] as? TimeInterval).map { millis in
            Date(timeIntervalSince1970: millis / 1000)
        }
        return Segment(
            id: id,
            name: (json["name"] as? String) ?? id,
            description: json["description"] as? String,
            joinedAt: joinedAt
        )
    }
}

import Foundation

/// The operations the SDK can defer.
///
/// The declaration order is also dependency order: the queue never sends an operation before the
/// one it depends on, because a token update or an identify call is meaningless to a backend
/// that has not been told the installation exists yet.
enum OperationType: String, CaseIterable {
    case registerInstallation
    case updateToken
    case identifyUser
    case logoutUser
    case updateTags
    case removeTags
    case updateTopics
    case updatePermission
    case trackEvents

    var order: Int {
        Self.allCases.firstIndex(of: self) ?? Self.allCases.count
    }

    /// Whether a newly enqueued operation of this type supersedes an identical pending one.
    ///
    /// Token, identity, topics and permission are "latest value wins" state, so keeping older
    /// copies would send known-stale data. Tag writes merge instead, and events accumulate.
    var isLatestValueWins: Bool {
        switch self {
        case .registerInstallation, .updateToken, .identifyUser, .logoutUser,
             .updateTopics, .updatePermission:
            return true
        case .updateTags, .removeTags, .trackEvents:
            return false
        }
    }
}

/// One durable unit of backend synchronisation.
///
/// Operations are persisted, so they must be representable as plain JSON: no closures, no object
/// references, nothing a process restart would lose.
struct PendingOperation: Equatable {

    enum Key {
        static let token = "token"
        static let provider = "provider"
        static let userId = "userId"
        static let tags = "tags"
        static let tagKeys = "tagKeys"
        static let removeAll = "removeAll"
        static let topics = "topics"
        static let enabled = "enabled"
        static let events = "events"
    }

    let id: String
    let type: OperationType
    /// Operation arguments. Values are strings, or JSON-encoded strings for nested data.
    let payload: [String: String]
    let createdAt: Date
    /// Attempts already made. Persisted so backoff survives a restart.
    let attempts: Int

    init(
        id: String = UUID().uuidString,
        type: OperationType,
        payload: [String: String] = [:],
        createdAt: Date = Date(),
        attempts: Int = 0
    ) {
        self.id = id
        self.type = type
        self.payload = payload
        self.createdAt = createdAt
        self.attempts = attempts
    }

    func withAttempts(_ attempts: Int) -> PendingOperation {
        PendingOperation(
            id: id,
            type: type,
            payload: payload,
            createdAt: createdAt,
            attempts: attempts
        )
    }

    func withPayload(_ payload: [String: String], attempts: Int) -> PendingOperation {
        PendingOperation(
            id: id,
            type: type,
            payload: payload,
            createdAt: createdAt,
            attempts: attempts
        )
    }

    func toDictionary() -> [String: Any] {
        [
            "id": id,
            "type": type.rawValue,
            "payload": payload,
            "createdAt": createdAt.timeIntervalSince1970,
            "attempts": attempts
        ]
    }

    /// Returns `nil` for entries that cannot be understood, so one bad row, or a queue written by
    /// a newer SDK version, cannot wedge the queue permanently.
    static func from(dictionary: [String: Any]) -> PendingOperation? {
        guard let rawType = dictionary["type"] as? String,
              let type = OperationType(rawValue: rawType)
        else { return nil }

        let createdAt = (dictionary["createdAt"] as? TimeInterval)
            .map(Date.init(timeIntervalSince1970:)) ?? Date()

        return PendingOperation(
            id: dictionary["id"] as? String ?? UUID().uuidString,
            type: type,
            payload: dictionary["payload"] as? [String: String] ?? [:],
            createdAt: createdAt,
            attempts: dictionary["attempts"] as? Int ?? 0
        )
    }
}

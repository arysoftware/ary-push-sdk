import Foundation

/// Push-related event reporting.
///
/// Scope is intentionally narrow. This is not an analytics SDK and must not grow into one: the
/// events that belong here describe what happened to a notification, so that delivery and
/// engagement can be attributed on the push backend. Screen views, purchases and funnels belong
/// to whatever analytics product the host application already uses.
final class EventManager {

    static let notificationReceived = "notification_received"
    static let notificationOpened = "notification_opened"

    private static let maxProperties = 25
    private static let maxValueLength = 256

    private let onEvent: (PushEvent) -> Void

    init(onEvent: @escaping (PushEvent) -> Void) {
        self.onEvent = onEvent
    }

    func track(_ name: String, properties: [String: String] = [:]) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            PushLogger.warn("Ignoring event with a blank name")
            return
        }

        if properties.count > Self.maxProperties {
            PushLogger.warn(
                "Event \(trimmed) carries \(properties.count) properties; only the first "
                    + "\(Self.maxProperties) are sent"
            )
        }

        let trimmedProperties = properties
            .sorted { $0.key < $1.key }
            .prefix(Self.maxProperties)
            .reduce(into: [String: String]()) { result, entry in
                result[entry.key] = String(entry.value.prefix(Self.maxValueLength))
            }

        PushLogger.debug("Event tracked: \(trimmed)")
        onEvent(PushEvent(name: trimmed, properties: trimmedProperties))
    }

    /// Emitted by the SDK itself when a notification is opened.
    func trackNotificationOpened(id: String, actionId: String?) {
        var properties = ["notificationId": id]
        if let actionId { properties["actionId"] = actionId }
        track(Self.notificationOpened, properties: properties)
    }

    /// Emitted by the SDK itself when a notification is received and not suppressed.
    func trackNotificationReceived(id: String, foreground: Bool) {
        track(
            Self.notificationReceived,
            properties: ["notificationId": id, "foreground": String(foreground)]
        )
    }
}

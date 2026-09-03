import Foundation

/// Platform-neutral representation of a push notification.
///
/// APNs payloads, Android FCM payloads and Flutter events are all mapped into this shape, so
/// host application code is identical on every platform.
///
/// The SDK never interprets ``data``. It is delivered verbatim, and the host application decides
/// what an action means and where to navigate.
public struct PushNotification: Equatable {

    /// Stable identifier used for deduplication. Taken from the APNs `apns-collapse-id`, an
    /// explicit `notification_id` payload key, or a hash of the payload when neither is present.
    public let id: String

    /// Notification title, when the payload carries one.
    public let title: String?

    /// Notification body, when the payload carries one.
    public let body: String?

    /// Remote image to render as an attachment, when the payload carries one.
    public let imageURL: String?

    /// The custom data payload, delivered verbatim, with the APNs `aps` block removed.
    public let data: [String: String]

    /// When the SDK received the message.
    public let receivedAt: Date

    /// Thread identifier, which iOS uses to group notifications.
    public let threadIdentifier: String?

    /// APNs category, which selects the registered action buttons.
    public let categoryIdentifier: String?

    /// Identifier of the action button the user tapped.
    ///
    /// `nil` when the notification body itself was tapped, and always `nil` on
    /// notification-received events.
    public let actionId: String?

    /// True when the message arrived while the application was in the foreground.
    public let wasForeground: Bool

    public init(
        id: String,
        title: String? = nil,
        body: String? = nil,
        imageURL: String? = nil,
        data: [String: String] = [:],
        receivedAt: Date = Date(),
        threadIdentifier: String? = nil,
        categoryIdentifier: String? = nil,
        actionId: String? = nil,
        wasForeground: Bool = false
    ) {
        self.id = id
        self.title = title
        self.body = body
        self.imageURL = imageURL
        self.data = data
        self.receivedAt = receivedAt
        self.threadIdentifier = threadIdentifier
        self.categoryIdentifier = categoryIdentifier
        self.actionId = actionId
        self.wasForeground = wasForeground
    }

    /// Conventional payload key carrying a host-defined action name.
    public static let actionKey = "action"

    /// Convenience accessor for the conventional `action` payload key.
    public var action: String? { data[Self.actionKey] }

    /// Flat representation used by the Flutter bridge and by persistence.
    public func toDictionary() -> [String: Any] {
        var result: [String: Any] = [
            "id": id,
            "data": data,
            "receivedAt": Int(receivedAt.timeIntervalSince1970 * 1000),
            "wasForeground": wasForeground
        ]
        if let title { result["title"] = title }
        if let body { result["body"] = body }
        if let imageURL { result["imageUrl"] = imageURL }
        if let threadIdentifier { result["threadId"] = threadIdentifier }
        if let categoryIdentifier { result["categoryId"] = categoryIdentifier }
        if let actionId { result["actionId"] = actionId }
        return result
    }

    /// Returns a copy with the action identifier replaced.
    public func withAction(_ actionId: String?) -> PushNotification {
        PushNotification(
            id: id,
            title: title,
            body: body,
            imageURL: imageURL,
            data: data,
            receivedAt: receivedAt,
            threadIdentifier: threadIdentifier,
            categoryIdentifier: categoryIdentifier,
            actionId: actionId,
            wasForeground: wasForeground
        )
    }
}

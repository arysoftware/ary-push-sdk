import Foundation

/// Serialises a ``PushNotification`` so it can outlive the process.
///
/// Needed for the pending-open record, which has to survive on disk until host or Dart listeners
/// attach. JSON rather than `NSCoding` because the same shape is read back by the Flutter bridge.
enum NotificationCodec {

    static func encode(_ notification: PushNotification) -> String? {
        var payload: [String: Any] = [
            "id": notification.id,
            "data": notification.data,
            "receivedAt": notification.receivedAt.timeIntervalSince1970,
            "wasForeground": notification.wasForeground
        ]
        if let title = notification.title { payload["title"] = title }
        if let body = notification.body { payload["body"] = body }
        if let imageURL = notification.imageURL { payload["imageUrl"] = imageURL }
        if let thread = notification.threadIdentifier { payload["threadId"] = thread }
        if let category = notification.categoryIdentifier { payload["categoryId"] = category }
        if let actionId = notification.actionId { payload["actionId"] = actionId }

        guard let data = try? JSONSerialization.data(withJSONObject: payload) else {
            PushLogger.warn("Could not encode a notification for persistence")
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    /// Returns `nil` for anything unreadable rather than throwing into a system callback.
    static func decode(_ raw: String?) -> PushNotification? {
        guard let raw,
              let data = raw.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["id"] as? String
        else { return nil }

        let receivedAt = (json["receivedAt"] as? TimeInterval).map(Date.init(timeIntervalSince1970:))
        return PushNotification(
            id: id,
            title: json["title"] as? String,
            body: json["body"] as? String,
            imageURL: json["imageUrl"] as? String,
            data: json["data"] as? [String: String] ?? [:],
            receivedAt: receivedAt ?? Date(),
            threadIdentifier: json["threadId"] as? String,
            categoryIdentifier: json["categoryId"] as? String,
            actionId: json["actionId"] as? String,
            wasForeground: json["wasForeground"] as? Bool ?? false
        )
    }
}

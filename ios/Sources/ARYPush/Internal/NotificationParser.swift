import Foundation
import UserNotifications

/// Maps an APNs payload onto the platform-neutral ``PushNotification``.
///
/// The same payload reaches the SDK through three different callbacks, and this is where they
/// are made to look identical:
///
/// * `userNotificationCenter(_:willPresent:)` for a foreground notification;
/// * `userNotificationCenter(_:didReceive:)` for a tap, including from a terminated launch;
/// * `application(_:didReceiveRemoteNotification:)` for a silent, content-available message.
enum NotificationParser {

    private static let apsKey = "aps"
    private static let notificationIdKeys = ["notification_id", "id", "gcm.message_id"]

    /// Payload keys added by Google's SDKs that are transport plumbing, not application data.
    private static let reservedPrefixes = ["gcm.", "google.", "aps"]

    static func parse(
        userInfo: [AnyHashable: Any],
        wasForeground: Bool,
        actionId: String? = nil
    ) -> PushNotification {
        let aps = userInfo[apsKey] as? [String: Any] ?? [:]
        let alert = aps["alert"]

        var title: String?
        var body: String?
        if let alertString = alert as? String {
            body = alertString
        } else if let alertDict = alert as? [String: Any] {
            title = alertDict["title"] as? String
            body = (alertDict["body"] as? String) ?? (alertDict["subtitle"] as? String)
        }

        let data = customData(from: userInfo)

        return PushNotification(
            id: resolveId(userInfo: userInfo, data: data),
            title: title ?? data["title"],
            body: body ?? data["body"],
            imageURL: imageURL(userInfo: userInfo, data: data),
            data: data,
            receivedAt: Date(),
            threadIdentifier: aps["thread-id"] as? String,
            categoryIdentifier: aps["category"] as? String,
            actionId: actionId,
            wasForeground: wasForeground
        )
    }

    /// Maps a delivered `UNNotification`, preserving the identifier iOS assigned it.
    static func parse(
        notification: UNNotification,
        wasForeground: Bool,
        actionId: String? = nil
    ) -> PushNotification {
        let parsed = parse(
            userInfo: notification.request.content.userInfo,
            wasForeground: wasForeground,
            actionId: actionId
        )
        let content = notification.request.content
        return PushNotification(
            id: parsed.id,
            title: parsed.title ?? content.title.nonEmpty,
            body: parsed.body ?? content.body.nonEmpty,
            imageURL: parsed.imageURL,
            data: parsed.data,
            receivedAt: notification.date,
            threadIdentifier: parsed.threadIdentifier ?? content.threadIdentifier.nonEmpty,
            categoryIdentifier: parsed.categoryIdentifier ?? content.categoryIdentifier.nonEmpty,
            actionId: actionId,
            wasForeground: wasForeground
        )
    }

    /// The application-visible payload, with transport plumbing removed.
    static func customData(from userInfo: [AnyHashable: Any]) -> [String: String] {
        var result: [String: String] = [:]
        for (rawKey, value) in userInfo {
            guard let key = rawKey as? String else { continue }
            guard !reservedPrefixes.contains(where: { key == $0 || key.hasPrefix($0) }) else {
                continue
            }
            if let string = value as? String {
                result[key] = string
            } else if let data = try? JSONSerialization.data(
                withJSONObject: JSONSanitizer.sanitize(value)
            ), let encoded = String(data: data, encoding: .utf8) {
                // Nested objects are handed over as JSON rather than dropped, so a host
                // application can still read them.
                result[key] = encoded
            } else {
                result[key] = String(describing: value)
            }
        }
        return result
    }

    /// A stable identity for deduplication.
    ///
    /// Preference order matters. A sender-supplied id groups a logical message across resends;
    /// the transport's own id is unique per delivery attempt; the content hash is the last
    /// resort for senders that supply neither, and is what makes deduplication work for them.
    static func resolveId(userInfo: [AnyHashable: Any], data: [String: String]) -> String {
        for key in notificationIdKeys {
            if let value = (userInfo[key] as? String) ?? data[key], !value.isEmpty {
                return value
            }
        }
        return contentHash(userInfo: userInfo)
    }

    private static func imageURL(
        userInfo: [AnyHashable: Any],
        data: [String: String]
    ) -> String? {
        if let options = userInfo["fcm_options"] as? [String: Any],
           let image = options["image"] as? String {
            return image
        }
        return data["image_url"] ?? data["image"]
    }

    private static func contentHash(userInfo: [AnyHashable: Any]) -> String {
        let fingerprint = userInfo
            .compactMap { key, value -> String? in
                guard let key = key as? String else { return nil }
                return "\(key)=\(String(describing: value))"
            }
            .sorted()
            .joined(separator: ";")
        return "hash-\(StableHash.digest(fingerprint))"
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}

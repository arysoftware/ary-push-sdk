import Foundation

/// Transport that issued the current push token.
///
/// On iOS these are genuinely different values for the same device, and confusing them is a
/// classic source of "the token looks fine but nothing is delivered": an APNs device token is a
/// hex string issued by Apple, while an FCM registration token is issued by Google *after* it has
/// been given the APNs token. A backend that stores one and sends through the other reaches
/// nobody.
public enum PushProvider: String, Equatable {

    /// Apple Push Notification service, used directly.
    case apns

    /// Firebase Cloud Messaging, layered on top of APNs.
    case fcm

    /// Parses a persisted or wire value, defaulting to APNs on iOS.
    public static func fromWire(_ value: String?) -> PushProvider {
        guard let value, let provider = PushProvider(rawValue: value) else { return .apns }
        return provider
    }

    public var wireValue: String { rawValue }
}

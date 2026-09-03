import Foundation
import UserNotifications

/// Notification permission state, normalised across iOS and Android.
///
/// `UNAuthorizationStatus` has states Android does not (`provisional`, `ephemeral`) and Android
/// has behaviour iOS does not, so both are mapped onto this one model. Host application code
/// then reads the same values on every platform.
public enum PushPermissionStatus: String, Equatable {

    /// The user has not been asked yet.
    case notDetermined

    /// Notifications may be presented.
    case granted

    /// The user declined, or notifications are switched off for the application.
    case denied

    /// Quiet delivery granted without an explicit prompt.
    case provisional

    /// Temporary authorization granted to an App Clip.
    case ephemeral

    /// Restricted by policy; a prompt is not possible.
    case restricted

    /// True when the SDK is allowed to present notifications, quietly or otherwise.
    public var isAuthorized: Bool {
        self == .granted || self == .provisional || self == .ephemeral
    }

    /// Maps the platform value onto the shared model.
    static func from(_ status: UNAuthorizationStatus) -> PushPermissionStatus {
        switch status {
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .authorized: return .granted
        case .provisional: return .provisional
        case .ephemeral: return .ephemeral
        @unknown default:
            // A future iOS state the SDK has not seen. Reporting `denied` is the safe answer:
            // it never claims a capability the SDK might not have.
            return .denied
        }
    }
}

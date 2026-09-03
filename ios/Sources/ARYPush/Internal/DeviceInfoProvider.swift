import Foundation
import UIKit

/// Collects the small, documented set of device facts the backend needs to target a notification.
///
/// Deliberately narrow. Nothing here identifies a person, and nothing is read that would require
/// a permission or a privacy manifest entry beyond the SDK's declared use: no IDFA, no
/// `identifierForVendor`, no contacts, no location. Every field below is listed in
/// docs/SECURITY.md so that a privacy review can be done against the documentation rather than
/// against the source.
final class DeviceInfoProvider {

    /// The SDK version, kept in one place so it can never drift from the released tag.
    static let sdkVersion = "1.0.0"

    private let bundle: Bundle

    init(bundle: Bundle = .main) {
        self.bundle = bundle
    }

    /// Host application version, e.g. `5.2.0`.
    var appVersion: String? {
        bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
    }

    /// Host application build number.
    var appBuild: String? {
        bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String
    }

    var sdkVersion: String { Self.sdkVersion }

    /// iOS release version, e.g. `17.4`.
    var osVersion: String { UIDevice.current.systemVersion }

    /// Hardware identifier, e.g. `iPhone16,1`.
    ///
    /// `UIDevice.model` only ever returns "iPhone", which is useless for targeting, so the
    /// machine identifier is read from `uname` instead. It describes a model, not a device, and
    /// cannot be used to identify a person.
    var deviceModel: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let identifier = withUnsafePointer(to: &systemInfo.machine) { pointer in
            pointer.withMemoryRebound(to: CChar.self, capacity: 1) { String(validatingUTF8: $0) }
        }
        return identifier?.trimmingCharacters(in: .whitespacesAndNewlines)
            ?? UIDevice.current.model
    }

    /// Current BCP-47 locale, e.g. `en-PK`. Read on each access so a change is picked up.
    var locale: String { Locale.current.identifier.replacingOccurrences(of: "_", with: "-") }

    /// IANA timezone, e.g. `Asia/Karachi`.
    var timezone: String { TimeZone.current.identifier }

    /// The host application's bundle identifier, which is also its public App Store identity.
    var bundleIdentifier: String { bundle.bundleIdentifier ?? "unknown" }
}

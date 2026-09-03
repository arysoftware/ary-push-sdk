import Foundation

/// Builds a ``ARYPushConfig`` from the host application's `Info.plist`.
///
/// This exists so an application can configure the SDK without touching its app delegate, which
/// matters when the SDK is dropped into a large existing app whose startup path is contested,
/// and so that a process launched by a silent notification still knows which backend to use
/// before any host code has run.
///
/// Expected shape:
///
/// ```xml
/// <key>ARYPush</key>
/// <dict>
///     <key>BackendBaseURL</key><string>https://push-api.ary.com</string>
///     <key>ApplicationId</key><string>wallet_ios</string>
///     <key>EnableLogging</key><false/>
/// </dict>
/// ```
///
/// Values passed to `ARYPush.initialize(_:)` always win over these.
enum InfoPlistConfigReader {

    private static let rootKey = "ARYPush"

    static func read(bundle: Bundle = .main) -> ARYPushConfig {
        guard let dictionary = bundle.object(forInfoDictionaryKey: rootKey) as? [String: Any] else {
            return ARYPushConfig()
        }

        var backend: PushBackendConfig?
        if let baseURL = dictionary["BackendBaseURL"] as? String, !baseURL.isEmpty {
            backend = PushBackendConfig(
                baseURL: baseURL,
                applicationId: dictionary["ApplicationId"] as? String,
                apiVersion: dictionary["BackendApiVersion"] as? String ?? "v1"
            )
        }

        return ARYPushConfig(
            enableLogging: dictionary["EnableLogging"] as? Bool ?? false,
            logLevel: logLevel(dictionary["LogLevel"] as? String) ?? .info,
            autoRequestPermission: dictionary["AutoRequestPermission"] as? Bool ?? false,
            autoRegisterForRemoteNotifications:
                dictionary["AutoRegisterForRemoteNotifications"] as? Bool ?? true,
            foregroundDisplay: foregroundPolicy(dictionary["ForegroundDisplay"] as? String) ?? .show,
            backend: backend,
            collectDeviceInfo: dictionary["CollectDeviceInfo"] as? Bool ?? true,
            proxyNotificationCenterDelegate:
                dictionary["ProxyNotificationCenterDelegate"] as? Bool ?? true,
            proxyApplicationDelegate: dictionary["ProxyApplicationDelegate"] as? Bool ?? true
        )
    }

    private static func logLevel(_ raw: String?) -> PushLogLevel? {
        switch raw?.lowercased() {
        case "verbose": return .verbose
        case "debug": return .debug
        case "info": return .info
        case "warning": return .warning
        case "error": return .error
        case "none": return PushLogLevel.none
        default: return nil
        }
    }

    private static func foregroundPolicy(_ raw: String?) -> ForegroundDisplayPolicy? {
        switch raw?.lowercased() {
        case "show": return .show
        case "eventonly", "event_only": return .eventOnly
        case "suppress": return .suppress
        default: return nil
        }
    }
}

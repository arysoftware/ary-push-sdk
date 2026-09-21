import Foundation
import UIKit
import UserNotifications

/// What the SDK does with a notification that arrives while the application is in the foreground.
public enum ForegroundDisplayPolicy {
    /// Present the notification banner and emit `onNotificationReceived`. The default.
    case show
    /// Present nothing, but still emit `onNotificationReceived`.
    case eventOnly
    /// Present nothing and emit nothing.
    case suppress
}

/// Verbosity of the SDK logger.
public enum PushLogLevel: Int, Comparable {
    case verbose = 0
    case debug = 1
    case info = 2
    case warning = 3
    case error = 4
    case none = 99

    public static func < (lhs: PushLogLevel, rhs: PushLogLevel) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

/// Transport-level timeouts for backend synchronisation.
public struct NetworkConfig {
    public let requestTimeout: TimeInterval
    public let resourceTimeout: TimeInterval

    public init(requestTimeout: TimeInterval = 15, resourceTimeout: TimeInterval = 30) {
        self.requestTimeout = requestTimeout
        self.resourceTimeout = resourceTimeout
    }
}

/// Exponential backoff with jitter, used by the sync queue and by transient HTTP failures.
public struct RetryConfig {
    public let maxAttempts: Int
    public let initialBackoff: TimeInterval
    public let maxBackoff: TimeInterval
    public let backoffMultiplier: Double
    public let jitterFactor: Double
    public let respectRetryAfter: Bool
    public let maxRetryAfter: TimeInterval

    public init(
        maxAttempts: Int = 5,
        initialBackoff: TimeInterval = 1,
        maxBackoff: TimeInterval = 300,
        backoffMultiplier: Double = 2,
        jitterFactor: Double = 1,
        respectRetryAfter: Bool = true,
        maxRetryAfter: TimeInterval = 900
    ) {
        self.maxAttempts = max(1, maxAttempts)
        self.initialBackoff = max(0.001, initialBackoff)
        self.maxBackoff = max(initialBackoff, maxBackoff)
        self.backoffMultiplier = max(1, backoffMultiplier)
        self.jitterFactor = min(max(0, jitterFactor), 1)
        self.respectRetryAfter = respectRetryAfter
        self.maxRetryAfter = maxRetryAfter
    }
}

/// Configurable request header names, for gateways with a different convention.
public struct HeaderNames {
    public let sdkVersion: String
    public let platform: String
    public let appVersion: String
    public let requestId: String
    public let applicationId: String
    public let installationId: String
    public let authorization: String

    public init(
        sdkVersion: String = "X-SDK-Version",
        platform: String = "X-Platform",
        appVersion: String = "X-App-Version",
        requestId: String = "X-Request-ID",
        applicationId: String = "X-Application-Id",
        installationId: String = "X-Installation-Id",
        authorization: String = "Authorization"
    ) {
        self.sdkVersion = sdkVersion
        self.platform = platform
        self.appVersion = appVersion
        self.requestId = requestId
        self.applicationId = applicationId
        self.installationId = installationId
        self.authorization = authorization
    }
}

/// Points the SDK at one of ARY's push API environments.
///
/// Environment selection belongs to the consuming application: the SDK never hardcodes a URL and
/// never needs rebuilding for development, QA, staging or production. Omit it entirely and the
/// SDK runs against ``NoopPushBackend``, which keeps every push feature working with no server.
public struct PushBackendConfig {
    /// Base URL of the push API, used exactly as given, e.g. `https://easypanel.host`.
    ///
    /// No version segment is ever added: requests go to `{baseURL}/api/...`. A trailing slash is
    /// ignored. Must be HTTPS in production.
    public let baseURL: String
    public let applicationId: String?
    public let defaultHeaders: [String: String]
    public let headerNames: HeaderNames

    /// Project the installation belongs to. Sent as the `projectId` query parameter on every
    /// request, because the push API scopes every call to a project.
    public let projectId: String?

    /// Bearer token sent as `Authorization: Bearer <authToken>` on every request.
    ///
    /// A static token supplied by the host application. When an ``AuthProvider`` is also
    /// configured it wins, because it can refresh an expired token and this cannot. Never
    /// logged, and masked in ``description`` and ``customMirror``.
    public let authToken: String?

    public init(
        baseURL: String,
        applicationId: String? = nil,
        defaultHeaders: [String: String] = [:],
        headerNames: HeaderNames = HeaderNames(),
        projectId: String? = nil,
        authToken: String? = nil
    ) {
        self.baseURL = baseURL
        self.applicationId = applicationId
        self.defaultHeaders = defaultHeaders
        self.headerNames = headerNames
        self.projectId = projectId
        self.authToken = authToken
    }

    /// The base URL without trailing slashes, so joining a path never produces `//`.
    var normalizedBaseURL: String {
        var trimmed = baseURL
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        return trimmed
    }

    var isPlaintext: Bool { baseURL.hasPrefix("http://") }
}

// A configuration printed while debugging -- `print(config)`, or `dump(config)`, which reads
// through reflection rather than `description` -- must not write the bearer token to the console.
extension PushBackendConfig: CustomStringConvertible, CustomReflectable {

    private var maskedAuthToken: String {
        guard let authToken, !authToken.isEmpty else { return "nil" }
        return "***"
    }

    public var description: String {
        "PushBackendConfig(baseURL: \(baseURL), applicationId: \(applicationId ?? "nil"), "
            + "projectId: \(projectId ?? "nil"), "
            + "authToken: \(maskedAuthToken))"
    }

    public var customMirror: Mirror {
        Mirror(
            self,
            children: [
                "baseURL": baseURL,
                "applicationId": applicationId as Any,
                "projectId": projectId as Any,
                "authToken": maskedAuthToken
            ],
            displayStyle: .struct
        )
    }
}

/// Optional configuration for ``ARYPush/initialize(_:)``.
///
/// Every value has a working default, so the minimal integration is a single line:
///
/// ```swift
/// ARYPush.initialize()
/// ```
///
/// Anything not set here can also be supplied in the host application's `Info.plist` under the
/// `ARYPush` dictionary, which lets an application configure the SDK without touching its
/// app delegate. Values passed here always win.
public struct ARYPushConfig {

    /// Emit SDK logs. Keep this off in release builds.
    public let enableLogging: Bool

    /// Minimum level emitted when ``enableLogging`` is true.
    public let logLevel: PushLogLevel

    /// Ask for notification permission during initialization.
    ///
    /// Off by default: prompting on first launch, out of context, is the most common cause of a
    /// permanent denial. Call ``ARYPush/requestPermission(options:completion:)`` at a moment
    /// that makes sense to the user instead.
    public let autoRequestPermission: Bool

    /// Authorization options requested by ``ARYPush/requestPermission(options:completion:)``.
    public let authorizationOptions: UNAuthorizationOptions

    /// Register with APNs during initialization, once permission allows it.
    public let autoRegisterForRemoteNotifications: Bool

    /// What to do with a message that arrives while the application is in the foreground.
    public let foregroundDisplay: ForegroundDisplayPolicy

    /// Backend environment. Omit for a fully local, server-less integration.
    public let backend: PushBackendConfig?

    public let network: NetworkConfig
    public let retry: RetryConfig

    /// Supplies the host application's access token to SDK requests.
    public let authProvider: AuthProvider?

    /// Replaces the built-in REST backend entirely, for a bespoke transport or a test fake.
    public let customBackend: PushBackend?

    /// How many recently seen message identifiers are remembered. Bounded by design.
    public let deduplicationCacheSize: Int

    /// Send device model, OS version, locale and timezone with the installation record.
    public let collectDeviceInfo: Bool

    /// Debounce window for tag writes, in seconds.
    public let tagSyncDebounce: TimeInterval

    /// Keep the host application's existing notification-centre delegate working.
    ///
    /// The SDK installs a forwarding proxy rather than replacing the delegate. Turning this off
    /// means the SDK will not observe notifications at all unless the host forwards them
    /// manually, so it exists only for applications that want complete control.
    public let proxyNotificationCenterDelegate: Bool

    /// Observe the app delegate's remote-notification callbacks automatically.
    ///
    /// When false the host application must forward `didRegisterForRemoteNotifications`,
    /// `didFailToRegisterForRemoteNotifications` and `didReceiveRemoteNotification` to the
    /// matching ``ARYPush`` methods itself.
    public let proxyApplicationDelegate: Bool

    public init(
        enableLogging: Bool = false,
        logLevel: PushLogLevel = .info,
        autoRequestPermission: Bool = false,
        authorizationOptions: UNAuthorizationOptions = [.alert, .badge, .sound],
        autoRegisterForRemoteNotifications: Bool = true,
        foregroundDisplay: ForegroundDisplayPolicy = .show,
        backend: PushBackendConfig? = nil,
        network: NetworkConfig = NetworkConfig(),
        retry: RetryConfig = RetryConfig(),
        authProvider: AuthProvider? = nil,
        customBackend: PushBackend? = nil,
        deduplicationCacheSize: Int = 200,
        collectDeviceInfo: Bool = true,
        tagSyncDebounce: TimeInterval = 0.75,
        proxyNotificationCenterDelegate: Bool = true,
        proxyApplicationDelegate: Bool = true
    ) {
        self.enableLogging = enableLogging
        self.logLevel = logLevel
        self.autoRequestPermission = autoRequestPermission
        self.authorizationOptions = authorizationOptions
        self.autoRegisterForRemoteNotifications = autoRegisterForRemoteNotifications
        self.foregroundDisplay = foregroundDisplay
        self.backend = backend
        self.network = network
        self.retry = retry
        self.authProvider = authProvider
        self.customBackend = customBackend
        self.deduplicationCacheSize = max(1, deduplicationCacheSize)
        self.collectDeviceInfo = collectDeviceInfo
        self.tagSyncDebounce = max(0, tagSyncDebounce)
        self.proxyNotificationCenterDelegate = proxyNotificationCenterDelegate
        self.proxyApplicationDelegate = proxyApplicationDelegate
    }
}

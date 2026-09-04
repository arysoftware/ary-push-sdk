// Vendored into this pod by the podspec prepare_command in a Flutter build, and a separate
// module when the SDK is consumed directly. canImport covers both without two source files.
#if canImport(ARYPush)
import ARYPush
#endif
import Flutter
import UIKit

/// The iOS half of the Flutter bridge.
///
/// Deliberately thin. Every decision about APNs, presentation, tokens, storage and deduplication
/// is made by the native SDK; this class translates method calls and forwards events. There is no
/// second notification engine here, because two engines would drift and the Dart one cannot run
/// while the app is terminated, which is exactly when a notification tap has to be captured.
///
/// Engine lifecycle is the subtle part. A hot restart or a recreated `FlutterEngine` tears down
/// the Dart side while the native SDK keeps running, so listeners are attached when Dart starts
/// listening and detached when it stops. Without that, every hot restart would leave another
/// listener behind and the app would show duplicate events that vanish on a cold start.
public class ARYPushPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

    private static let methodChannelName = "ary_push/methods"
    private static let eventChannelName = "ary_push/events"

    /// Bounded, because a device that receives many messages before the engine attaches must not
    /// accumulate them forever. Notification opens are not buffered here: the native SDK
    /// persists those itself, which is what makes them survive a terminated launch rather than
    /// merely a slow one.
    private static let maxPendingEvents = 50

    private var eventSink: FlutterEventSink?
    private var pendingEvents: [[String: Any]] = []

    private var receivedListenerId: UUID?
    private var openedListenerId: UUID?
    private var tokenListenerId: UUID?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = ARYPushPlugin()

        let methodChannel = FlutterMethodChannel(
            name: methodChannelName,
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(instance, channel: methodChannel)

        let eventChannel = FlutterEventChannel(
            name: eventChannelName,
            binaryMessenger: registrar.messenger()
        )
        eventChannel.setStreamHandler(instance)
    }

    // MARK: - FlutterStreamHandler

    public func onListen(
        withArguments arguments: Any?,
        eventSink events: @escaping FlutterEventSink
    ) -> FlutterError? {
        eventSink = events
        attachListeners()

        // Anything that happened while Dart was not listening is delivered now, in order.
        let queued = pendingEvents
        pendingEvents.removeAll()
        queued.forEach { events($0) }
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        detachListeners()
        eventSink = nil
        return nil
    }

    private func attachListeners() {
        detachListeners()
        receivedListenerId = ARYPush.addNotificationReceivedListener { [weak self] in
            self?.emit(type: "received", payload: $0.toDictionary())
        }
        openedListenerId = ARYPush.addNotificationOpenedListener { [weak self] in
            self?.emit(type: "opened", payload: $0.toDictionary())
        }
        tokenListenerId = ARYPush.addTokenRefreshListener { [weak self] token in
            self?.emit(type: "token", payload: ["token": token])
        }
    }

    private func detachListeners() {
        if let receivedListenerId {
            ARYPush.removeNotificationReceivedListener(receivedListenerId)
        }
        if let openedListenerId {
            ARYPush.removeNotificationOpenedListener(openedListenerId)
        }
        if let tokenListenerId {
            ARYPush.removeTokenRefreshListener(tokenListenerId)
        }
        receivedListenerId = nil
        openedListenerId = nil
        tokenListenerId = nil
    }

    private func emit(type: String, payload: [String: Any]) {
        let event: [String: Any] = ["type": type, "payload": payload]
        guard let eventSink else {
            if pendingEvents.count >= Self.maxPendingEvents { pendingEvents.removeFirst() }
            pendingEvents.append(event)
            return
        }
        // Channel messages must be sent from the platform thread.
        if Thread.isMainThread {
            eventSink(event)
        } else {
            DispatchQueue.main.async { eventSink(event) }
        }
    }
}

// MARK: - Method calls

extension ARYPushPlugin {

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? [String: Any] ?? [:]

        switch call.method {
        case "initialize":
            ARYPush.initialize(FlutterConfigMapper.from(call.arguments))
            result(nil)

        case "isInitialized":
            result(ARYPush.isInitialized)

        case "requestPermission":
            // Answered asynchronously: the system prompt is shown and the result reported once
            // the user has decided.
            ARYPush.requestPermission { status in result(status.rawValue) }

        case "getPermissionStatus":
            ARYPush.getPermissionStatus { status in result(status.rawValue) }

        case "openNotificationSettings":
            Task { @MainActor in
                ARYPush.openNotificationSettings()
                result(nil)
            }

        case "getInstallationId":
            result(ARYPush.getInstallationId())

        case "getPushToken":
            result(ARYPush.getPushToken())

        case "getPushProvider":
            result(ARYPush.getPushProvider().wireValue)

        case "login":
            guard let userId = arguments["userId"] as? String, !userId.isEmpty else {
                result(
                    FlutterError(
                        code: "invalid_argument",
                        message: "userId must not be blank",
                        details: nil
                    )
                )
                return
            }
            ARYPush.login(userId)
            result(nil)

        case "logout":
            ARYPush.logout()
            result(nil)

        case "getUserId":
            result(ARYPush.getUserId())

        case "addTags":
            ARYPush.addTags(stringMap(arguments["tags"]))
            result(nil)

        case "removeTags":
            ARYPush.removeTags(Set(stringList(arguments["keys"])))
            result(nil)

        case "removeAllTags":
            ARYPush.removeAllTags()
            result(nil)

        case "getTags":
            result(ARYPush.getTags())

        case "subscribeToTopic":
            result(ARYPush.subscribeToTopic(arguments["topic"] as? String ?? ""))

        case "unsubscribeFromTopic":
            result(ARYPush.unsubscribeFromTopic(arguments["topic"] as? String ?? ""))

        case "getSegments":
            // Answered asynchronously: membership is read from the backend.
            ARYPush.getSegments { segments in
                result(segments.map { $0.toDictionary() })
            }

        case "getSubscribedTopics":
            result(Array(ARYPush.getSubscribedTopics()).sorted())

        case "getInitialNotification":
            result(ARYPush.consumeInitialNotification()?.toDictionary())

        case "trackEvent":
            ARYPush.trackEvent(
                arguments["name"] as? String ?? "",
                properties: stringMap(arguments["properties"])
            )
            result(nil)

        case "flush":
            ARYPush.flush()
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func stringMap(_ value: Any?) -> [String: String] {
        guard let dictionary = value as? [String: Any] else { return [:] }
        return dictionary.reduce(into: [String: String]()) { result, entry in
            result[entry.key] = String(describing: entry.value)
        }
    }

    private func stringList(_ value: Any?) -> [String] {
        (value as? [Any])?.map { String(describing: $0) } ?? []
    }
}

/// Translates the Dart configuration map into a native ``ARYPushConfig``.
///
/// Every field is optional and every unrecognised value falls back to the native default. A
/// configuration mistake in Dart should degrade to "the SDK ran with defaults", never to a failed
/// initialization: an application that cannot start because a log level was misspelled is a worse
/// outcome than one that logs at the wrong level.
enum FlutterConfigMapper {

    static func from(_ arguments: Any?) -> ARYPushConfig {
        guard let map = arguments as? [String: Any] else { return ARYPushConfig() }

        var backend: PushBackendConfig?
        if let backendMap = map["backend"] as? [String: Any],
           let baseURL = backendMap["baseUrl"] as? String,
           !baseURL.isEmpty {
            backend = PushBackendConfig(
                baseURL: baseURL,
                applicationId: backendMap["applicationId"] as? String,
                apiVersion: backendMap["apiVersion"] as? String ?? "v1"
            )
        }

        return ARYPushConfig(
            enableLogging: map["enableLogging"] as? Bool ?? false,
            logLevel: logLevel(map["logLevel"] as? String) ?? .info,
            autoRequestPermission: map["autoRequestPermission"] as? Bool ?? false,
            foregroundDisplay: foregroundPolicy(map["foregroundDisplay"] as? String) ?? .show,
            backend: backend,
            collectDeviceInfo: map["collectDeviceInfo"] as? Bool ?? true
        )
    }

    private static func logLevel(_ value: String?) -> PushLogLevel? {
        switch value?.lowercased() {
        case "verbose": return .verbose
        case "debug": return .debug
        case "info": return .info
        case "warning": return .warning
        case "error": return .error
        case "none": return PushLogLevel.none
        default: return nil
        }
    }

    private static func foregroundPolicy(_ value: String?) -> ForegroundDisplayPolicy? {
        switch value?.lowercased() {
        case "show": return .show
        case "eventonly": return .eventOnly
        case "suppress": return .suppress
        default: return nil
        }
    }
}

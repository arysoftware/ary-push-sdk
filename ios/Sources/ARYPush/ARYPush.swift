import Foundation
import UIKit
import UserNotifications

/// The ARY Push SDK.
///
/// Adding push to an existing application is one package and one line:
///
/// ```swift
/// import ARYPush
///
/// func application(
///     _ application: UIApplication,
///     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
/// ) -> Bool {
///     ARYPush.initialize()
///     return true
/// }
/// ```
///
/// Everything else is optional. The SDK handles authorization, the APNs registration and token,
/// token changes, the installation identity, foreground and background delivery, taps, actions,
/// topics, tags, user identity, local storage, backend synchronisation, the offline queue,
/// retries and deduplication.
///
/// What it never does is navigate. Taps arrive as
/// ``addNotificationOpenedListener(_:)`` callbacks carrying the payload, and the host
/// application's own router decides where the user lands.
///
/// It also never takes anything away. An application that already has a
/// `UNUserNotificationCenterDelegate`, its own deep links, its own analytics or its own
/// Firebase Messaging integration keeps all of them: the SDK forwards rather than replaces.
///
/// Every method is safe to call before ``initialize(_:)``: the call is logged and ignored rather
/// than crashing, because a push SDK must never be the reason an application falls over.
public enum ARYPush {

    // MARK: - Initialization

    /// Initializes the SDK.
    ///
    /// Idempotent, thread-safe and cheap: repeated calls reuse the existing instance and never
    /// create a second set of listeners, delegates, installation ids or event streams. Nothing
    /// blocking happens on the calling thread.
    ///
    /// Passing a `config` to an already-initialized SDK reconfigures it in place, which is what
    /// lets an application start the SDK from `Info.plist` and supply its environment later.
    ///
    /// - Parameter config: optional configuration. When omitted, `Info.plist` is used.
    public static func initialize(_ config: ARYPushConfig? = nil) {
        PushCore.initialize(config: config, explicit: true)
    }

    /// True once ``initialize(_:)`` has run in this process.
    public static var isInitialized: Bool { PushCore.isInitialized }

    // MARK: - Permission

    /// Requests notification authorization, presenting the system prompt when one is possible.
    ///
    /// `completion` is invoked on the main queue exactly once, with the resulting status. iOS
    /// only ever presents this prompt once per install, so call it at a moment the user
    /// understands; there is no second chance.
    ///
    /// When a prompt is no longer possible (already granted, or already denied), the current
    /// status is reported immediately without showing anything.
    public static func requestPermission(
        options: UNAuthorizationOptions? = nil,
        completion: ((PushPermissionStatus) -> Void)? = nil
    ) {
        guard let core = core("requestPermission") else {
            completion?(.notDetermined)
            return
        }
        core.requestPermission(options: options ?? core.config.authorizationOptions) { status in
            completion?(status)
        }
    }

    /// Current notification authorization state.
    public static func getPermissionStatus() async -> PushPermissionStatus {
        guard let core = core("getPermissionStatus") else { return .notDetermined }
        return await core.permissionManager.status()
    }

    /// Callback form of ``getPermissionStatus()``.
    public static func getPermissionStatus(_ completion: @escaping (PushPermissionStatus) -> Void) {
        guard let core = core("getPermissionStatus") else {
            completion(.notDetermined)
            return
        }
        core.permissionManager.status(completion)
    }

    /// Opens this application's page in Settings.
    ///
    /// The escape hatch for a user who has already denied: iOS will not present the prompt a
    /// second time, so Settings is the only remaining route.
    @MainActor
    public static func openNotificationSettings() {
        core("openNotificationSettings")?.permissionManager.openSettings()
    }

    /// Asks iOS to register with APNs.
    ///
    /// Called automatically once authorization allows it. Exposed for applications that manage
    /// registration timing themselves.
    @MainActor
    public static func registerForRemoteNotifications() {
        core("registerForRemoteNotifications")?.tokenManager.registerForRemoteNotifications()
    }

    // MARK: - Identity and token

    /// The SDK's installation identifier for this app on this device.
    ///
    /// Stable across token refreshes, logins and logouts.
    public static func getInstallationId() -> String? {
        core("getInstallationId")?.installationManager.installationId
    }

    /// The current push token: the FCM registration token when one has been supplied, otherwise
    /// the APNs device token as a hex string.
    ///
    /// `nil` until APNs has issued one, which requires authorization and a network. The host
    /// application never has to send this to the backend; the SDK registers it automatically.
    public static func getPushToken() -> String? {
        core("getPushToken")?.tokenManager.currentToken
    }

    /// The raw APNs device token, kept even when FCM is the active provider.
    public static func getAPNsToken() -> String? {
        core("getAPNsToken")?.tokenManager.apnsToken
    }

    /// Which transport issued ``getPushToken()``.
    public static func getPushProvider() -> PushProvider {
        core("getPushProvider")?.tokenManager.provider ?? .apns
    }

    /// Registers a listener invoked on the main queue whenever the push token changes.
    ///
    /// - Returns: a token to pass to ``removeTokenRefreshListener(_:)``.
    @discardableResult
    public static func addTokenRefreshListener(_ listener: @escaping (String) -> Void) -> UUID? {
        core("addTokenRefreshListener")?.tokenManager.addListener(listener)
    }

    public static func removeTokenRefreshListener(_ id: UUID) {
        core("removeTokenRefreshListener")?.tokenManager.removeListener(id)
    }
}

// MARK: - User identity

public extension ARYPush {

    /// Associates this installation with a user.
    ///
    /// Local state changes immediately, so an offline login is true from the application's point
    /// of view straight away; the backend is told through the durable queue. One user may own
    /// several installations.
    static func login(_ userId: String) {
        core("login")?.userManager.login(userId)
    }

    /// Clears the user association.
    ///
    /// Deliberately narrow: the installation id, the push token and the APNs registration all
    /// survive, so the device keeps receiving unauthenticated campaigns. Logout is not
    /// unregistration.
    static func logout() {
        core("logout")?.userManager.logout()
    }

    /// The currently associated user, or nil when logged out.
    static func getUserId() -> String? {
        core("getUserId")?.userManager.userId
    }
}

// MARK: - Tags

public extension ARYPush {

    /// Sets one tag.
    ///
    /// Tags are attributes the backend builds segments from: `subscription=premium`,
    /// `language=en`, `country=PK`. Consecutive calls are coalesced into a single request.
    static func addTag(_ key: String, _ value: String) {
        core("addTag")?.tagManager.addTag(key, value)
    }

    /// Sets several tags at once.
    static func addTags(_ tags: [String: String]) {
        core("addTags")?.tagManager.addTags(tags)
    }

    static func removeTag(_ key: String) {
        core("removeTag")?.tagManager.removeTag(key)
    }

    static func removeTags(_ keys: Set<String>) {
        core("removeTags")?.tagManager.removeTags(keys)
    }

    static func removeAllTags() {
        core("removeAllTags")?.tagManager.removeAllTags()
    }

    /// Tags currently held for this installation, read from local storage.
    static func getTags() -> [String: String] {
        core("getTags")?.tagManager.tags ?? [:]
    }
}

// MARK: - Topics

public extension ARYPush {

    /// Subscribes this installation to a topic.
    ///
    /// On iOS the fan-out happens on the push backend rather than in the transport: APNs has no
    /// concept of a topic. The API and the topic-name rules are identical to Android so host
    /// code stays portable. See docs/IOS.md.
    ///
    /// - Returns: false when the topic name is invalid.
    @discardableResult
    static func subscribeToTopic(_ topic: String) -> Bool {
        core("subscribeToTopic")?.topicManager.subscribe(topic) ?? false
    }

    @discardableResult
    static func unsubscribeFromTopic(_ topic: String) -> Bool {
        core("unsubscribeFromTopic")?.topicManager.unsubscribe(topic) ?? false
    }

    /// Topics this installation is recorded as subscribed to.
    static func getSubscribedTopics() -> Set<String> {
        core("getSubscribedTopics")?.topicManager.topics ?? []
    }
}

// MARK: - Notification events

public extension ARYPush {

    /// Called when a message arrives while the process is running.
    ///
    /// - Returns: a token to pass to ``removeNotificationReceivedListener(_:)``.
    @discardableResult
    static func addNotificationReceivedListener(
        _ listener: @escaping (PushNotification) -> Void
    ) -> UUID? {
        core("addNotificationReceivedListener")?.dispatcher.addReceivedListener(listener)
    }

    static func removeNotificationReceivedListener(_ id: UUID) {
        core("removeNotificationReceivedListener")?.dispatcher.removeReceivedListener(id)
    }

    /// Called when the user taps a notification, or one of its action buttons.
    ///
    /// A tap that happened while the application was terminated is not lost: it is persisted and
    /// replayed to the first listener that registers, so attaching this in
    /// `didFinishLaunchingWithOptions` always sees it.
    ///
    /// The SDK does not navigate. Read ``PushNotification/data`` and route from here.
    @discardableResult
    static func addNotificationOpenedListener(
        _ listener: @escaping (PushNotification) -> Void
    ) -> UUID? {
        core("addNotificationOpenedListener")?.dispatcher.addOpenedListener(listener)
    }

    static func removeNotificationOpenedListener(_ id: UUID) {
        core("removeNotificationOpenedListener")?.dispatcher.removeOpenedListener(id)
    }

    /// The notification that launched the application, if it has not been delivered yet.
    ///
    /// Most applications should register ``addNotificationOpenedListener(_:)`` instead, which
    /// replays the same event. This exists for frameworks that pull rather than subscribe.
    static func consumeInitialNotification() -> PushNotification? {
        core("consumeInitialNotification")?.dispatcher.consumePendingOpen()
    }
}

// MARK: - Events and maintenance

public extension ARYPush {

    /// Records a push-related event.
    ///
    /// Scope is push: delivery and engagement attribution on the push backend. This is not an
    /// analytics SDK and should not be used as one.
    static func trackEvent(_ name: String, properties: [String: String] = [:]) {
        core("trackEvent")?.eventManager.track(name, properties: properties)
    }

    /// Sends anything the SDK is holding back.
    ///
    /// Tag writes are debounced and queued operations wait for connectivity, so an application
    /// about to be killed can call this to stop waiting.
    static func flush() {
        guard let core = core("flush") else { return }
        core.tagManager.flushNow()
        core.syncManager.requestSync()
    }
}

// MARK: - Manual forwarding
//
// Everything below is optional. The SDK proxies the app delegate and the notification centre
// delegate automatically, so an ordinary integration never calls any of it.
//
// These exist for two situations that genuinely occur:
//
//  * a team whose policy forbids runtime method manipulation sets `proxyApplicationDelegate` and
//    `proxyNotificationCenterDelegate` to false and forwards explicitly;
//  * an application that uses Firebase Messaging hands the SDK the FCM registration token, which
//    only Firebase knows.

public extension ARYPush {

    /// Forward from `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`.
    static func didRegisterForRemoteNotifications(deviceToken: Data) {
        PushCore.ensureInitialized().handleAPNsToken(deviceToken)
    }

    /// Forward from `application(_:didFailToRegisterForRemoteNotificationsWithError:)`.
    static func didFailToRegisterForRemoteNotifications(error: Error) {
        PushCore.ensureInitialized().tokenManager.handleRegistrationFailure(error)
    }

    /// Forward from `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)`.
    static func didReceiveRemoteNotification(userInfo: [AnyHashable: Any]) {
        PushCore.ensureInitialized().handleRemoteNotification(userInfo: userInfo)
    }

    /// Forward from `userNotificationCenter(_:willPresent:withCompletionHandler:)`.
    ///
    /// - Returns: the presentation options the SDK wants. Union them with your own rather than
    ///   replacing yours, so neither side can suppress the other's notification.
    static func willPresent(_ notification: UNNotification) -> UNNotificationPresentationOptions {
        PushCore.ensureInitialized().handleWillPresent(notification)
    }

    /// Forward from `userNotificationCenter(_:didReceive:withCompletionHandler:)`.
    static func didReceive(_ response: UNNotificationResponse) {
        PushCore.ensureInitialized().handleDidReceive(response)
    }

    /// Hands the SDK an FCM registration token.
    ///
    /// Call this from `messaging(_:didReceiveRegistrationToken:)` when the host application uses
    /// Firebase Messaging. The SDK then records `fcm` as the provider and synchronises the FCM
    /// token rather than the APNs one, because those are different values and a backend that
    /// sends through Firebase must be given the Firebase token.
    ///
    /// Applications that talk to APNs directly never call this.
    static func setFCMToken(_ token: String) {
        PushCore.ensureInitialized().handleFCMToken(token)
    }

    /// Re-samples state the OS can change without telling the application.
    ///
    /// Call from `applicationDidBecomeActive` if you disabled delegate proxying; otherwise the
    /// SDK does this itself.
    static func applicationDidBecomeActive() {
        core("applicationDidBecomeActive")?.refreshOnForeground()
    }
}

// MARK: - Internal

extension ARYPush {

    /// Returns the live core, logging a clear warning instead of crashing when there is none.
    static func core(_ operation: String) -> PushCore? {
        guard let instance = PushCore.instance else {
            PushLogger.warn("\(operation)() called before ARYPush.initialize(); ignored")
            return nil
        }
        return instance
    }
}

import Foundation
import UIKit
import UserNotifications

/// The engine behind the ``ARYPush`` facade.
///
/// Everything stateful lives here exactly once. The facade is a thin, nil-safe wrapper so that
/// the public API can never be the thing that owns state, and so that a host application calling
/// an API before initialization gets a logged warning rather than a crash.
///
/// Two independent halves meet in this class and are deliberately not allowed to depend on each
/// other: the **push engine** (APNs, presentation, events) and **backend synchronisation**
/// (queue, REST). Nothing in the push path awaits the sync path, which is why a backend outage
/// cannot stop a notification being received, displayed or opened.
final class PushCore {

    static let platform = "ios"

    private static let lock = NSLock()
    private static var core: PushCore?

    /// The live core, or nil when the SDK has never been initialized in this process.
    static var instance: PushCore? {
        lock.lock()
        defer { lock.unlock() }
        return core
    }

    static var isInitialized: Bool { instance != nil }

    private(set) var config: ARYPushConfig

    let storage: StorageManager
    let device: DeviceInfoProvider
    let installationManager: InstallationManager
    let permissionManager: PermissionManager
    let dispatcher: NotificationEventDispatcher
    let appState: AppStateTracker

    private let deduplication: DeduplicationManager
    private let queue: PendingOperationQueue
    private let connectivity: ConnectivityMonitor

    private(set) var syncManager: SyncManager!
    private(set) var tokenManager: TokenManager!
    private(set) var userManager: UserManager!
    private(set) var tagManager: TagManager!
    private(set) var topicManager: TopicManager!
    private(set) var eventManager: EventManager!

    private var backend: PushBackend = NoopPushBackend.shared
    private var restClient: RestClient?
    private var notificationProxy: NotificationCenterDelegateProxy?
    private var foregroundObserver: NSObjectProtocol?

    private let configLock = NSLock()

    private init(config: ARYPushConfig, storage: StorageManager, bundle: Bundle) {
        self.config = config
        self.storage = storage
        self.device = DeviceInfoProvider(bundle: bundle)
        self.installationManager = InstallationManager(storage: storage)
        self.permissionManager = PermissionManager(storage: storage)
        self.dispatcher = NotificationEventDispatcher(storage: storage)
        self.appState = AppStateTracker()
        self.deduplication = DeduplicationManager(
            storage: storage,
            maxSize: config.deduplicationCacheSize
        )
        self.queue = PendingOperationQueue(storage: storage)

        var requestSync: (() -> Void)?
        self.connectivity = ConnectivityMonitor { requestSync?() }

        self.syncManager = SyncManager(
            queue: queue,
            storage: storage,
            isOnline: { [connectivity] in connectivity.isOnline },
            retryManager: RetryManager(config: config.retry),
            backendProvider: { [weak self] in self?.backend ?? NoopPushBackend.shared },
            installationProvider: { [weak self] in self?.buildInstallation() }
        )
        requestSync = { [weak self] in self?.syncManager.requestSync() }

        self.tokenManager = TokenManager(storage: storage) { [weak self] token, provider in
            self?.syncManager.enqueueTokenUpdate(token: token, provider: provider)
        }
        self.userManager = UserManager(storage: storage) { [weak self] userId in
            guard let self else { return }
            if let userId {
                self.syncManager.enqueueIdentify(userId: userId)
            } else {
                self.syncManager.enqueueLogout()
            }
        }
        self.tagManager = TagManager(
            storage: storage,
            debounce: config.tagSyncDebounce,
            onTagsChanged: { [weak self] tags in self?.syncManager.enqueueTagUpdate(tags) },
            onTagsRemoved: { [weak self] keys, all in
                self?.syncManager.enqueueTagRemoval(keys: keys, all: all)
            }
        )
        self.topicManager = TopicManager(storage: storage) { [weak self] topics in
            self?.syncManager.enqueueTopicUpdate(topics)
        }
        self.eventManager = EventManager { [weak self] event in
            self?.syncManager.enqueueEvent(event)
        }
    }
}

// MARK: - Lifecycle

extension PushCore {

    /// Creates the core, or reconfigures the existing one.
    ///
    /// Idempotent and thread-safe, which matters more than it sounds: a silent notification, a
    /// notification tap and the host application's own `didFinishLaunching` can all reach this
    /// within milliseconds of each other on a cold start. Exactly one core, one set of listeners,
    /// one installation id and one event stream must come out of that race.
    @discardableResult
    static func initialize(
        config: ARYPushConfig?,
        explicit: Bool,
        storage: StorageManager = StorageManager(),
        bundle: Bundle = .main
    ) -> PushCore {
        lock.lock()
        if let existing = core {
            lock.unlock()
            if explicit, let config {
                existing.reconfigure(config)
            } else {
                PushLogger.debug("initialize() called again; existing instance reused")
            }
            return existing
        }

        let effective = config ?? InfoPlistConfigReader.read(bundle: bundle)
        let created = PushCore(config: effective, storage: storage, bundle: bundle)
        core = created
        lock.unlock()

        created.start()
        return created
    }

    /// Guarantees a core exists, without requiring the host application to have run yet.
    ///
    /// This is what makes silent notifications and terminated-state taps work: iOS starts the
    /// process for the SDK's own callbacks, and none of them can assume host code has executed.
    /// Configuration comes from `Info.plist` in that case.
    @discardableResult
    static func ensureInitialized() -> PushCore {
        instance ?? initialize(config: nil, explicit: false)
    }

    /// Drops the singleton. Test-only: production code has no reason to tear the SDK down.
    static func resetForTesting() {
        lock.lock()
        let existing = core
        core = nil
        lock.unlock()
        existing?.shutdown()
    }

    /// Brings the SDK up.
    ///
    /// Ordering is chosen so the cheap, always-correct work happens immediately (logger,
    /// delegates, installation id) and everything that can block (network, permission queries)
    /// is deferred. `initialize()` must never make an application's launch slower.
    private func start() {
        PushLogger.configure(enabled: config.enableLogging, level: config.logLevel)
        PushLogger.info("Initialization started")

        applyBackend(config)
        connectivity.start()
        installProxies()
        observeForeground()

        let installationId = installationManager.installationId
        PushLogger.info("Installation ID loaded")

        permissionManager.status { [weak self] status in
            guard let self else { return }
            PushLogger.info("Permission status: \(status.rawValue)")

            if self.permissionManager.recordStatusChange(status) {
                self.syncManager.enqueuePermissionUpdate(enabled: status.isAuthorized)
            }

            if status.isAuthorized, self.config.autoRegisterForRemoteNotifications {
                // Registering before authorization is pointless: iOS will not issue a token.
                Task { @MainActor in self.tokenManager.registerForRemoteNotifications() }
            }

            self.syncManager.registerInstallationIfChanged()
            self.syncManager.requestSync()

            if self.config.autoRequestPermission, status == .notDetermined {
                self.requestPermission(options: self.config.authorizationOptions) { _ in }
            }
        }

        PushLogger.info("Initialization complete (installation=\(installationId))")
    }

    /// Applies a configuration supplied after the SDK was already running.
    ///
    /// The common cause is an application that lets `Info.plist` bring the SDK up and then calls
    /// `initialize(_:)` once it knows which environment it is in. Managers and listeners are
    /// preserved; only configuration-derived components are rebuilt.
    func reconfigure(_ newConfig: ARYPushConfig) {
        configLock.lock()
        let previous = config
        config = newConfig
        configLock.unlock()

        PushLogger.configure(enabled: newConfig.enableLogging, level: newConfig.logLevel)
        PushLogger.info("Reconfigured")

        let backendChanged = previous.backend?.normalizedBaseURL != newConfig.backend?.normalizedBaseURL
            || previous.backend?.applicationId != newConfig.backend?.applicationId
            || previous.customBackend !== newConfig.customBackend
            || previous.authProvider !== newConfig.authProvider

        if backendChanged {
            applyBackend(newConfig)
            // A new environment means the backend has never seen this installation.
            syncManager.registerInstallationIfChanged(force: true)
        }
        syncManager.requestSync()
    }

    /// Chooses the backend for a configuration.
    ///
    /// A custom backend wins; a REST configuration builds a transport; anything else runs
    /// server-less. A transport that cannot be built degrades to ``NoopPushBackend`` rather than
    /// failing initialization, because a bad base URL must not cost the application its push.
    private func applyBackend(_ target: ARYPushConfig) {
        restClient?.close()
        restClient = nil

        if let custom = target.customBackend {
            backend = custom
            PushLogger.info("Using a host-supplied PushBackend implementation")
            return
        }

        guard let backendConfig = target.backend else {
            backend = NoopPushBackend.shared
            PushLogger.info("No backend configured; running without server synchronisation")
            return
        }

        let client = URLSessionRestClient(
            backendConfig: backendConfig,
            networkConfig: target.network,
            retryConfig: target.retry,
            authProvider: target.authProvider,
            device: device,
            installationIdProvider: { [storage] in
                // Read without creating: header construction must never be what generates the
                // installation id.
                storage.string(StorageManager.Keys.installationId)
            }
        )
        restClient = client
        backend = RestPushBackend(client: client, config: backendConfig)
    }

    /// Re-samples OS-owned state every time the application comes forward.
    ///
    /// Neither "the user switched notifications off in Settings" nor "the host installed its own
    /// notification delegate" has a callback, so both are checked here instead of assumed.
    private func observeForeground() {
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.refreshOnForeground()
        }
    }

    /// Flushes pending work and releases resources. Used by tests and by Flutter engine teardown.
    func shutdown() {
        if let foregroundObserver {
            NotificationCenter.default.removeObserver(foregroundObserver)
            self.foregroundObserver = nil
        }
        tagManager.flushNow()
        connectivity.stop()
        backend.close()
        restClient?.close()
        dispatcher.clearListeners()
        PushLogger.info("SDK shut down")
    }
}

// MARK: - Delegate installation

private extension PushCore {

    func installProxies() {
        if config.proxyNotificationCenterDelegate {
            installNotificationCenterProxy()
        } else {
            PushLogger.info(
                "Notification delegate proxying is disabled; forward willPresent and didReceive "
                    + "to ARYPush yourself. See docs/IOS.md."
            )
        }

        guard config.proxyApplicationDelegate else {
            PushLogger.info(
                "App delegate proxying is disabled; forward the remote-notification callbacks to "
                    + "ARYPush yourself. See docs/IOS.md."
            )
            return
        }

        let proxy = AppDelegateProxy.shared
        proxy.onAPNsToken = { [weak self] token in self?.handleAPNsToken(token) }
        proxy.onRegistrationFailure = { [weak self] error in
            self?.tokenManager.handleRegistrationFailure(error)
        }
        proxy.onRemoteNotification = { [weak self] userInfo in
            self?.handleRemoteNotification(userInfo: userInfo)
        }
        Task { @MainActor in proxy.install() }
    }

    func installNotificationCenterProxy() {
        let center = UNUserNotificationCenter.current()
        let existing = center.delegate

        if let existing, existing === notificationProxy { return }

        let proxy = NotificationCenterDelegateProxy(
            previous: existing,
            onWillPresent: { [weak self] notification in
                self?.handleWillPresent(notification) ?? []
            },
            onDidReceive: { [weak self] response in
                self?.handleDidReceive(response)
            }
        )
        notificationProxy = proxy
        center.delegate = proxy

        if existing != nil {
            PushLogger.info(
                "An existing notification delegate was found and is being forwarded to; its "
                    + "notifications, deep links and analytics continue to work unchanged"
            )
        }
    }

    /// Re-captures a delegate the host installed after the SDK started.
    ///
    /// A host that assigns `UNUserNotificationCenter.current().delegate` in a view controller,
    /// or after an async setup step, would otherwise silently displace the proxy and stop the
    /// SDK from seeing anything.
    func recaptureNotificationDelegateIfNeeded() {
        guard config.proxyNotificationCenterDelegate, let proxy = notificationProxy else { return }
        let current = UNUserNotificationCenter.current().delegate
        guard current !== proxy else { return }

        PushLogger.warn(
            "The notification delegate was replaced after initialization; re-installing the "
                + "forwarding proxy so both the SDK and the host keep receiving callbacks"
        )
        proxy.setPreviousDelegate(current)
        UNUserNotificationCenter.current().delegate = proxy
    }
}

// MARK: - Notification handling

extension PushCore {

    /// Decides how a foreground notification is presented.
    ///
    /// Returning an empty option set is how the SDK suppresses its own banner; the proxy still
    /// unions this with whatever the host delegate asks for, so the host can never be silenced.
    func handleWillPresent(_ notification: UNNotification) -> UNNotificationPresentationOptions {
        let parsed = NotificationParser.parse(notification: notification, wasForeground: true)

        guard deduplication.markSeenIfNew(parsed.id) else { return [] }

        switch config.foregroundDisplay {
        case .suppress:
            PushLogger.debug("Foreground policy is suppress; message \(parsed.id) dropped")
            return []
        case .eventOnly:
            dispatch(received: parsed, foreground: true)
            return []
        case .show:
            dispatch(received: parsed, foreground: true)
            return Self.foregroundPresentationOptions
        }
    }

    /// Handles a notification tap, including one that launched the process.
    func handleDidReceive(_ response: UNNotificationResponse) {
        let actionId = response.actionIdentifier == UNNotificationDefaultActionIdentifier
            ? nil
            : response.actionIdentifier

        // A dismissal is not an open. Reporting it as one would inflate engagement and, worse,
        // send the user somewhere they did not ask to go.
        guard response.actionIdentifier != UNNotificationDismissActionIdentifier else {
            PushLogger.debug("Notification dismissed, not opened")
            return
        }

        let parsed = NotificationParser.parse(
            notification: response.notification,
            wasForeground: appState.isForeground,
            actionId: actionId
        )

        // Opens are deduplicated separately from receipts, and keyed on the action, so tapping
        // the body and then an action button are two events but a redelivered response is not.
        guard deduplication.markSeenIfNew("open:\(parsed.id):\(actionId ?? "")") else { return }

        dispatcher.dispatchOpened(parsed)
        eventManager.trackNotificationOpened(id: parsed.id, actionId: actionId)
    }

    /// Handles a silent or background remote notification.
    func handleRemoteNotification(userInfo: [AnyHashable: Any]) {
        let foreground = appState.isForeground
        let parsed = NotificationParser.parse(userInfo: userInfo, wasForeground: foreground)

        guard deduplication.markSeenIfNew(parsed.id) else { return }
        guard config.foregroundDisplay != .suppress || !foreground else { return }

        dispatch(received: parsed, foreground: foreground)
    }

    /// Records the APNs device token and re-registers when it changed.
    func handleAPNsToken(_ deviceToken: Data) {
        tokenManager.handleAPNsToken(deviceToken)
        syncManager.registerInstallationIfChanged()
    }

    /// Records an FCM token supplied by a host application that uses Firebase Messaging.
    func handleFCMToken(_ token: String) {
        tokenManager.handleFCMToken(token)
        syncManager.registerInstallationIfChanged()
    }

    private func dispatch(received notification: PushNotification, foreground: Bool) {
        dispatcher.dispatchReceived(notification)
        eventManager.trackNotificationReceived(id: notification.id, foreground: foreground)
    }

    /// Presentation options for a foreground notification.
    ///
    /// `.banner` and `.list` on iOS 14+, `.alert` before that; `.alert` is deprecated but is the
    /// only option older systems understand.
    static var foregroundPresentationOptions: UNNotificationPresentationOptions {
        if #available(iOS 14.0, *) {
            return [.banner, .list, .sound, .badge]
        } else {
            return [.alert, .sound, .badge]
        }
    }
}

// MARK: - Operations

extension PushCore {

    func requestPermission(
        options: UNAuthorizationOptions,
        completion: @escaping (PushPermissionStatus) -> Void
    ) {
        permissionManager.request(options: options) { [weak self] status in
            guard let self else {
                completion(status)
                return
            }

            if self.permissionManager.recordStatusChange(status) {
                self.syncManager.enqueuePermissionUpdate(enabled: status.isAuthorized)
            }

            if status.isAuthorized {
                // A device that just became reachable is worth registering immediately.
                Task { @MainActor in self.tokenManager.registerForRemoteNotifications() }
                self.syncManager.registerInstallationIfChanged()
            }
            completion(status)
        }
    }

    /// Assembles the record the backend stores for this device.
    func buildInstallation() -> Installation {
        configLock.lock()
        let current = config
        configLock.unlock()

        let collect = current.collectDeviceInfo
        let authorized = storage.string(StorageManager.Keys.lastPermissionState)
            .map { PushPermissionStatus(rawValue: $0)?.isAuthorized ?? false } ?? false

        return Installation(
            id: installationManager.installationId,
            applicationId: current.backend?.applicationId,
            platform: PushCore.platform,
            provider: tokenManager.provider,
            pushToken: tokenManager.currentToken,
            userId: userManager.userId,
            appVersion: device.appVersion,
            appBuild: device.appBuild,
            sdkVersion: device.sdkVersion,
            osVersion: collect ? device.osVersion : nil,
            deviceModel: collect ? device.deviceModel : nil,
            locale: collect ? device.locale : nil,
            timezone: collect ? device.timezone : nil,
            notificationsEnabled: authorized
        )
    }

    /// Re-checks state the OS can change behind the SDK's back.
    ///
    /// Called when the application becomes active: the user may have switched notifications off
    /// in Settings, and the host may have installed its own notification delegate in the
    /// meantime. Neither event has a callback, so both are sampled.
    func refreshOnForeground() {
        recaptureNotificationDelegateIfNeeded()
        permissionManager.status { [weak self] status in
            guard let self else { return }
            if self.permissionManager.recordStatusChange(status) {
                self.syncManager.enqueuePermissionUpdate(enabled: status.isAuthorized)
                self.syncManager.registerInstallationIfChanged()
            }
            self.syncManager.requestSync()
        }
    }
}

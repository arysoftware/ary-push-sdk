# iOS integration

## Package

Xcode: **File > Add Package Dependencies**, then the private repository URL, pinned to
`Up to Next Major Version` from `1.0.0`. Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "git@github.com:ary/ary-push-sdk.git", from: "1.0.0")
]
```

Production applications pin an immutable version tag, never a branch.

Flutter applications get the SDK through the `ary_push` plugin's CocoaPods dependency; see
[FLUTTER.md](FLUTTER.md).

## Capabilities

Under **Signing & Capabilities**:

- **Push Notifications** (adds the `aps-environment` entitlement)
- **Background Modes > Remote notifications**, for silent `content-available` messages

Upload your APNs authentication key (`.p8`) or certificate to the push backend. The SDK contains
neither and never will; see [SECURITY.md](SECURITY.md).

## Initialization

```swift
import ARYPush

func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {
    ARYPush.initialize()
    return true
}
```

With configuration:

```swift
ARYPush.initialize(
    ARYPushConfig(
        enableLogging: true,
        logLevel: .debug,
        foregroundDisplay: .show,
        backend: PushBackendConfig(
            baseURL: "https://push-api.ary.com",
            applicationId: "wallet_ios"
        )
    )
)
```

Idempotent, thread-safe and cheap. Repeated calls reuse the same instance; a configuration passed
later reconfigures in place.

### Configuring from Info.plist

```xml
<key>ARYPush</key>
<dict>
    <key>BackendBaseURL</key><string>https://push-api.ary.com</string>
    <key>ApplicationId</key><string>wallet_ios</string>
    <key>EnableLogging</key><false/>
    <key>ForegroundDisplay</key><string>show</string>
    <key>ProxyApplicationDelegate</key><true/>
    <key>ProxyNotificationCenterDelegate</key><true/>
</dict>
```

Necessary as well as convenient: a silent notification can start the process before any host code
runs, and the SDK still has to know which backend to talk to. Values passed to `initialize(_:)`
always win.

## Delegate safety

This is the part of an iOS push SDK that most often breaks host applications, so it is worth
being explicit about what this one does.

There is exactly **one** `UNUserNotificationCenter.delegate`. An SDK that assigns itself to it
silently breaks every notification the app already handled: its deep links stop working, its
analytics stop firing, its action buttons stop responding, and nothing logs an error.

**This SDK does not replace your delegate. It wraps it.**

- The delegate already installed is kept and every callback is forwarded to it.
- Presentation options are the **union** of what the SDK wants and what your delegate returns, so
  neither side can suppress the other's notification.
- A completion handler is called exactly once, even if your delegate calls its handler twice, or
  never calls it at all (a two-second fallback covers the second case).
- Your delegate is held **weakly**, matching `UNUserNotificationCenter.delegate` itself, so the
  SDK can never keep a host object alive or create a retain cycle.
- If you install a delegate *after* the SDK started, the SDK notices when the app next becomes
  active and re-installs the proxy around your new delegate rather than losing its callbacks.

The same applies to the app delegate. APNs hands the device token to exactly one place, and there
is no API that lets a library observe it, so the SDK adds its own implementations of the three
remote-notification selectors to your app delegate's class. Where you already implement one, the
implementations are exchanged so your code still runs; where you do not, the method is simply
added. Nothing is ever removed.

### If your policy forbids runtime method manipulation

Turn it off and forward explicitly. Three lines:

```swift
ARYPush.initialize(
    ARYPushConfig(
        proxyNotificationCenterDelegate: false,
        proxyApplicationDelegate: false
    )
)
```

```swift
func application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
) {
    ARYPush.didRegisterForRemoteNotifications(deviceToken: deviceToken)
}

func application(
    _ application: UIApplication,
    didFailToRegisterForRemoteNotificationsWithError error: Error
) {
    ARYPush.didFailToRegisterForRemoteNotifications(error: error)
}

func application(
    _ application: UIApplication,
    didReceiveRemoteNotification userInfo: [AnyHashable: Any],
    fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
) {
    ARYPush.didReceiveRemoteNotification(userInfo: userInfo)
    completionHandler(.noData)
}
```

```swift
func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
) {
    // Union, never replace: otherwise one side silences the other.
    completionHandler(ARYPush.willPresent(notification).union([.sound]))
}

func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
) {
    ARYPush.didReceive(response)
    completionHandler()
}

func applicationDidBecomeActive(_ application: UIApplication) {
    ARYPush.applicationDidBecomeActive()
}
```

## Permission

```swift
ARYPush.requestPermission { status in
    if status.isAuthorized {
        enableNotificationFeatures()
    } else if status == .denied {
        Task { @MainActor in ARYPush.openNotificationSettings() }
    }
}
```

iOS presents this prompt **once per install, ever**. There is no second chance, so call it at a
moment the user understands rather than on first launch. `autoRequestPermission` is off by
default for exactly this reason.

Once permission allows it, the SDK registers with APNs itself; you do not need to call
`registerForRemoteNotifications()`.

## Tokens

Two different values, and confusing them is a classic source of silent non-delivery:

| | What it is |
| --- | --- |
| **APNs device token** | A hex string Apple hands to the app delegate |
| **FCM registration token** | Issued by Google *after* being given the APNs token |

`ARYPush.getPushToken()` returns whichever one the backend should send to, and
`getPushProvider()` says which. Applications talking to APNs directly get `apns` and never think
about it. Applications using Firebase Messaging call `ARYPush.setFCMToken(_:)` from
`messaging(_:didReceiveRegistrationToken:)`; see [FIREBASE.md](FIREBASE.md).

Registration failures are logged, never thrown. The usual causes are the Simulator without a
paired Mac, a missing Push Notifications capability, or no network at launch.

## Topics

`subscribeToTopic` works on iOS, but the fan-out happens on the push backend rather than in the
transport: APNs has no concept of a topic subscription. The API and the topic-name rules are
identical to Android so your code stays portable; only where the fan-out happens differs.

## Privacy

The SDK reads no IDFA, no `identifierForVendor`, no contacts and no location, and requests no
permission other than notifications. The complete list of fields sent to the backend is in
[SECURITY.md](SECURITY.md), which is what a privacy review should be conducted against.

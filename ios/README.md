# ARY Push SDK for iOS

Swift implementation of the ARY Push SDK, distributed as a private Swift Package.

## Integration

In Xcode: **File > Add Package Dependencies**, then the private repository URL. Or in a
`Package.swift`:

```swift
dependencies: [
    .package(url: "git@github.com:ary/ary-push-sdk.git", from: "1.0.0")
]
```

Production applications pin an immutable version tag, never a branch.

```swift
import ARYPush

ARYPush.initialize()
```

That is the whole integration. Full documentation: [../docs/IOS.md](../docs/IOS.md).

## Required capabilities

The host application, not the SDK, provides these:

| Capability | Where |
| --- | --- |
| Push Notifications | Signing & Capabilities |
| Background Modes > Remote notifications | Signing & Capabilities, for silent messages |
| `aps-environment` entitlement | Added by Xcode with the capability |
| APNs key or certificate | Apple Developer portal, uploaded to the push backend |

The SDK contains no `.p8` key, no certificate, and no `GoogleService-Info.plist`. See
[../docs/SECURITY.md](../docs/SECURITY.md).

## Package layout

| Path | Contents |
| --- | --- |
| `Sources/ARYPush/ARYPush.swift` | Public facade |
| `Sources/ARYPush/Model/` | `PushNotification`, `Installation`, permission and provider enums |
| `Sources/ARYPush/API/` | `RestClient`, `ApiResult`, `AuthProvider`, URLSession implementation |
| `Sources/ARYPush/Backend/` | `PushBackend`, `RestPushBackend`, `NoopPushBackend` |
| `Sources/ARYPush/Internal/` | Managers, delegate proxies and engine internals |

## Samples

| Sample | What it shows |
| --- | --- |
| `sample-basic/` | A new application: one line of integration |
| `sample-existing-notification-delegate/` | An application that already owns the notification delegate, its deep links and its token registration, all still working |

These are single-file app delegates rather than checked-in Xcode projects: drop the file into a
new iOS App target, add the package, and enable the Push Notifications capability. Committing
generated `.xcodeproj` files would add churn without adding clarity.

## Testing

```bash
swift test
```

The test target covers the parts that are platform-independent logic: retry and backoff,
`Retry-After` parsing, payload parsing and identity, deduplication bounds, the durable queue's
coalescing and ordering, terminated-state open replay, the sync state machine and the REST
contract. Delegate proxying and APNs registration need a real device and are exercised by the
sample applications.

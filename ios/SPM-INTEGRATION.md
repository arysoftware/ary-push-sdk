# Swift Package Manager

Add `https://github.com/arysoftware/ary-push-sdk.git` in Xcode under
**File → Add Package Dependencies**, then link the **ARYPush** product to your app target.
The library requires iOS 13 and Swift tools 5.9 or newer. Import it with `import ARYPush`.

Before the first release tag exists, select the packaging branch or an immutable commit for
evaluation. For production select a released semantic version. The first candidate is `v1.1.0`,
matching current SDK metadata; publish only after the release checks pass. SPM resolves directly
from the Git tag and requires no registry upload or binary download.

The root package and `ios/Package.swift` reference the same Swift sources and tests. CocoaPods
remains supported with its existing podspec and configuration. Use only one dependency manager
for ARYPush in a given target to avoid duplicate symbols.

Firebase Messaging remains optional: the host provides Firebase and forwards its FCM token
using `ARYPush.setFCMToken(_:)`. There are no Firebase package dependencies in ARYPush.

The preference and URL-routing APIs proposed in SDK PR #1 become available through SPM only
after those source changes are merged into the selected revision. ARYZAP migration and removal
of its local CocoaPods patches are separate work.

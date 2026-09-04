// swift-tools-version: 5.9
//
// ARY Push SDK for iOS.
//
// Distributed privately. Host applications add it as a Swift Package from ARY's private
// Git repository and pin an immutable version tag:
//
//   .package(url: "https://github.com/arysoftware/ary-push-sdk.git", from: "1.0.0")
//
// The package has no external dependencies on purpose. It uses UserNotifications and
// UIKit directly, so it never forces a Firebase version, a networking library or an
// analytics SDK onto the host application's dependency graph.

import PackageDescription

let package = Package(
    name: "ARYPush",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: "ARYPush",
            targets: ["ARYPush"]
        )
    ],
    dependencies: [],
    targets: [
        .target(
            name: "ARYPush",
            path: "Sources/ARYPush"
        ),
        .testTarget(
            name: "ARYPushTests",
            dependencies: ["ARYPush"],
            path: "Tests/ARYPushTests"
        )
    ]
)

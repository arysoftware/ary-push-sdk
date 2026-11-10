// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ARYPush",
    platforms: [.iOS(.v13)],
    products: [.library(name: "ARYPush", targets: ["ARYPush"])],
    dependencies: [],
    targets: [
        .target(name: "ARYPush", path: "ios/Sources/ARYPush"),
        .testTarget(name: "ARYPushTests", dependencies: ["ARYPush"], path: "ios/Tests/ARYPushTests")
    ]
)

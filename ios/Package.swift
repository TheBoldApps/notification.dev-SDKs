// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NotificationDev",
    platforms: [.iOS(.v15)],
    products: [.library(name: "NotificationDev", targets: ["NotificationDev"])],
    targets: [
        .binaryTarget(name: "NotificationCore", path: "Artifacts/NotificationCore.xcframework"),
        .target(name: "NotificationDev", dependencies: ["NotificationCore"]),
        .testTarget(name: "NotificationDevTests", dependencies: ["NotificationDev"]),
    ]
)

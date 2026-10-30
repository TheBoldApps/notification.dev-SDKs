// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NotificationDev",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "NotificationDev", targets: ["NotificationDev"]),
        .library(name: "NotificationDevActivityKit", targets: ["NotificationDevActivityKit"]),
    ],
    targets: [
        .target(name: "NotificationDevActivityKit"),
        .binaryTarget(name: "NotificationCore", path: "Artifacts/NotificationCore.xcframework"),
        .target(name: "NotificationDev", dependencies: ["NotificationCore", "NotificationDevActivityKit"]),
        .testTarget(name: "NotificationDevTests", dependencies: ["NotificationDev"]),
    ]
)

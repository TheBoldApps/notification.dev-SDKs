// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NotificationDev",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "NotificationDev", targets: ["NotificationDev"]),
        .library(name: "NotificationDevActivityKit", targets: ["NotificationDevActivityKit"]),
        .library(name: "NotificationDevServiceExtension", targets: ["NotificationDevServiceExtension"]),
    ],
    targets: [
        .target(name: "NotificationDevServiceExtension", dependencies: ["NotificationDevCommunication"]),
        .target(name: "NotificationDevCommunication"),
        .target(name: "NotificationDevActivityKit"),
        .binaryTarget(name: "NotificationCore", path: "Artifacts/NotificationCore.xcframework"),
        .target(name: "NotificationDev", dependencies: ["NotificationCore", "NotificationDevActivityKit", "NotificationDevCommunication"]),
        .testTarget(name: "NotificationDevTests", dependencies: ["NotificationDev", "NotificationDevServiceExtension"]),
    ]
)

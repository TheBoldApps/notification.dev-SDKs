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
        .target(name: "NotificationDevCommunication", path: "ios/Sources/NotificationDevCommunication"),
        .target(name: "NotificationDevServiceExtension", dependencies: ["NotificationDevCommunication"], path: "ios/Sources/NotificationDevServiceExtension"),
        .target(name: "NotificationDevActivityKit", path: "ios/Sources/NotificationDevActivityKit"),
        .binaryTarget(
            name: "NotificationCore",
            url: "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/0.3.1/NotificationCore.xcframework.zip",
            checksum: "7824f50128f74c3b5459dd0ca0b3e2a99c9f5f90c22f55dce9c3a1a416f98309"
        ),
        .target(
            name: "NotificationDev",
            dependencies: ["NotificationCore", "NotificationDevActivityKit", "NotificationDevCommunication"],
            path: "ios/Sources/NotificationDev"
        ),
        .testTarget(
            name: "NotificationDevTests",
            dependencies: ["NotificationDev", "NotificationDevServiceExtension"],
            path: "ios/Tests/NotificationDevTests"
        ),
    ]
)

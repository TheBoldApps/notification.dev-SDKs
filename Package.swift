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
            url: "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/0.4.1/NotificationCore.xcframework.zip",
            checksum: "fadabed1fab370a2e8c5de5a4d7fcce6200ce2945288df2b191aa08a31c38c73"
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

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
        .target(name: "NotificationDevServiceExtension", path: "ios/Sources/NotificationDevServiceExtension"),
        .target(name: "NotificationDevActivityKit", path: "ios/Sources/NotificationDevActivityKit"),
        .binaryTarget(
            name: "NotificationCore",
            url: "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/0.3.0/NotificationCore.xcframework.zip",
            checksum: "b41425a461e7d75c2f26462632f09930694fb86c4e7a7397a8029bcc01a4477c"
        ),
        .target(
            name: "NotificationDev",
            dependencies: ["NotificationCore", "NotificationDevActivityKit"],
            path: "ios/Sources/NotificationDev"
        ),
        .testTarget(
            name: "NotificationDevTests",
            dependencies: ["NotificationDev", "NotificationDevServiceExtension"],
            path: "ios/Tests/NotificationDevTests"
        ),
    ]
)

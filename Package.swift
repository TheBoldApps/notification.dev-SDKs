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
            url: "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/0.4.0/NotificationCore.xcframework.zip",
            checksum: "ab390b0822f87ad148440bccfe96494e203030d16cf0214b9e376fc3aef48d0d"
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

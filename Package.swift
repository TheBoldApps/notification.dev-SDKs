// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NotificationDev",
    platforms: [.iOS(.v15)],
    products: [.library(name: "NotificationDev", targets: ["NotificationDev"])],
    targets: [
        .binaryTarget(
            name: "NotificationCore",
            url: "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/0.2.0/NotificationCore.xcframework.zip",
            checksum: "fbd45a25ee2465a14be63d890f05d4f40f437ab5e5ec7ee443ed53b9aa9b1505"
        ),
        .target(
            name: "NotificationDev",
            dependencies: ["NotificationCore"],
            path: "ios/Sources/NotificationDev"
        ),
        .testTarget(
            name: "NotificationDevTests",
            dependencies: ["NotificationDev"],
            path: "ios/Tests/NotificationDevTests"
        ),
    ]
)

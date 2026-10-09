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
            checksum: "42d8dbd8cee03ca2d4b277e7c88a365efcb1a1a5ffcdc53852ddf2ebdabea0b6"
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

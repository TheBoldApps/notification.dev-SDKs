// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NotificationDev",
    platforms: [.iOS(.v15)],
    products: [.library(name: "NotificationDev", targets: ["NotificationDev"])],
    targets: [
        .binaryTarget(
            name: "NotificationCore",
            url: "https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/0.1.0/NotificationCore.xcframework.zip",
            checksum: "389c503801066c5e665eb390627f073628c57fb11337928469f685c6adb26290"
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

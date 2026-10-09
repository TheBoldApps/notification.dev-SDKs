// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "notification_dev",
    platforms: [.iOS(.v15)],
    products: [.library(name: "notification-dev", targets: ["notification_dev"])],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        .package(url: "https://github.com/TheBoldApps/notification.dev-SDKs.git", exact: "0.2.0"),
    ],
    targets: [
        .target(
            name: "notification_dev",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "NotificationDev", package: "notification.dev-SDKs"),
            ]
        )
    ]
)

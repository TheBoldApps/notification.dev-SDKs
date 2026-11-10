// swift-tools-version: 5.9
import PackageDescription
import Foundation

// Local contributor builds must use the same native sources as the Dart bridge.
let sdk: Package.Dependency = {
    if let path = ProcessInfo.processInfo.environment["NOTIFICATION_DEV_SWIFT_PACKAGE_PATH"] {
        return .package(name: "notification.dev-SDKs", path: path)
    }
    return .package(url: "https://github.com/TheBoldApps/notification.dev-SDKs.git", exact: "0.2.0")
}()

let package = Package(
    name: "notification_dev",
    platforms: [.iOS(.v15)],
    products: [.library(name: "notification-dev", targets: ["notification_dev"])],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        sdk,
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

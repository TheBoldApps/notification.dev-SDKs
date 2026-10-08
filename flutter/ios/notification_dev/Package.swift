// swift-tools-version: 5.9
import Foundation
import PackageDescription

// Resolve the Flutter plugin symlink before locating the repository-local native SDK.
let nativeSDK = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath()
    .deletingLastPathComponent().appendingPathComponent("../../../ios").standardized.path

let package = Package(
    name: "notification_dev",
    platforms: [.iOS(.v15)],
    products: [.library(name: "notification-dev", targets: ["notification_dev"])],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        .package(name: "NotificationDev", path: nativeSDK),
    ],
    targets: [
        .target(
            name: "notification_dev",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "NotificationDev", package: "NotificationDev"),
            ]
        )
    ]
)

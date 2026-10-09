# iOS SDK

Swift API for iOS 15+, using direct APNs. Installing a published release requires Xcode; the Kotlin core is supplied as a precompiled XCFramework. Supports devices and Apple Silicon simulators; CocoaPods and Intel simulators are unsupported.

## Install with Swift Package Manager

In Xcode, choose **File > Add Package Dependencies**, enter
`https://github.com/TheBoldApps/notification.dev-SDKs`, select version `0.2.0`,
and add the `NotificationDev` product to your app target.

For another Swift package, declare:

```swift
.package(url: "https://github.com/TheBoldApps/notification.dev-SDKs", exact: "0.2.0")
```

Add `.product(name: "NotificationDev", package: "notification.dev-SDKs")` to
your target's dependencies. Swift Package Manager downloads the matching Kotlin
core automatically and verifies its archive checksum. No Gradle or Maven setup
is needed in the consuming app.

## Install locally

Building the SDK locally requires macOS, Xcode, JDK 17, and Android SDK 36.
From the repository root:

```sh
./gradlew :core:prepareSwiftPackage
```

Add `ios/` as a local Swift package in Xcode and link `NotificationDev`. Rebuild after core or contract changes; the XCFramework is ignored by Git.

The root package manifest is for published releases; `ios/Package.swift` is for
local development. See [the iOS release procedure](../RELEASING.md).

## Initialize and use

Public APIs use `@MainActor`. Keep a startup task so delegate callbacks can await initialization.

```swift
import NotificationDev

let sdk = try await NotificationDev.initialize(SdkConfig(
    projectId: "YOUR_PUBLIC_PROJECT_UUID"
))
try await sdk.login("opaque-customer-id")
try await sdk.setTags(["plan": .string("premium")])
try await sdk.setEmail("customer@example.com", optedIn: true)
try await sdk.setPushOptedIn(true)
for await state in sdk.states { /* Update your UI. */ }
```

See [shared behavior](../README.md#shared-behavior). Observe `diagnostics` for errors. Cancel stream tasks to release observers.

## Permissions and APNs

Enable Push Notifications and configure signing plus a server APNs provider matching the bundle ID and environment. Keep provider credentials on the server.

Await startup and forward these delegate callbacks on the main actor:

| Callback | SDK method |
| --- | --- |
| APNs token | `didRegisterForRemoteNotifications(deviceToken:)` |
| APNs registration failure | `didFailToRegisterForRemoteNotifications()` |
| Foreground notification | `presentationOptions(for:)` |
| Notification response | `handleResponse(_:)` |

Call Apple's completion handlers exactly once. A `nil` presentation result means another provider's notification; empty options suppress an owned notification. The [SwiftUI example](../example/ios-example/README.md) shows complete forwarding.

Request permission with `requestPushPermission(settingsFallback: true)`; inspect `getPushPermissionStatus()`. Settings opening does not confirm permission. APNs token registration runs automatically.

Collect `pendingOpens`, navigate, then `try await sdk.acknowledgeOpen(open.interactionId)`. Foreground display can be disabled with `displayInForeground: false`.

Background alerts may appear before SDK checks run. Receipts cover SDK-observed messages; APNs acceptance does not confirm delivery. Pending sync resumes when iOS next allows execution.

## Verify

Prepare the framework, then run from `ios/`:

```sh
xcodebuild -scheme NotificationDev -destination 'platform=iOS Simulator,id=SIMULATOR_UUID' -derivedDataPath /tmp/notification-ios-tests test CODE_SIGNING_ALLOWED=NO ARCHS=arm64
```

Use the example's [device checks](../example/ios-example/README.md#device-checks) for real APNs delivery.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions. The 0.2.0 flag replaces the
previous API without compatibility aliases or startup configuration migration.

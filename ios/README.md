# iOS SDK

Swift API for iOS 15+, using direct APNs. Requires macOS, Xcode, JDK 17, and Android SDK 36 for the shared Gradle build. Supports devices and Apple Silicon simulators; CocoaPods and Intel simulators are unsupported.

## Install locally

From the repository root:

```sh
./gradlew :core:prepareSwiftPackage
```

Add `ios/` as a local Swift package in Xcode and link `NotificationDev`. Rebuild after core or contract changes; the XCFramework is ignored by Git.

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

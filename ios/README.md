# iOS SDK

Swift API for iOS 15+, using direct APNs. Installing a published release requires Xcode; the Kotlin core is supplied as a precompiled XCFramework. Supports devices and Apple Silicon simulators; CocoaPods and Intel simulators are unsupported.

## Integrate with an AI agent

Connect an MCP client using Streamable HTTP to
`https://app.notification.dev/mcp` and complete the OAuth sign-in and consent flow.
Ask: “Use `get_sdk_integration_guide` with `platform: "ios"` and integrate
notification.dev into this app.” The tool supports `android`, `ios`, `flutter`,
and `expo`; retrieving a guide requires no additional connection permissions.

The [integration guides](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/ios.md) cover configuration, usage, troubleshooting,
and device verification. This README remains usable without MCP. Agents use OAuth;
mobile apps initialize with the public project UUID, not the agent token or a
server API key.

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

| Callback                  | SDK method                                        |
| ------------------------- | ------------------------------------------------- |
| APNs token                | `didRegisterForRemoteNotifications(deviceToken:)` |
| APNs registration failure | `didFailToRegisterForRemoteNotifications()`       |
| Foreground notification   | `presentationOptions(for:)`                       |
| Notification response     | `handleResponse(_:)`                              |

Call Apple's completion handlers exactly once. A `nil` presentation result means another provider's notification; empty options suppress an owned notification. The [SwiftUI example](../example/ios-example/README.md) shows complete forwarding.

Request permission with `requestPushPermission(settingsFallback: true)`; inspect `getPushPermissionStatus()`. Settings opening does not confirm permission. APNs token registration runs automatically.

Foreground display can be disabled with `displayInForeground: false`.

Background alerts may appear before SDK checks run. Receipts cover SDK-observed messages; APNs acceptance does not confirm delivery. Pending sync resumes when iOS next allows execution.

## Handle notification opens

`pendingOpens` emits unacknowledged notification taps, including the tap that
launched the app. Subscribe once after your navigation UI is ready, route the
notification, then acknowledge it. `notifications` reports received notifications;
use `pendingOpens` for taps. The SDK does not launch deep links automatically.

```swift
let opensTask = Task { @MainActor in
    for await pending in sdk.pendingOpens {
        for open in pending {
            try Task.checkCancellation()
            try await navigateFromNotification(open.notification.deepLink, open.notification.data)
            try await sdk.acknowledgeOpen(open.interactionId)
        }
    }
}
```

The task handles opens serially. Retain it, observe errors through
`opensTask.value`, and call `opensTask.cancel()` when the navigation owner is
disposed. A thrown error ends the task. Forward notification responses using
`handleResponse(_:)` as shown in Permissions and APNs.

The app supplies `navigateFromNotification`. Navigation must complete before
acknowledgment. Route only links/data your app supports.
Snapshots can include the same open more than once, even while acknowledgment is
in progress, so routing should safely select an already-open destination. For
non-repeatable actions, deduplicate by `interactionId` in your routing layer.
If handling fails, the open stays pending; retry through your app's retry flow or
restart the observer. A new snapshot is not guaranteed immediately.

## Verify your integration

On a device with matching FCM/APNs providers, send a notification and tap it while
the app is running, backgrounded, and after process termination. Verify navigation,
replay before acknowledgment, and no replay after successful acknowledgment.
See the [integration checks](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/ios.md#verify-the-integration)
for permission, registration, and offline checks.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

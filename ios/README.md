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

`pendingOpens` is the notification-open callback/stream. It emits a list of
unacknowledged taps, including taps that launched the app. Subscribe once in your
app's navigation layer after initialization. `notifications` reports received
notifications; use `pendingOpens` for taps.

Opens replay until acknowledged. Handle them serially, wait for navigation to be
ready, and acknowledge only after successful handling. The SDK does not launch
deep links automatically. `open.notification.deepLink` and
`open.notification.data` contain your routing information.

```swift
// After initialization, in your @MainActor navigation owner:
let opensTask = Task { @MainActor in
    var navigated = Set<String>()
    for await pending in sdk.pendingOpens {
        for open in pending {
            do {
                try Task.checkCancellation()
                if !navigated.contains(open.interactionId) {
                    try await waitForNavigationReady()
                    try await navigateFromNotification(open.notification.deepLink, open.notification.data)
                    navigated.insert(open.interactionId)
                }
                try await sdk.acknowledgeOpen(open.interactionId)
            } catch is CancellationError {
                return
            } catch {
                reportOpenError(error)
                break
            }
        }
    }
}

// When the navigation owner stops observing:
// opensTask.cancel()
```

Retain the task and cancel it when its owner is disposed. Keep just one active
open observer, and forward notification responses using `handleResponse(_:)` as
shown in Permissions and APNs.

The app supplies `waitForNavigationReady`, `navigateFromNotification`, and
`reportOpenError`; navigation must finish successfully before returning. Route
only links/data your app supports. The handled-ID set prevents repeated navigation
when snapshots replay during acknowledgment; retain it for the observer's lifetime.
On failure, the open remains pending. Retry through your app's retry flow or by
restarting the observer; a new snapshot is not guaranteed immediately.

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

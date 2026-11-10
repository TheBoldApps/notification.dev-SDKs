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

## Live Activities

Use `sdk.liveActivities` for automatic ActivityKit setup, local starts, and manual
`enter`/`exit` token registration. Supports custom typed attributes and default
JSON attributes, single-user or shared multi-user activities, and durable token
retries. Local starts require iOS 16.1+ and remote starts iOS 17.2+.
See the [Live Activities guide](LIVE_ACTIVITIES.md) for widget setup, registration
grants, identity handling, and examples. Link the lightweight
`NotificationDevActivityKit` product to the widget extension.

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

## Advanced push

See the [advanced integration guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/advanced-push.md) for platform options, app/environment routing, foreground suppression, images, credential revocation, and provider coexistence.

## Reset local development state

If initialization reports `CONFIGURATION_CHANGED` after changing projects or API
hosts, call `try NotificationDev.resetLocalState()` before initializing again.
This discards local SDK credentials, pending edits/events, and pending opens, and
the next initialization creates a fresh installation. It does not delete backend
records or other app data. An already initialized client must be stopped by
restarting the app before reset. The iOS example offers **Reset SDK state and
reconnect** after an initialization failure, so reinstalling is unnecessary.

## iOS image notifications

An `imageUrl` alone does not attach an image on iOS. The installed app must embed
an enabled **Notification Service Extension**, and the backend automatically enables image processing when
`imageUrl` is set, even if `ios.mutableContent` is omitted or false. Installing the main SDK package alone does not add
this extension.

1. Add a Notification Service Extension target to the app in Xcode. Embed its
   `.appex` in the main app and add a target dependency so it builds with the app.
2. Link only the `NotificationDevServiceExtension` Swift package product to the
   extension, with `APPLICATION_EXTENSION_API_ONLY = YES`.
3. Match the main app's signing team. Use a distinct extension bundle ID prefixed
   with the main app bundle ID, such as `<app-bundle-id>.service-extension`.
4. Set the extension's Info.plist entries under `NSExtension`:
   `NSExtensionPointIdentifier = com.apple.usernotifications.service` and
   `NSExtensionPrincipalClass = $(PRODUCT_MODULE_NAME).NotificationService`.
5. Use this extension principal class:

```swift
import NotificationDevServiceExtension

final class NotificationService: NotificationDevServiceExtension.NotificationService, @unchecked Sendable {}
```

Set the image URL alongside the title and body:

```json
{
  "title": "Image test",
  "body": "Expand to see the image",
  "imageUrl": "https://your-host.example/image.jpg"
}
```

Use a direct HTTPS JPEG, PNG, or GIF URL with no redirects, at most 2 MiB,
downloadable within five seconds. In the dashboard, just set the image URL. Build and run the updated app on a
physical device, background it, and send a fresh notification. Press and hold
the new notification to inspect its image; already delivered notifications are
not enriched retroactively. If the image is missing, check the embedded extension,
its principal class, and the image's HTTP response,
content type, size, and download time. Download failures preserve the original
alert. See the [native example](https://github.com/TheBoldApps/notification.dev-SDKs/tree/main/example/ios-example/ServiceExtension).

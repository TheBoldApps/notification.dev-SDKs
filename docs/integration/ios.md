# iOS SDK integration

This guide targets SDK release `0.2.0`. Inspect the app's existing notification
integration before changing startup or callback ownership. Use the public project
UUID from the dashboard, never a server API key. Configure matching FCM/APNs
providers in the dashboard; MCP does not configure provider credentials.

Canonical source: [this guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/ios.md).
The [platform README](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/ios/README.md) also covers local SDK development.

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

See [shared behavior](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/README.md#shared-behavior). Observe `diagnostics` for errors. Cancel stream tasks to release observers.

## Permissions and APNs

Enable Push Notifications and configure signing plus a server APNs provider matching the bundle ID and environment. Keep provider credentials on the server.

Await startup and forward these delegate callbacks on the main actor:

| Callback                  | SDK method                                        |
| ------------------------- | ------------------------------------------------- |
| APNs token                | `didRegisterForRemoteNotifications(deviceToken:)` |
| APNs registration failure | `didFailToRegisterForRemoteNotifications()`       |
| Foreground notification   | `presentationOptions(for:)`                       |
| Notification response     | `handleResponse(_:)`                              |

Call Apple's completion handlers exactly once. A `nil` presentation result means another provider's notification; empty options suppress an owned notification. The [SwiftUI example](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/example/ios-example/README.md) shows complete forwarding.

Request permission with `requestPushPermission(settingsFallback: true)`; inspect `getPushPermissionStatus()`. Settings opening does not confirm permission. APNs token registration runs automatically.

Collect `pendingOpens`, navigate, then `try await sdk.acknowledgeOpen(open.interactionId)`. Foreground display can be disabled with `displayInForeground: false`.

Background alerts may appear before SDK checks run. Receipts cover SDK-observed messages; APNs acceptance does not confirm delivery. Pending sync resumes when iOS next allows execution.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions.

## Delegate forwarding example

Add these members to your `@MainActor` app delegate, which must conform to
`UIApplicationDelegate` and `UNUserNotificationCenterDelegate`. Import UIKit,
UserNotifications, and NotificationDev. Call `startSdk()` in
`application(_:didFinishLaunchingWithOptions:)` before returning `true`.
For SwiftUI, attach this delegate using `@UIApplicationDelegateAdaptor`.

```swift
private var startup: Task<NotificationDev, Error>?

private func report(_ error: Error) {
    // Surface initialization/callback errors in your diagnostics UI.
}

private func startSdk() {
    UNUserNotificationCenter.current().delegate = self
    startup = Task {
        try await NotificationDev.initialize(SdkConfig(
            projectId: "YOUR_PUBLIC_PROJECT_UUID"
        ))
    }
}

    func application(
        _ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task {
            do {
                try await startup?.value.didRegisterForRemoteNotifications(deviceToken: deviceToken)
            } catch { report(error) }
        }
    }

    func application(
        _ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task {
            do { try await startup?.value.didFailToRegisterForRemoteNotifications() } catch {
                report(error)
            }
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        Task { @MainActor in
            let client = try? await startup?.value
            let options = await client?.presentationOptions(for: notification)
            completionHandler(options ?? [.banner, .sound])
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        Task { @MainActor in
            let client = try? await startup?.value
            _ = await client?.handleResponse(response)
            completionHandler()
        }
    }
```

The fallback foreground options apply only when the SDK returns `nil` for an
unowned notification or initialization is unavailable. Empty options from the SDK
suppress an owned notification. Every completion handler is called exactly once.
Observe `startup.value` errors as well as the SDK diagnostics stream.

## iOS host checklist

For SwiftUI, attach a `UIApplicationDelegateAdaptor`; for UIKit, use the existing
application delegate. Set `UNUserNotificationCenter.current().delegate` during
startup, retain the initialization task, and await it before forwarding callbacks.
Use the callback signatures and completion-handler implementation in the
[SwiftUI host example](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/example/ios-example/Sources/NotificationExampleApp.swift).

Enable the Push Notifications capability on the app target. Match the APNs
provider's bundle ID and sandbox/production environment to the signed app's
`aps-environment` entitlement. Do not embed APNs keys in the app. A build with
`CODE_SIGNING_ALLOWED=NO` is a compile check only: Keychain storage requires a
signed app, including an ad-hoc signed simulator build.

## Shared identity and synchronization behavior

Initialize once per process with stable configuration. Initialization establishes
local readiness; inspect state and diagnostics for server registration and sync
failures. Setters update local state and queue synchronization rather than proving
server acceptance. `refresh()` retains pending local edits.

Tags and email belong to the user; push preference belongs to the installation.
Email preference, push preference, and OS permission are independent. Login uses
unverified external IDs: use opaque random IDs and retry the same ID after an
ambiguous result. Association changes discard pending work; logout creates a new
anonymous association without unsubscribing the old user's email.

Events persist offline and retry for up to seven days. App opens are automatic;
`notification_dev.app_opened` and `notification_dev.subscriber_created` are reserved.
Pending notification opens replay until acknowledged. Serialize navigation,
deduplicate in-flight work by `interactionId`, and acknowledge only after handling
the open. The SDK does not launch deep links automatically.

## Verify the integration

Use a disposable project and a signed app on a device with matching providers:

1. Start the app and inspect registration, push token status, sync state, and diagnostics.
2. Request permission from a user action, enable SDK push preference, and verify the
   installation appears in the dashboard. Also test denial and return from settings.
3. Send a campaign from the dashboard while foregrounded and backgrounded. Android
   requires data-only FCM; Firebase console notification messages do not exercise
   the SDK path. Provider acceptance alone does not prove device delivery.
4. Tap a notification while running, backgrounded, and after process termination.
   Verify routing, replay before acknowledgment, and no replay after acknowledgment.
   Android force-stop prevents delivery until the app is opened again.
5. Edit tags/email and track an event offline; restart and reconnect, then verify
   sync. Log in/out and verify the new association and notification handling.

## Troubleshooting

- No server registration: verify the project UUID and API host, inspect diagnostics
  and pending state, and check network access. Keep provider secrets on the server.
- Registered but no push: check OS permission, SDK push preference, token status,
  provider credentials, Android Firebase project, and iOS bundle ID/environment.
- Missing opens: check callback ownership and forwarding, subscribe to pending
  opens at app startup, and avoid acknowledging before navigation completes.
- Local storage failure: inspect the diagnostic code; iOS needs signing and
  Keychain access. Clear development app data when changing projects or hosts.
- HTTP development backend: explicitly allow HTTP in SDK configuration and host OS
  transport policy. Android emulators reach the host at `10.0.2.2`; iOS simulators
  can use `localhost`. Keep production endpoints on HTTPS.

Copyright 2026 The Bold Apps, LLC. Licensed under
[Apache-2.0](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/LICENSE).

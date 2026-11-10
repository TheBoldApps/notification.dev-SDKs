# iOS example

## Setup

1. Run `./gradlew :core:prepareSwiftPackage` from the repository root.
2. Open `NotificationExample.xcodeproj`; select **NotificationExample**.
3. Replace `YOUR_PUBLIC_PROJECT_UUID` in `Sources/NotificationExampleApp.swift` with your notification.dev project UUID.
4. Run on an Apple Silicon simulator, or configure your signing team and bundle ID for a device.
5. For APNs, enable Push Notifications and configure one matching provider in notification.dev. The development entitlement uses sandbox APNs; distribution needs matching production signing and provider settings.

The app forwards delegates explicitly and displays deep links as text. See [iOS integration](../../ios/README.md).

## Build

From this directory:

```sh
xcodebuild -project NotificationExample.xcodeproj -scheme NotificationExample -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/notification-ios-example build CODE_SIGNING_ALLOWED=NO
```

To run hosted integration tests, disable the app's startup initialization first so tests can create their isolated client, then run:

```sh
xcodebuild -project NotificationExample.xcodeproj -scheme NotificationExample -destination 'platform=iOS Simulator,id=SIMULATOR_UUID' -derivedDataPath /tmp/notification-ios-hosted test
```

## Device checks

Use a disposable project:

- Verify registration, permission changes, and a real APNs campaign.
- Tap alerts while foregrounded, backgrounded, and terminated; restart before acknowledgment and verify replay.
- Edit offline, relaunch, and reconnect; verify synchronization.
- Verify opt-out/logout clears SDK notifications, and stale/expired foreground messages are suppressed.
- Test sandbox/production separately, iOS 15 and a current release, and storage access before first unlock.

Background APNs alerts may appear before SDK eligibility checks. Simulator tests do not establish device delivery.

## Live Activities

The project includes `NotificationExampleWidget` with both typed delivery and
default dictionary widgets. Set `LiveActivityProviderConfigurationId` in the main
app's Info.plist to your APNs provider UUID. Match app and widget bundle IDs to
your signing setup. Startup sets up observers for both attribute types, including
background launches.

Use the Live Activities form to start a default or typed activity with a fresh
activity ID and registration grant from your authenticated backend. The backend
grant must match the type, installation, and current association. See the
[SDK guide](../../ios/LIVE_ACTIVITIES.md) for the grant request and remote start,
update, and end payloads. Keep project API keys on your backend.

For the default widget send state inside `eventUpdates.data`; the typed widget
expects `status` and `minutesRemaining` directly in `eventUpdates`. The local end
button ends the selected UI and unregisters it. Verify local and remote starts,
shared audiences on two devices, relaunch, permission changes, and account
switching on physical devices.

### Local simulator launch configuration

You can override the example project and API at launch without editing source.
The example remembers these public settings for later background launches:

```sh
env SIMCTL_CHILD_NOTIFICATION_DEV_PROJECT_ID=<project-uuid> \
    SIMCTL_CHILD_NOTIFICATION_DEV_BASE_URL=http://127.0.0.1:<local-port>/ \
    SIMCTL_CHILD_NOTIFICATION_DEV_LIVE_ACTIVITY_PROVIDER_ID=<provider-uuid> \
    xcrun simctl launch <simulator-uuid> dev.notification.example.ios
```

Build simulator runs with signing enabled (`CODE_SIGNING_ALLOWED=YES
CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO`) so the example's application and
Keychain entitlements are embedded. The unsigned commands above verify compilation;
they do not produce a runnable Keychain integration. To change the launch project
or API, stop the app, update the scheme environment variables, and run again.
If initialization reports `CONFIGURATION_CHANGED`, tap **Reset SDK state and
reconnect**. This creates a fresh SDK installation and discards pending local SDK
work while leaving backend records and other app data intact.

### Random number server

The [randoms Python server](randoms-server/README.md) starts a widget titled
`randoms` and sends random numbers through the server-to-server API every two
seconds. It uses a server-side project API key and exposes a local status endpoint.

## Image notifications

The app embeds `NotificationExampleServiceExtension`, which links only the
`NotificationDevServiceExtension` Swift package product. Use the same signing
team as the main app and an extension bundle ID prefixed by the main app bundle
ID (for example, `<app-bundle-id>.service-extension`).

Set the notification's image URL; the backend automatically enables iOS image
processing. Use a direct HTTPS JPEG, PNG, or GIF URL, no
redirects, at most 2 MiB, downloadable within five seconds. Run the updated app
on the device, send a fresh notification with the app in the background, then
press and hold it to inspect the attachment. Already delivered notifications
are not enriched retroactively. A download failure preserves the original alert.

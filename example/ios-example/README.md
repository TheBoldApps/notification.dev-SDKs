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

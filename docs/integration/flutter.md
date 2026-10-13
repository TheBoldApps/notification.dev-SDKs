# Flutter SDK integration

This guide targets SDK release `0.2.0`. Inspect the app's existing notification
integration before changing startup or callback ownership. Use the public project
UUID from the dashboard, never a server API key. Configure matching FCM/APNs
providers in the dashboard; MCP does not configure provider credentials.

Canonical source: [this guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/flutter.md).
The [platform README](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/flutter/README.md) covers released installation and usage.

Android/iOS plugin over the native SDKs. Requires Flutter 3.44+, Dart 3.12+, JDK 17, Android SDK 36, and Swift Package Manager for iOS. Supports Android API 23+, iOS 15+, and Apple Silicon simulators.

## Install

```yaml
dependencies:
  notification_dev: ^0.2.0
```

Run `flutter pub get`. Android downloads the native SDK from Maven Central; iOS downloads the released Swift package from GitHub. No native SDK checkout or local Gradle composite build is required.

Enable Swift Package Manager for iOS with `flutter config --enable-swift-package-manager`.

## Host setup

- Android: configure Firebase and the Google Services plugin. Add a monochrome `ic_notification` drawable and retain it during resource shrinking, as in the example. Default integration cannot be combined with `firebase_messaging`.
- iOS: enable Push Notifications, configure signing and a matching APNs provider, and retain standard `FlutterAppDelegate` forwarding. Exclude `x86_64` simulator architecture after including `Generated.xcconfig`: `EXCLUDED_ARCHS[sdk=iphonesimulator*] = $(inherited) x86_64`.

See the [full example](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/example/flutter-example/README.md) for complete platform configuration. CocoaPods and web/desktop are unsupported.

## Native host configuration

In `android/settings.gradle.kts`, add the Google Services plugin using the
version selected for your host project, alongside its existing plugin declarations.
Apply `id("com.google.gms.google-services")` in `android/app/build.gradle.kts`.
Place the Firebase client configuration at `android/app/google-services.json`;
its package must match `applicationId`. Keep `google()` and `mavenCentral()` in
dependency repositories.

Add `android/app/src/main/res/drawable/ic_notification.xml` (or a PNG) with a
monochrome icon. Retain the icon through resource shrinking with
`android/app/src/main/res/raw/keep.xml`:

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_notification" />
```

In Xcode, enable Push Notifications on Runner and select the signing team and
bundle ID. Configure a matching sandbox/production APNs provider in the dashboard.
Retain standard Flutter application delegate forwarding; automatic integration
installs SDK callback handling. Use a signed app for Keychain access.

## Initialize and use

See the [minimal Flutter app](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/flutter/example/main.dart) for initialization and a permission button.

```dart
import 'package:notification_dev/notification_dev.dart';

WidgetsFlutterBinding.ensureInitialized();
final sdk = await NotificationDev.initialize(const SdkConfig(
  projectId: 'YOUR_PUBLIC_PROJECT_UUID',
  androidSmallIcon: 'ic_notification',
));
await sdk.login('opaque-customer-id');
await sdk.setTags({'plan': 'premium'});
await sdk.setEmail('customer@example.com', optedIn: true);
await sdk.setPushOptedIn(true);
await sdk.track('checkout', properties: {'total': 42});
final subscription = sdk.states.listen((state) { /* Update your UI. */ });
```

Initialization waits for local readiness, not server registration. Automatic integration handles native startup and push callbacks without host code edits. Use the same configuration each startup. See [shared behavior](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/README.md#shared-behavior).

`getState()`, `getTags()`, and `getEmail()` read local values; `refresh()` fetches server state. Tags accept strings, booleans, and finite numbers; event properties accept JSON. Failures throw `SdkException`. Cancel stream subscriptions when finished.

## Permissions

```dart
await sdk.requestPushPermission(settingsFallback: true);
final status = await sdk.getPushPermissionStatus();
```

`notifications` and `diagnostics` are live streams without replay; `states` and
`pendingOpens` replay current values.

## Handle notification opens

`pendingOpens` is the notification-open callback/stream. It emits a list of
unacknowledged taps, including taps that launched the app. Subscribe once in your
app's navigation layer after initialization. `notifications` reports received
notifications; use `pendingOpens` for taps.

Opens replay until acknowledged. Handle them serially, wait for navigation to be
ready, and acknowledge only after successful handling. The SDK does not launch
deep links automatically. `open.notification.deepLink` and
`open.notification.data` contain your routing information.

```dart
final navigated = <String>{};
final opensSubscription = sdk.pendingOpens.asyncMap((pending) async {
  for (final open in pending) {
    try {
      if (!navigated.contains(open.interactionId)) {
        await waitForNavigationReady();
        await navigateFromNotification(
          open.notification.deepLink,
          open.notification.data,
        );
        navigated.add(open.interactionId);
      }
      await sdk.acknowledgeOpen(open.interactionId);
    } catch (error) {
      reportOpenError(error);
      break;
    }
  }
}).listen((_) {}, onError: (Object error) => reportOpenError(error));

// When the navigation owner stops observing:
// await opensSubscription.cancel();
```

`asyncMap` serializes snapshots; avoid an asynchronous `listen` callback that
starts overlapping navigation. Keep just one active open subscription and cancel
it when its owner is disposed. Automatic integration forwards native tap callbacks.

The app supplies `waitForNavigationReady`, `navigateFromNotification`, and
`reportOpenError`; navigation must finish successfully before returning. Route
only links/data your app supports. The handled-ID set prevents repeated navigation
when snapshots replay during acknowledgment; retain it for the observer's lifetime.
On failure, the open remains pending. Retry through your app's retry flow or by
restarting the observer; a new snapshot is not guaranteed immediately.

## Manual integration

For custom callback ownership, set `automaticIntegration: false` from the first initialization. Initialize the native SDK at host startup with matching settings and forward [Android](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/android/README.md#notifications-and-permissions) or [iOS](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/ios/README.md#permissions-and-apns) callbacks. Manual mode does not restore startup configuration.

For custom WorkManager setup, remove `NotificationSdkInitializer` metadata from the AndroidX Startup provider and initialize WorkManager before the SDK. Remove the SDK Firebase service if your host owns FCM. Manual mode does not establish compatibility with other push plugins.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions.

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

# Flutter SDK

Android/iOS plugin over the native SDKs. Requires Flutter 3.44+, Dart 3.12+, JDK 17, Android SDK 36, and Swift Package Manager for iOS. Supports Android API 23+, iOS 15+, and Apple Silicon simulators.

## Integrate with an AI agent

Connect an MCP client using Streamable HTTP to
`https://app.notification.dev/mcp` and complete the OAuth sign-in and consent flow.
Ask: “Use `get_sdk_integration_guide` with `platform: "flutter"` and integrate
notification.dev into this app.” The tool supports `android`, `ios`, `flutter`,
and `expo`; retrieving a guide requires no additional connection permissions.

The [integration guides](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/flutter.md) cover configuration, usage, troubleshooting,
and device verification. This README remains usable without MCP. Agents use OAuth;
mobile apps initialize with the public project UUID, not the agent token or a
server API key.

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

## Initialize and use

See the [minimal Flutter app](example/main.dart) for initialization and a permission button.

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

`pendingOpens` emits unacknowledged notification taps, including the tap that
launched the app. Subscribe once after your navigation UI is ready, route the
notification, then acknowledge it. `notifications` reports received notifications;
use `pendingOpens` for taps. The SDK does not launch deep links automatically.

```dart
final opensSubscription = sdk.pendingOpens.asyncMap((pending) async {
  for (final open in pending) {
    await navigateFromNotification(open.notification.deepLink, open.notification.data);
    await sdk.acknowledgeOpen(open.interactionId);
  }
}).listen((_) {}, onError: reportOpenError);
```

`asyncMap` keeps handling serial. Cancel with `await opensSubscription.cancel()`
when the navigation owner is disposed. Automatic integration forwards tap callbacks.

The app supplies `navigateFromNotification` and `reportOpenError`. Navigation
must complete before acknowledgment. Route only links/data your app supports.
Snapshots can include the same open more than once, even while acknowledgment is
in progress, so routing should safely select an already-open destination. For
non-repeatable actions, deduplicate by `interactionId` in your routing layer.
If handling fails, the open stays pending; retry through your app's retry flow or
restart the observer. A new snapshot is not guaranteed immediately.

## Manual integration

For custom callback ownership, set `automaticIntegration: false` from the first initialization. Initialize the native SDK at host startup with matching settings and forward [Android](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/android/README.md#notifications-and-permissions) or [iOS](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/ios/README.md#permissions-and-apns) callbacks. Manual mode does not restore startup configuration.

For custom WorkManager setup, remove `NotificationSdkInitializer` metadata from the AndroidX Startup provider and initialize WorkManager before the SDK. Remove the SDK Firebase service if your host owns FCM. Manual mode does not establish compatibility with other push plugins.

## Verify your integration

On a device with matching FCM/APNs providers, send a notification and tap it while
the app is running, backgrounded, and after process termination. Verify navigation,
replay before acknowledgment, and no replay after successful acknowledgment.
See the [integration checks](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/flutter.md#verify-the-integration)
for permission, registration, and offline checks.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

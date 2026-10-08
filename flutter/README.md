# Flutter SDK

Android/iOS plugin over the native SDKs. Requires Flutter 3.44+, Dart 3.12+, JDK 17, Android SDK 36, and Swift Package Manager for iOS. Supports Android API 23+, iOS 15+, and Apple Silicon simulators.

## Install locally

```yaml
dependencies:
  notification_dev:
    path: /path/to/notification.dev-SDKs/flutter
```

Add the [Android composite build](../android/README.md#install-locally) to your app's `android/settings.gradle.kts`. For iOS, run `./gradlew :core:prepareSwiftPackage` from the SDK repository root.

Host setup:

- Android: configure Firebase and the Google Services plugin. Add a monochrome `ic_notification` drawable and retain it during resource shrinking, as in the example. Default integration cannot be combined with `firebase_messaging`.
- iOS: enable Push Notifications, configure signing and a matching APNs provider, and retain standard `FlutterAppDelegate` forwarding. Exclude `x86_64` simulator architecture after including `Generated.xcconfig`: `EXCLUDED_ARCHS[sdk=iphonesimulator*] = $(inherited) x86_64`.

See the [example](../example/flutter-example/README.md) for complete platform configuration. CocoaPods and web/desktop are unsupported.

## Initialize and use

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

Initialization waits for local readiness, not server registration. Automatic integration handles native startup and push callbacks without host code edits. Use the same configuration each startup. See [shared behavior](../README.md#shared-behavior).

`getState()`, `getTags()`, and `getEmail()` read local values; `refresh()` fetches server state. Tags accept strings, booleans, and finite numbers; event properties accept JSON. Failures throw `SdkException`. Cancel stream subscriptions when finished.

## Permissions and opens

```dart
await sdk.requestPushPermission(settingsFallback: true);
final status = await sdk.getPushPermissionStatus();
```

Observe `pendingOpens`, serialize navigation in your app, and call `acknowledgeOpen(open.interactionId)` after handling each open. Opens replay until acknowledged. `notifications` and `diagnostics` are live streams without replay; `states` and `pendingOpens` replay current values.

## Manual integration

For custom callback ownership, set `automaticIntegration: false` from the first initialization. Initialize the native SDK at host startup with matching settings and forward [Android](../android/README.md#notifications-and-permissions) or [iOS](../ios/README.md#permissions-and-apns) callbacks. Manual mode does not restore startup configuration.

For custom WorkManager setup, remove `NotificationSdkInitializer` metadata from the AndroidX Startup provider and initialize WorkManager before the SDK. Remove the SDK Firebase service if your host owns FCM. Manual mode does not establish compatibility with other push plugins.

## Verify

From this directory:

```sh
flutter pub get
flutter analyze
flutter test
```

Run the example's [device checks](../example/flutter-example/README.md#device-checks) for real push delivery and restart behavior.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

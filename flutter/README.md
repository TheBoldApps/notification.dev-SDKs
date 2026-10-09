# Flutter SDK

Android/iOS plugin over the native SDKs. Requires Flutter 3.44+, Dart 3.12+, JDK 17, Android SDK 36, and Swift Package Manager for iOS. Supports Android API 23+, iOS 15+, and Apple Silicon simulators.

## Install

```yaml
dependencies:
  notification_dev: ^0.2.0
```

Run `flutter pub get`. Android downloads the native SDK from Maven Central; iOS downloads the released Swift package from GitHub. No native SDK checkout or local Gradle composite build is required.

Enable Swift Package Manager for iOS with `flutter config --enable-swift-package-manager`.

## Develop with a local Flutter checkout

```yaml
dependencies:
  notification_dev:
    path: /path/to/notification.dev-SDKs/flutter
```

This uses local Flutter bridge code with the released native SDKs. To develop native Android code too, add the [Android composite build](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/android/README.md#install-locally) to your app's `android/settings.gradle.kts`.

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

## Permissions and opens

```dart
await sdk.requestPushPermission(settingsFallback: true);
final status = await sdk.getPushPermissionStatus();
```

Observe `pendingOpens`, serialize navigation in your app, and call `acknowledgeOpen(open.interactionId)` after handling each open. Opens replay until acknowledged. `notifications` and `diagnostics` are live streams without replay; `states` and `pendingOpens` replay current values.

## Manual integration

For custom callback ownership, set `automaticIntegration: false` from the first initialization. Initialize the native SDK at host startup with matching settings and forward [Android](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/android/README.md#notifications-and-permissions) or [iOS](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/ios/README.md#permissions-and-apns) callbacks. Manual mode does not restore startup configuration.

For custom WorkManager setup, remove `NotificationSdkInitializer` metadata from the AndroidX Startup provider and initialize WorkManager before the SDK. Remove the SDK Firebase service if your host owns FCM. Manual mode does not establish compatibility with other push plugins.

## Verify

From this directory:

```sh
flutter pub get
flutter analyze
flutter test
```

Run the example's [device checks](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/example/flutter-example/README.md#device-checks) for real push delivery and restart behavior.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions. The 0.2.0 flag replaces the
previous API without compatibility aliases or startup configuration migration.

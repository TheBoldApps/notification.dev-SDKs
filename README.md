# notification.dev SDKs

Android, iOS, and Flutter clients for notification.dev, powered by a shared Kotlin Multiplatform core. Supports push notifications, user identity, tags, email preferences, events, and offline synchronization.

| Platform | Requirements | Guide | Example |
| --- | --- | --- | --- |
| Android | API 23+, JDK 17, Android SDK 36 | [Setup](android/README.md) | [Android app](example/android-example/README.md) |
| iOS | iOS 15+, macOS, Xcode, Apple Silicon simulator | [Setup](ios/README.md) | [SwiftUI app](example/ios-example/README.md) |
| Flutter | Flutter 3.44+, Dart 3.12+, native build tools | [Setup](flutter/README.md) | [Flutter app](example/flutter-example/README.md) |

The iOS SDK has a remote Swift Package Manager manifest; see its [installation guide](ios/README.md). Android has Maven Central publishing configured; see the [release procedure](RELEASING.md) for local verification and upload steps. Flutter distribution currently uses local builds. The committed [OpenAPI snapshot](contract/openapi.json) makes SDK builds independent of the backend checkout.

## Shared behavior

- Initialize once per process with your public notification.dev project UUID. The default API is `https://app.notification.dev/`.
- Setters update local state and schedule sync; they do not confirm server acceptance. Observe state and diagnostics for pending changes and errors. `refresh()` fetches server state while retaining pending edits.
- Tags and email belong to users; push preference belongs to the installation. Email preference, push preference, and OS permission are independent.
- External IDs are unverified: anyone who knows an ID can claim it. Use opaque, random IDs. Resolve an ambiguous login by retrying the same ID. Association changes discard pending work; logout creates a new anonymous association without unsubscribing the old user's email.
- Events persist offline and retry for up to seven days. App opens are tracked automatically; `notification_dev.app_opened` and `notification_dev.subscriber_created` are reserved.
- Notification opens persist until `acknowledgeOpen` succeeds. Navigate using your app's routing rules; the SDK does not launch deep links automatically.
- State is encrypted locally. Storage write failures can leave unsaved changes; clear development app data when changing projects or API hosts. Legacy SDK storage is not migrated.

## Development

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or provide `sdk.dir` in root `local.properties`. Run from the repository root:

```sh
./gradlew :core:testAndroidHostTest :android:testDebugUnitTest :android:lintDebug
```

On macOS with Xcode and an installed ARM64 simulator:

```sh
./gradlew :core:iosSimulatorArm64Test :core:prepareSwiftPackage
```

See [architecture](ARCHITECTURE.md), [contract maintenance](contract/README.md), and [examples](example/README.md). Real FCM/APNs delivery and notification taps after process termination need device testing.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for checks and pull requests, and [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

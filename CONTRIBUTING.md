# Contributing

Open an issue with reproduction steps, platform/tool versions, and sanitized logs. For security vulnerabilities, follow [SECURITY.md](SECURITY.md).

Keep pull requests focused and include a short description and verification results. Follow the existing code style and add regression tests for behavior changes.

Run the relevant checks below. Real push-delivery checks need a disposable project and configured providers.

Update documentation when behavior changes. Refresh the [OpenAPI snapshot](contract/README.md#api-generation) through the backend exporter; do not edit generated contracts or Kotlin sources manually.

Keep credentials, local configuration, and build outputs out of commits.

## MCP integration guides

Maintain `docs/integration/{android,ios,flutter,expo}.md` alongside SDK changes.
Use absolute links so the guides work when read through MCP. Keep release setup
and API examples consistent with the platform READMEs and runnable examples.

The product backend bundles these guides. From its `apps/app/backend` directory:

```sh
pnpm import:sdk-guides /path/to/notification.dev-SDKs
pnpm import:sdk-guides /path/to/notification.dev-SDKs --check
pnpm test:sdk-guides
```

Commit guide updates in this repository and the refreshed bundle in the product
repository together. Normal product builds need only the committed bundle.

## SDK development

These instructions are for contributors building SDK sources. App developers should
use released packages from the [platform setup guides](README.md).

### Shared core and Android — Tests

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or provide `sdk.dir` in root `local.properties`. Run from the repository root:

```sh
./gradlew :core:testAndroidHostTest :android:testDebugUnitTest :android:lintDebug
```

On macOS with Xcode and an installed ARM64 simulator:

```sh
./gradlew :core:iosSimulatorArm64Test :core:prepareSwiftPackage
```

See [architecture](ARCHITECTURE.md), [contract maintenance](contract/README.md), and [examples](example/README.md). Real FCM/APNs delivery and notification taps after process termination need device testing.

### Android — Local installation

Add the SDK repository to your app's `settings.gradle.kts`:

```kotlin
includeBuild("/path/to/notification.dev-SDKs") {
    dependencySubstitution {
        substitute(module("dev.notification:android-sdk")).using(project(":android"))
    }
}
```

Add `implementation("dev.notification:android-sdk:0.2.0")` to your app dependencies. Configure Firebase with the Google Services plugin and your `google-services.json`. Firebase Messaging is included by the SDK.

### iOS — Local installation

Building the SDK locally requires macOS, Xcode, JDK 17, and Android SDK 36.
From the repository root:

```sh
./gradlew :core:prepareSwiftPackage
```

Add `ios/` as a local Swift package in Xcode and link `NotificationDev`. Rebuild after core or contract changes; the XCFramework is ignored by Git.

The root package manifest is for published releases; `ios/Package.swift` is for
local development. See [the iOS release procedure](RELEASING.md).

### iOS — Tests

Prepare the framework, then run from `ios/`:

```sh
xcodebuild -scheme NotificationDev -destination 'platform=iOS Simulator,id=SIMULATOR_UUID' -derivedDataPath /tmp/notification-ios-tests test CODE_SIGNING_ALLOWED=NO ARCHS=arm64
```

Use the example's [device checks](example/ios-example/README.md#device-checks) for real APNs delivery.

### Flutter — Develop with a local Flutter checkout

```yaml
dependencies:
  notification_dev:
    path: /path/to/notification.dev-SDKs/flutter
```

This uses local Flutter bridge code with the released native SDKs. To develop native Android code too, add the [Android composite build](#android--local-installation) to your app's `android/settings.gradle.kts`.

### Flutter — Tests

From `flutter/`:

```sh
flutter pub get
flutter analyze
flutter test
```

Run the example's [device checks](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/example/flutter-example/README.md#device-checks) for real push delivery and restart behavior.

### Expo — Tests

From `expo/`:

```sh
npm ci
npm test
ruby test/spm_test.rb
npm pack --dry-run
```

The Ruby test requires the `xcodeproj` gem, included with CocoaPods. See the
[example](https://github.com/TheBoldApps/notification.dev-SDKs/tree/main/example/expo-example) for native builds and device checks.
Native version overrides and local SDK substitutions are not provided.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions.

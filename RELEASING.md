# iOS releases

Publish the Swift sources from this repository and the matching Kotlin core as a
GitHub Release asset. The root `Package.swift` is the public package; the manifest
under `ios/` continues to use a locally built framework.

## Prepare and verify

Use a clean checkout with macOS, Xcode, JDK 17, and Android SDK 36. From the
repository root:

```sh
./gradlew :core:iosSimulatorArm64Test :core:prepareSwiftPackage
```

Run the Swift tests using the command in [the iOS guide](ios/README.md#verify),
then build the [iOS example](example/ios-example/README.md#build) for an ARM64
simulator and an iPhone destination. Verify the XCFramework contains both
`ios-arm64` and `ios-arm64-simulator` slices.

Archive the framework and calculate the checksum of the exact ZIP to upload:

```sh
ditto -c -k --sequesterRsrc --keepParent \
  ios/Artifacts/NotificationCore.xcframework \
  /tmp/NotificationCore.xcframework.zip
swift package compute-checksum /tmp/NotificationCore.xcframework.zip
```

Set the root manifest's binary URL to
`https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/VERSION/NotificationCore.xcframework.zip`
and replace its checksum. Update the installation examples to the release
version. Validate the manifest with `swift package dump-package` from the root.

Commit the manifest and documentation before creating the semantic version tag
(for example, `0.1.0`). Build the binary from the same source revision as the
tagged Swift sources. Do not include the framework binary in Git.

## Publish and smoke test

Create a GitHub Release for that tag, attach the exact verified
`NotificationCore.xcframework.zip`, and publish it with release notes. Both the
repository and asset must be publicly accessible for public SDK distribution.

Download the published archive and verify its checksum matches `Package.swift`.
In a clean consumer project, install the tagged package by repository URL, import
`NotificationDev`, and build for an ARM64 simulator and an iPhone destination.
This check must not use the local `ios/` package or a pre-existing package cache.

Never replace a published archive or move an existing release tag. Publish a new
version for corrections. Android/Maven and Flutter/pub.dev publishing are separate
release steps; neither is needed to install the Swift package.

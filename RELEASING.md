# SDK releases

## Android / Maven Central

The Android SDK is configured to publish as `dev.notification:android-sdk:0.2.0`. Publish
the shared core in the same release: `sdk-core`, `sdk-core-android`,
`sdk-core-iosarm64`, and `sdk-core-iossimulatorarm64`, all under `dev.notification`.
Gradle metadata selects the appropriate core platform for consumers.

The Vanniktech Maven publishing plugin builds the artifacts, sources, documentation
archives, POM metadata, and signatures. Public metadata lives in `gradle.properties`
and the root `build.gradle.kts`. Set `POM_DEVELOPER_EMAIL` to the public maintainer
contact email; the generated POM includes that email plus the developer organization.
Keep Central user-token credentials and GPG signing credentials in your user-level
`~/.gradle/gradle.properties`, outside Git. The signing public key must be available
on a Central-supported keyserver. See the
[publishing plugin guide](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

From the SDK repository root, run these steps sequentially:

```sh
./gradlew verifyRelease
./gradlew :core:publishToMavenLocal :android:publishToMavenLocal
```

`verifyRelease` runs Android debug unit tests, release lint, shared-core Android
and iOS simulator tests, and the Kotlin plugin's POM metadata validation.
This project does not define `:android:testReleaseUnitTest`.
The iOS tests require macOS, Xcode, and an available Apple Silicon simulator.
Local publishing writes signed packages to `~/.m2/repository/dev/notification/`;
it does not upload anything to Central. Inspect the generated POMs and Gradle
metadata under each module's `build/publications/` and test a consumer with
`mavenLocal()` and no composite-build dependency substitution.

After checks pass and the release is ready for upload:

```sh
./gradlew publishToMavenCentral
```

In [Central Portal deployments](https://central.sonatype.com/publishing/deployments),
wait for validation, review the artifacts, and click **Publish**. Automatic release
is not enabled by the committed configuration. Test a clean consumer with only
`google()` and `mavenCentral()` and `dev.notification:android-sdk:0.2.0` after
the artifacts become available. Use a new version for changes to a published release.

## iOS releases

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
mkdir -p /tmp/notification-release-0.2.0
ditto -c -k --sequesterRsrc --keepParent \
  ios/Artifacts/NotificationCore.xcframework \
  /tmp/notification-release-0.2.0/NotificationCore.xcframework.zip
swift package compute-checksum /tmp/notification-release-0.2.0/NotificationCore.xcframework.zip
```

Set the root manifest's binary URL to
`https://github.com/TheBoldApps/notification.dev-SDKs/releases/download/VERSION/NotificationCore.xcframework.zip`
and replace its checksum. Update the installation examples to the release
version. Validate the manifest with `swift package dump-package` from the root.

Commit the manifest and documentation before creating the semantic version tag
(for example, `0.2.0`). Build the binary from the same source revision as the
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


## Flutter / pub.dev

The package is `notification_dev` in `flutter/`. Its Android dependency is
`dev.notification:android-sdk:0.2.0`; its Swift dependency is GitHub tag `0.2.0`.
Publish both native releases first and confirm that all transitive Android core
artifacts are available from Maven Central. A staged Central deployment or a
local Maven publication is not sufficient.

From `flutter/`:

```sh
flutter pub get
flutter analyze
flutter test
flutter pub publish --dry-run
```

Review the archive list and resolve warnings. The archive must include `lib/`,
Android and iOS plugin sources, the Swift manifest, LICENSE, NOTICE, README,
CHANGELOG, and the usage example. It must not include build caches, credentials,
or machine-specific configuration. Keep README links usable outside the repo.

Before upload, build a separate app against a copy of the publishable package.
Do not add `mavenLocal()` or a Gradle composite dependency substitution. Enable
Swift Package Manager and verify that iOS resolves the remote SDK tag and its
XCFramework. Build Android and iOS, then check initialization, permissions,
FCM/APNs delivery, restart behavior, and notification opens on configured devices.
The repository example substitutes local Android source and does not prove
Maven Central installation works.

After the checks pass, run from `flutter/`:

```sh
flutter pub publish
```

Authenticate with the Google account that will own the package. A verified
pub.dev publisher is optional and has separate domain verification from Maven
Central. For a new package, publish using a Google account, then transfer it to
the organization's publisher on pub.dev if desired.

Finally install `notification_dev: ^0.2.0` from pub.dev in a separate app and build
both platforms again. For future releases, update the Flutter package version,
CHANGELOG, and native dependency versions together as appropriate.


## Coordinated 0.2.0 release

This release replaces the old local-HTTP flag with `allowHttp` (Swift:
`allowHTTP`). HTTP is permitted for any host only with explicit opt-in; HTTPS
remains the default. No API aliases or saved startup configuration migration
are provided. Host apps must separately permit HTTP through Android cleartext
policy or iOS App Transport Security.

Changing source does not change an installed package. Prepare and verify all
components locally, then publish the native dependencies before Flutter:

1. Run `./gradlew verifyRelease :core:prepareSwiftPackage` and native Swift tests.
   Check Flutter analysis, tests, and both platform builds with temporary local
   dependency overrides before the new remote versions exist. Keep the committed
   Flutter dependencies pointed at release 0.2.0.
2. Publish Android and every shared-core artifact with `./gradlew publishToMavenCentral`.
   Validate and publish in Central Portal; confirm download availability.
3. ZIP the newly built XCFramework and compute the checksum as above. Set the
   root Swift manifest's 0.2.0 URL and checksum. Commit the tested sources and
   manifest, tag that revision 0.2.0, and push the commit and tag. Create the release
   and upload the exact archive using GitHub CLI (no browser upload required):

   ```sh
   gh auth login
   gh release create 0.2.0 /tmp/notification-release-0.2.0/NotificationCore.xcframework.zip \
     --repo TheBoldApps/notification.dev-SDKs \
     --verify-tag --title "SDK 0.2.0" --generate-notes
   ```

4. Test isolated consumers against the downloadable Android artifacts and GitHub
   Swift release. From `flutter/`, run `flutter pub publish --dry-run`, then
   `flutter pub publish`.
5. Update production examples to `notification_dev: ^0.2.0`, resolve it from
   pub.dev, and rebuild both platforms. Verify physical-device LAN access with
   `allowHttp: true`, without ADB forwarding.

Do not replace the 0.1.0 artifacts, release tag, or archive. A persisted 0.1.0
development configuration is unsupported after this breaking update. If needed,
reset a test app's data deliberately; this deletes its local identity and pending
operations. The SDK does not automatically erase that data.

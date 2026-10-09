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

`prepareSwiftPackage` also creates `build/NotificationCore.xcframework.zip`,
computes its SHA-256 checksum, and updates the root `Package.swift` with the
release URL (using the Gradle project version) and checksum. It prints the exact
ZIP path and next steps. No separate `ditto` or checksum command is required.

The task retains `ios/Artifacts/NotificationCore.xcframework` for local Swift
development. An unchanged framework reuses the existing ZIP so its bytes and
checksum remain stable. If you change the binary or version, prepare again before
committing. Update the installation examples to the release version.

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

### Repository-scoped release script

`scripts/release_github.py` publishes the exact prepared archive using Python 3
and the GitHub REST API. GitHub CLI and an account-wide OAuth login are not needed.
The script does not build or test the SDK; complete the verification above first.

Create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new)
with resource owner **TheBoldApps**, **Only select repositories** →
**notification.dev-SDKs**, and repository permission **Contents: Read and write**.
Leave other optional permissions unset and choose a short expiration. If the
organization requires approval, the token must be approved before publishing.
GitHub uses Contents write for both releases and repository content; it does not
offer a release-only token permission. Never commit the token or paste it in chat.

Commit and push the tested release sources, checksum, and script. Then run from
the SDK root (the token prompt below uses the default macOS zsh shell):

```sh
python3 scripts/release_github.py
read -s 'GH_TOKEN?Repository-scoped GitHub token: '
export GH_TOKEN
python3 scripts/release_github.py --publish
unset GH_TOKEN
```

By default the script only checks the local manifest/archive checksum and prints
the version and HEAD revision; it makes no network calls. Use `--archive PATH`
to supply a ZIP other than the default `build/NotificationCore.xcframework.zip`.

Publishing requires a clean working tree and a HEAD commit already on GitHub.
It creates the version tag at that exact revision if absent, refuses an existing
tag at another revision, creates a draft, uploads the archive, validates GitHub's
asset digest, then publishes and verifies the public archive checksum. It never
replaces a published release or asset. An interrupted matching draft can be
resumed by running the same command; an incomplete or mismatched asset requires
manual inspection. If publication succeeds but public download verification
fails, inspect the published release before retrying.

For future CI use, the same script accepts a GitHub Actions `GITHUB_TOKEN` in
`GH_TOKEN`, with job permission `contents: write`; that token is scoped to the
workflow repository and expires automatically. No personal token is needed for
that setup. The workflow must supply the exact ZIP matching the committed
checksum, rather than assume a fresh build produces byte-identical ZIP files.


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
3. `:core:prepareSwiftPackage` has already created the ZIP in `build/` and
   updated the root Swift manifest URL and checksum. Commit the tested sources
   and manifest, and push the commit. Create the tag and release and upload the exact
   archive using the repository-scoped script described above:

   ```sh
   python3 scripts/release_github.py
   python3 scripts/release_github.py --publish
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

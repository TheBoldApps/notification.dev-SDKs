# Expo 0.3.1 upgrade in hand-maintained native projects

This is a candidate upgrade checklist. Complete Maven/npm publication and
remote-only verification in [the release checklist](0.3.1.md) before presenting
it as a production-ready release. It is a reference diff, not a review of a
customer's native source. A fresh native build is required.

## Package versions

The isolated consumer compiled with Expo 58.0.0, React Native 0.88.0-rc.3 and
React 19.3.0. Preserve those versions for this tested matrix:

```diff
 "dependencies": {
-  "@notification.dev/expo": "0.2.0",
+  "@notification.dev/expo": "0.3.1",
   "expo": "58.0.0",
   "react-native": "0.88.0-rc.3",
   "react": "19.3.0"
 }
```

Use the final npm package and commit the resulting lockfile. The wrapper pins
native Swift and Android SDKs to 0.3.1. Verify resolved native dependencies;
changing JavaScript alone does not update an installed native binary.

## Preserve native edits

Do not run `expo prebuild --clean` over hand-maintained projects. Generate a
separate disposable reference project if needed, compare its SDK-specific
configuration, and apply reviewed edits to the real projects. The compiled
reference is not evidence that a different native project has the same wiring.

Keep project UUID, API origin, existing startup flags, identity/storage, channel
IDs, notification icon, bundle/application IDs and signing setup consistent.
Do not uninstall/reset the app to perform the upgrade; preserve the current
diagnostic journal and verify the update with the existing installation.

## iOS

- Preserve Expo autolinking and its app-delegate subscriber lifecycle. The updated
  bridge supplies the synchronous startup fix; do not register a second delegate
  or add duplicate response forwarding as part of the upgrade. Review existing
  custom forwarding if the host already owns notification callbacks.
- Keep the SDK's existing Podfile `post_install` hook. It loads
  `@notification.dev/expo/plugin/with_spm.rb` and calls
  `NotificationDevExpo.install_swift_package(installer)`. Run `pod install` after
  the package update, then inspect the app and Pods package references.
- Resolve `https://github.com/TheBoldApps/notification.dev-SDKs.git` at exact
  0.3.1. Align any independently pinned host/widget references. Avoid adding a
  second copy of the static SDK product; the bridge already links it.
- Preserve the existing `NotificationDev` Info.plist dictionary, including
  `projectId`, `baseUrl`, `allowHttp`, `loggingEnabled` and
  `displayInForeground`. For a production-APNs signed build, the new explicit
  routing field can be added as follows:

```diff
 <key>NotificationDev</key>
 <dict>
   <!-- Keep the existing project, origin and flags. -->
+  <key>iosApnsEnvironment</key>
+  <string>production</string>
 </dict>
```

The equivalent Expo config-plugin option is `iosApnsEnvironment: "production"`.
For a hand-maintained project, changing app config alone does not apply the
Info.plist edit. Verify the signed production build has the correct bundle ID,
Push Notifications capability and `aps-environment=production` entitlement with
the matching provider. Development/sandbox builds must use their matching
routing and entitlements; the metadata option does not change signing or turn a
sandbox token into a production token.

## Android

- Preserve Expo module autolinking, SDK initialization metadata and merged
  Firebase service/AndroidX Startup components from the updated dependency.
  Review any host-owned Firebase service or manifest overrides for conflicts.
- Keep the existing `dev.notification.expo.CONFIG` JSON metadata, channel/icon
  resources and notification permission setup. Native SDK versions come from
  the updated wrapper's Gradle dependency; do not add a second manual SDK copy.
- Align any explicit native SDK dependency, constraint, version catalog entry
  or `resolutionStrategy.force` to 0.3.1. Verify `android-sdk`, `sdk-core` and
  selected `sdk-core-android` resolve to 0.3.1.
- The SDK requires `android.kotlinVersion=2.3.20`. Preserve an existing correct
  property. Expo 58's tested buildscript reads this property; keep that reader
  instead of inserting a duplicate Kotlin classpath:

```groovy
def kotlinVersion = findProperty('android.kotlinVersion')
classpath(kotlinVersion ? "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion"
                       : 'org.jetbrains.kotlin:kotlin-gradle-plugin')
```

The candidate plugin also supports the older template's explicit classpath
form. Check custom build overrides against the actual host before editing them.
Final verification must use public remote dependencies without `mavenLocal()`,
composite substitutions or local Swift package overrides.

## Project-specific review and acceptance

For an exact customer diff, inspect their relevant Podfile hook, SPM references,
SDK Info.plist dictionary, notification callback ownership, Gradle SDK/Kotlin
pins and SDK manifest metadata. Request only these sanitized blocks; no
credentials, signing keys, tokens or unrelated configuration are needed.

Record the installed version/build, JS package and resolved native versions.
Then run fresh-delivery tests on physical iOS/Android devices in foreground,
background and terminated states. Each case must correlate its delivery ID
through callback evidence, individual `opened` acceptance (including results
inside HTTP 200), persistence and the correct campaign counters. Retry/restart
must not inflate unique tapped-delivery counts. Device builds and simulator
compilation alone do not satisfy that acceptance criterion.

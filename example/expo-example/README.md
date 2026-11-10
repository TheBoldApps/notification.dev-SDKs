# Expo example

Expo SDK 58 app using the bridge under `../../expo` and the **released** native
SDKs. No Gradle composite build or local Swift package is used.

```sh
# Build the local TypeScript bridge first:
cd ../../expo
npm ci
npm run build
cd ../example/expo-example
npm install
export NOTIFICATION_DEV_PROJECT_ID='YOUR_PUBLIC_PROJECT_UUID'
export GOOGLE_SERVICES_FILE='/absolute/path/to/google-services.json'
npx expo run:android
# On macOS:
npx expo run:ios
```

Firebase's package must match `dev.notification.expoexample`. Configure APNs
signing and the server provider for the iOS bundle ID of the same name. The
all-zero default UUID is a build placeholder; set a real project to register.
Android can compile without Google Services, but push registration requires it.

The example's npm configuration copies the local wrapper as a normal package
instead of linking its development `node_modules`, avoiding duplicate Expo/React
instances. After editing the wrapper, rebuild and reinstall it, or use a fresh
tarball for consumer verification.

Use `npm start` to connect the development build to Metro. A native configuration
change requires a new build; clean prebuild regenerates native configuration.

The UI exposes identity, email, tags, events, push preference, permissions,
refresh, diagnostics, and pending opens. Acknowledgement is explicit so replay
can be inspected.

## Build verification

Run `npx expo prebuild --no-install`, then `pod install` from `ios/`. Build the
generated workspace for an ARM64 simulator and iPhone destination. Inspect the
app and Pods project package references: both must reference the remote
`NotificationDev` package at `0.2.0`. Android dependency inspection must resolve
`dev.notification:android-sdk:0.2.0` from Maven Central.

For publication checks, run `npm pack` in `../../expo`, install the tarball in a
separate clean Expo app, and repeat native builds. The tarball must contain only
the Expo bridge, config plugin, TypeScript API, and docs.

## Device checks

- Grant/deny permission and inspect settings behavior.
- Send data-only FCM and APNs payloads for the active installation/association.
- Check foreground/background display and foreground suppression.
- Tap while running, backgrounded, and after process termination. Confirm opens
  replay across JS reloads/restarts until acknowledged.
- Set tags and track events offline, then reconnect and inspect synchronized state.
- Log in/out; check stale-association notification suppression.
- Check token registration on fresh installs and later app starts.
- Reload JavaScript repeatedly and check observers do not duplicate callbacks.

Real delivery requires configured providers and devices. Simulator builds alone
do not prove delivery.

## Optional provider coexistence

The example pins `react-native-onesignal` 5.5.17 and `onesignal-expo-plugin` 2.8.0.
Set `EXPO_PUBLIC_ONESIGNAL_APP_ID` before prebuild and runtime to enable it. Set
`NOTIFICATION_DEV_APNS_ENVIRONMENT` to `sandbox` or `production` to match signing.
The provider-switch buttons are illustrative: the application owns authenticated
account sequencing and its remote rollout flag. The switch verifies
`pushTokenRegistered`, pending changes and identity before opting out of OneSignal.
See [advanced integration](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/advanced-push.md).

When testing these unreleased source changes, use local native SDK overrides as
well as the local npm bridge; a released 0.2.0 native binary lacks the new APIs.

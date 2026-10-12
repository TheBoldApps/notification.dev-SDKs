# Expo example

Expo SDK 57 app using the bridge under `../../expo` and the **released** native
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

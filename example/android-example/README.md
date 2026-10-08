# Android example

## Setup

1. Open this directory in Android Studio. Use JDK 17 and Android SDK 36.
2. Register Firebase Android package `dev.notification.example`; save its client configuration as `app/google-services.json` (ignored by Git).
3. Configure an FCM provider in your notification.dev project using the same Firebase project. Keep service-account credentials on the server.
4. Replace `YOUR_PUBLIC_PROJECT_UUID` in `ExampleApplication.kt` with your notification.dev project UUID.
5. Run the **app** configuration on an API 23+ device or emulator with Google Play services.

The composite build uses the local SDK; Maven publication is unnecessary. For local development, override `SdkConfig.baseUrl` (for example, `http://10.0.2.2:3000/` on an emulator). Cleartext is debug-only; release requires HTTPS. Clear app data when switching projects or hosts.

## Build

From this directory, after Firebase setup:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Release builds are unsigned and minified.

## Device checks

Use a disposable project with one active matching FCM provider:

- Request permission, enable push, and verify the subscriber has a registered token.
- Send a campaign while foregrounded and backgrounded; tap the alert and check open handling.
- Edit tags/email offline, restart, and reconnect; verify synchronization.
- Decline permission, retry with settings fallback, and return after enabling it.

Run `./gradlew :app:connectedDebugAndroidTest` on a fresh test emulator for instrumentation checks. Tests change subscriber data and permission settings. Synthetic callback tests do not establish real FCM delivery; Firebase console notification messages bypass the SDK's data-only path.

See [Android integration](../../android/README.md).

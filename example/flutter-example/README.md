# Flutter example

## Setup

1. Use Flutter 3.44+ with Swift Package Manager enabled; run `flutter pub get` here.
2. Replace `YOUR_PUBLIC_PROJECT_UUID` in `lib/main.dart` with your notification.dev project UUID.
3. Android: save Firebase configuration for `dev.notification.example` as `android/app/google-services.json`; configure a matching FCM provider in notification.dev.
4. iOS: the Flutter plugin downloads native SDK release `0.2.0` through Swift Package Manager. Configure signing, Push Notifications, and an APNs provider matching the bundle ID and environment. The included entitlement uses sandbox APNs.
5. Run `flutter run`.

The Flutter bridge and Android SDK use local source; iOS uses the released Swift SDK. Native startup files need no edits for automatic integration. See [Flutter integration](../../flutter/README.md).

For a local backend, set `baseUrl` and `allowHttp: true` in `SdkConfig`: use `http://10.0.2.2:3000/` on Android emulators or `http://localhost:3000/` on iOS simulators. Clear app data when changing projects or hosts.

## Device checks

Use a disposable project:

- Edit tags/email offline, restart, and reconnect; verify sync.
- Decline permission, retry with settings fallback, enable permission, and return.
- Send real FCM/APNs notifications while foregrounded and backgrounded.
- Tap after process termination; verify replay until closing the detail screen acknowledges the open.
- On Android, deliver after process termination without force-stopping; verify native startup before Flutter runs.
- Opt out or log out; verify SDK-owned notifications clear.

Real APNs delivery requires signing and a device. Mock-channel tests do not establish push delivery.

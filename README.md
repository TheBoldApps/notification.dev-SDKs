# notification.dev SDKs

Android, iOS, Flutter, and Expo clients for notification.dev. Supports push notifications, user identity, tags, email preferences, events, and offline synchronization.

| Platform | Requirements                                               | Guide                      | Example                                          |
| -------- | ---------------------------------------------------------- | -------------------------- | ------------------------------------------------ |
| Android  | API 23+, JDK 17, Android SDK 36                            | [Setup](android/README.md) | [Android app](example/android-example/README.md) |
| iOS      | iOS 15+, macOS, Xcode, Apple Silicon simulator             | [Setup](ios/README.md)     | [SwiftUI app](example/ios-example/README.md)     |
| Flutter  | Flutter 3.44+, Dart 3.12+, native build tools              | [Setup](flutter/README.md) | [Flutter app](example/flutter-example/README.md) |
| Expo     | Expo SDK 57, Android API 24+, iOS 16.4+, development build | [Setup](expo/README.md)    | [Expo app](example/expo-example/README.md)       |

Install the released SDK for your platform using the setup guides above.

## Integrate with an AI agent

Connect an MCP client using Streamable HTTP to
`https://app.notification.dev/mcp` and complete the OAuth sign-in and consent flow.
Ask: “Use `get_sdk_integration_guide` with `platform: "flutter"` and integrate
notification.dev into this app.” The tool supports `android`, `ios`, `flutter`,
and `expo`; retrieving a guide requires no additional connection permissions.

The [integration guides](https://github.com/TheBoldApps/notification.dev-SDKs/tree/main/docs/integration/) cover configuration, usage, troubleshooting,
and device verification. This README remains usable without MCP. Agents use OAuth;
mobile apps initialize with the public project UUID, not the agent token or a
server API key.

## Shared behavior

- Initialize once per process with your public notification.dev project UUID. The default API is `https://app.notification.dev/`.
- Setters update local state and schedule sync; they do not confirm server acceptance. Observe state and diagnostics for pending changes and errors. `refresh()` fetches server state while retaining pending edits.
- Tags and email belong to users; push preference belongs to the installation. Email preference, push preference, and OS permission are independent.
- External IDs are unverified: anyone who knows an ID can claim it. Use opaque, random IDs. Resolve an ambiguous login by retrying the same ID. Association changes discard pending work; logout creates a new anonymous association without unsubscribing the old user's email.
- Events persist offline and retry for up to seven days. App opens are tracked automatically; `notification_dev.app_opened` and `notification_dev.subscriber_created` are reserved.
- Handle notification taps through `pendingOpens`; opens persist until `acknowledgeOpen` succeeds. Navigate using your app's routing rules; the SDK does not launch deep links automatically.
- State is encrypted locally. Storage write failures can leave unsaved changes; clear development app data when changing projects or API hosts. Legacy SDK storage is not migrated.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for checks and pull requests, and [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

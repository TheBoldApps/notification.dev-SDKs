# Android image thumbnail release gate

The presenter change, released in native SDK 0.4.1, uses the same `imageUrl` bitmap as a compact
large-icon thumbnail and an expanded picture. Use it for a person's photo or a
small seasonal asset. The expanded style hides the compact large icon so the
image is not duplicated above the large picture, following the
[Android notification guide](https://developer.android.com/develop/ui/compose/notifications/expanded).
It does not replace the configured monochrome app/status
icon, and does not enable Android MessagingStyle or iOS Communication
Notifications sender avatars.

Released in native SDK 0.4.1 with aligned Flutter/Expo 0.4.1 wrappers. Never
replace a published tag or binary; release corrections under a new version.

Before announcing customer readiness:

1. Build/lint and run the native Android tests for the exact release commit.
2. Publish the new Maven coordinates and aligned wrappers, then verify public
   resolution and native app builds. State exact wrapper and resolved native
   versions in the handoff.
3. Refresh `docs/integration/android.md` and the product's bundled SDK guide when
   publication makes the version and upgrade instructions concrete.
4. Rebuild a physical-device app. Verify a square photo/offer asset as a compact
   thumbnail and expanded image, plus readable text when the image fails.
5. Tap the same fresh delivery and trace its signed payload/delivery ID through
   per-operation API acceptance, persisted interaction and campaign attribution.

An iOS right-side thumbnail already uses `UNNotificationAttachment` through the
embedded Notification Service Extension. No iOS source change is required for
that behavior. True sender-avatar notifications are a distinct communication
integration and must not be represented by the ordinary attachment preview.

# Android image thumbnail release gate

The unreleased presenter fix uses the same `imageUrl` bitmap as a compact
large-icon thumbnail and an expanded picture. Use it for a person's photo or a
small seasonal asset. It does not replace the configured monochrome app/status
icon, and does not enable Android MessagingStyle or iOS Communication
Notifications sender avatars.

Do not replace the published native 0.3.1 tag or publish different binaries under
that version. Release this change under a new native patch version, align the
Flutter/Expo Android pins, and verify clean consumers using only public remote
dependencies. Keep the existing coordinated 0.3.1 notification-open reliability
release independent if its publication is already underway.

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

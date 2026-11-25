# Communication avatars in Expo apps

A communication notification represents a genuine incoming message from a person.
Use ordinary image attachments for offers and campaigns. Avatar support is native
code that requires `@notification.dev/expo` 0.4.1 or later and a new native
build; it cannot be delivered through a dashboard change or OTA update.

The server message adds:

```json
{
  "title": "Anna",
  "body": "Your new message",
  "deepLink": "your-app://chat/conversation-opaque-id",
  "communication": {
    "sender": {
      "id": "sender-opaque-id",
      "name": "Anna",
      "avatarUrl": "https://example.com/anna.jpg"
    },
    "conversationId": "conversation-opaque-id"
  }
}
```

Send this from an authorized backend using your actual sender and conversation
identities. Keep project API keys out of the mobile app. Existing delivery IDs,
installation ownership checks and pending-open acknowledgement remain unchanged.
`avatarUrl` is optional; unavailable images retain readable message content.

For generated iOS projects, the updated Expo config plugin supports explicit
`iosCommunicationNotifications: true` together with an explicit
`iosCommunicationAppGroup: "group.your.app.notifications"`. It adds `INSendMessageIntent` to
`NSUserActivityTypes` without duplicating or replacing existing types and enables
`com.apple.developer.usernotifications.communication`. It preserves existing
production APNs entitlements. The shared App Group stores only the current association scope, not tokens or
credentials. Add the same App Group entitlement and
`NotificationDevCommunicationAppGroup` Info.plist value to the service extension.
This prevents delayed notifications from donating a previous account’s sender
after logout. If shared state is missing or mismatched, presentation falls back
to the original readable alert. This option does not create or embed the required
Notification Service Extension target.

For hand-maintained iOS projects, add those Info.plist/entitlement settings
manually, enable Communication Notifications on the app's signing identity,
refresh provisioning as required, and embed the SDK service extension using the
[iOS guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/ios.md).
Preserve your existing signing team, bundle IDs and production APNs settings. Do
not run `prebuild --clean` over a hand-maintained project.

Launch the rebuilt app once and wait for SDK identity synchronization before
testing; until the host publishes a stable association, extension presentation
fails safely to the ordinary alert.

Android uses native MessagingStyle and sender Person metadata. Full system
conversation presentation requires a valid chat deep link handled by this app;
the SDK registers an association-scoped long-lived shortcut for that destination.
Missing routes, OS quotas or unavailable shortcut registration use safe messaging
fallback. Shortcut launches carry no notification-delivery extras and do not
create false notification-open events. The host's own
small/status icon stays configured normally. Both platforms require a fresh
native build and physical-device verification in foreground, background and
terminated states. For each case trace the same fresh delivery ID through the
accepted opened operation, persisted interaction and correct dashboard counters.
Neither the editor preview nor compilation establishes that OS/device validation.

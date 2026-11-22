# Communication sender avatars (unreleased)

Use `communication` only for genuine incoming person-to-person messages. Ordinary
promotions and seasonal offers use `imageUrl` attachments. This feature is not
present in the immutable native 0.3.1 release or existing public wrappers.
Publish new native artifacts and compatible wrappers, verify clean remote-only
consumers, then rebuild customer applications before calling it available.

Payload:

```json
{"title":"New message","body":"Your message text","communication":{"sender":{"id":"opaque-person-id","name":"Anna","avatarUrl":"https://example.com/avatar.jpg"},"conversationId":"opaque-conversation-id"}}
```

On iOS, enable Communication Notifications in the main application's capabilities
and signing profile (`com.apple.developer.usernotifications.communication`). Add
`INSendMessageIntent` to `NSUserActivityTypes` in the main app's Info.plist. Embed
and sign a Notification Service Extension, linking only the
`NotificationDevServiceExtension` Swift package product; retain its documented
principal class and app-prefixed bundle ID. Enable capabilities/provisioning for
the extension as required by the final app signing configuration. Expo config
plugins cannot install or sign this native target by themselves.

The extension downloads optional avatars within five seconds, accepting only
JPEG/PNG/GIF up to 2 MiB and 2048 pixels per side without URL credentials or
redirects. It donates an incoming `INSendMessageIntent`, then updates notification
content. Failed download permits a sender-name/default-avatar presentation;
failed donation, malformed metadata or timeout retains the readable original
alert. Normal image attachments and the SDK routing/tracking envelope remain
intact. No competing notification delegate is installed.

Foreground handling uses the SDK's `NotificationDelegateForwarder`. If the NSE
already enriched the remote notification it uses that content directly. Otherwise
it records SDK receipt/eligibility once, enriches content and replaces the original
presentation with one local notification. Only private random local identifiers
may skip the second receipt path; payload markers alone never authorize this.
Identity is rechecked after enrichment and before presentation, and replay
registrations expire. Custom native delegates must retain the SDK forwarder or
provide equivalent enrichment; merely calling `presentationOptions(for:)` returns
options and cannot change an immutable `UNNotification` content object.

Siri person/conversation identifiers are SHA-256 scoped by installation
association, and donations have delivery-specific identifiers and an association
cleanup group. SDK login/logout clears its previous group; late cancelled
donations are deleted. Avatar images and messages are not emitted to diagnostics.
The host and NSE must share an App Group entitlement and set the identical
`NotificationDevCommunicationAppGroup` Info.plist value. The host publishes only
the current public association ID to an atomic file in that group; no credentials
or message content are shared. Scope is revoked before identity transitions and
republished from current SDK state. The NSE checks it before and after donation;
missing, unreadable or mismatched scope falls back to the ordinary alert. This
also requires verifying both targets' signed App Group provisioning on devices.

Before publication/integration:

1. Rebuild the shared Kotlin XCFramework: an older prebuilt core may drop the new
   communication metadata from persisted events/pending opens despite Swift builds.
2. Build/test both simulator and device destinations and verify signed host + NSE
   capabilities/provisioning. Simulator donation success does not establish OS UI.
3. Verify physical iOS and Android devices in foreground, background and terminated
   states, avatar missing/download failure, expiry and login/logout while pending.
4. Correlate the same fresh delivery ID from native callback through each opened
   API result (including rejections inside HTTP 200), persisted interaction and
   the correct campaign/Journey counters. Navigation alone is insufficient.

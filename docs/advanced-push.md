# Advanced push integration

These capabilities require the matching native SDK release underneath Flutter
and Expo. For local development, use the native source overrides described in
[CONTRIBUTING.md](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/CONTRIBUTING.md).

## Payloads and platform controls

Notification events and pending opens expose optional `collapseId`, `threadId`,
`ttlSeconds`, `priority`, `ios`, `android`, `imageUrl`, and `contentAvailable`.
Existing payloads remain supported. Custom JSON remains nested in
`notification.data`; it cannot override native presentation options. See the
[advanced fixture](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/contract/notification.advanced.example.json).

Android replaces displayed notifications with the same collapse ID within the
same association. Receipts and opens still use the individual delivery ID.
`android.groupKey` groups notifications; `android.groupSummary` supplies summary
text. Create each requested notification channel in the host app before sending.
A missing explicit channel skips display and emits `NOTIFICATION_CHANNEL_MISSING`.
An omitted channel uses the configured default. Existing channel sound and user
preferences are respected. Delivery priority controls transport, not channel
importance. Images are optional, limited to 2 MiB and sampled to at most 2048
pixels per dimension; failures retain the text notification.

The backend constructs APNs sound, badge, subtitle, interruption-level, thread,
mutable-content and content-available fields. An omitted `ios.sound` uses the
backend default; explicit `null` requests silence. Kotlin, Swift and Dart expose
`ios.hasSound` to distinguish those cases. Critical alerts require the host's
Apple entitlement and user authorization. `contentAvailable` requests background
processing alongside the visible alert; it does not turn Android messages into
silent notifications or guarantee background execution.

## App identity and APNs routing

Native adapters report the actual Android package name or iOS bundle identifier.
When an embedded provisioning profile is present, iOS reads its APNs entitlement.
For builds without that profile, configure the environment explicitly from the
build's signing configuration (an explicit setting takes precedence):

```swift
let sdk = try await NotificationDev.initialize(
    SdkConfig(projectId: projectId, apnsEnvironment: .production))
```

For Flutter, use `SdkConfig(iosApnsEnvironment: 'production', projectId: projectId)`.
For Expo, set `iosApnsEnvironment: 'production'` in the notification.dev config
plugin. Valid values are `sandbox` and `production`. Flutter can add or update this field
in restored startup configuration without resetting installation identity; Android
ignores this iOS-only option. Updating only the APNs environment through repeated
Swift initialization queues new routing metadata and requests native token
registration. Other initialization settings remain fixed for the process. This setting is independent
of the API hostname and project: distribution/TestFlight builds use production
APNs even when talking to a development backend. Do not infer it from a debug
flag. Omission retains legacy provider selection and can produce
`PROVIDER_CONFIGURATION_REQUIRED` when the backend cannot select one provider.

Changing routing metadata invalidates earlier tokens and Live Activity grants.
The SDK synchronizes routing metadata before Live Activity uploads,
discards obsolete activity registrations, and requires new activity IDs/grants
for private activities. After routing is acknowledged, an unrelated ordinary
push registration failure does not block Live Activity uploads. Stable registration input is encrypted and retained for
recovery after a lost response; metadata changes are reported separately.

`state.pushTokenRegistered` reflects the last acknowledged server snapshot.
`state.pushRegistration` describes native token acquisition/registration health.
Neither OS permission nor successful local initialization establishes backend
readiness. Check pending changes, identity, diagnostics and a refreshed state.

After a revoked credential, the SDK hides account state, clears pending private
work, and stops sending under that installation. It does not automatically recreate
the account. An explicit logout can establish a fresh anonymous installation.

## Foreground suppression

Suppress alerts matching all specified custom-data fields while the app is in
the foreground. Receipts and notification events are still observed. Clear the
filter when leaving the screen. It is process-local and is cleared on login/logout.

```kotlin
NotificationDev.suppressForegroundNotifications(
    buildJsonObject { put("conversationId", "42") })
NotificationDev.suppressForegroundNotifications(null)
```

```swift
try sdk.suppressForegroundNotifications(matchingData: ["conversationId": .string("42")])
try sdk.suppressForegroundNotifications(matchingData: nil)
```

```dart
await sdk.suppressForegroundNotifications(matchingData: {'conversationId': '42'});
await sdk.suppressForegroundNotifications();
```

```typescript
await sdk.suppressForegroundNotifications({ conversationId: "42" });
await sdk.suppressForegroundNotifications(null);
```

Swift also supports `sdk.foregroundPresentation`, a synchronous closure returning
`UNNotificationPresentationOptions` after SDK eligibility and data-filter checks.

## iOS image attachments

Add a Notification Service Extension target in Xcode and embed its `.appex` in
the main app. Match the app signing team and use an extension bundle ID prefixed
by the app bundle ID. Rebuild and install the updated app before sending a fresh
notification. Link only the
`NotificationDevServiceExtension` Swift package product to that target, with
`APPLICATION_EXTENSION_API_ONLY = YES`. Use this principal class:

```swift
import NotificationDevServiceExtension

final class NotificationService: NotificationDevServiceExtension.NotificationService, @unchecked Sendable {}
```

Configure `NSExtensionPointIdentifier` as `com.apple.usernotifications.service`
and `NSExtensionPrincipalClass` as `$(PRODUCT_MODULE_NAME).NotificationService`.
Set `imageUrl`; the backend automatically adds `aps.mutable-content: 1`, even
when `ios.mutableContent` is omitted or false. Explicit `ios.mutableContent: true`
also enables extension processing for messages without an image. The helper accepts JPEG/PNG/GIF,
limits downloads to 2 MiB and five seconds, rejects redirects and dimensions over
2048 pixels, and preserves the original alert/userInfo on failure or expiry.
Use HTTPS in production. The extension does not initialize the app SDK, send
receipts, or establish account eligibility before an OS-displayed alert.

When sharing an extension with another provider, override `didReceive`, route
requests with `NotificationService.owns(request)` to `super`, and route other
requests to that provider's extension helper. Forward the expiry callback only
to the helper handling the current request.

## Coexistence with another push provider

The presence of `notification_dev` establishes ownership, even for malformed,
expired or suppressed payloads. Android hosts owning their Firebase service can
use `handleRemoteMessage` and its Boolean ownership result. Keep OneSignal's own
FCM broadcast receiver installed; do not manually feed messages into its private
APIs. The default notification.dev service ignores unrelated messages.

On iOS, retain `NotificationDelegateForwarder`, initialize other delegates first,
then call `install()`. It handles owned notifications and forwards unrelated
foreground/tap callbacks to the previous delegate, with completion handlers guarded
against repeated invocation. Installation captures the previous delegate once;
if the host subsequently replaces it, the host must explicitly forward callbacks.
Forward native APNs registration success/failure to both integrations using their
documented paths. Expo and automatic Flutter integration install this forwarder.
Other plugins that claim the same Firebase service still require host integration;
support for arbitrary push-plugin combinations is not implied.

The Expo example pins Expo 58.0.23, React Native 0.88.1,
`react-native-onesignal` 5.5.17 and `onesignal-expo-plugin` 2.8.0. Enable its
optional OneSignal plugin with `EXPO_PUBLIC_ONESIGNAL_APP_ID`. Its provider-switch
example requires acknowledged identity, a registered server token, settled pending
work and permission before opting out of the old provider. Applications own their
remote rollout flag, authenticated account state, serialized transitions and
dual-send policy. A local opt-out does not recall already queued notifications.

Validate both providers on physical devices before enabling a migration: cold
start, foreground/background, terminated process, token rotation, account changes,
tap data, duplicate display and rollback. Unit tests and a successful native build
do not prove APNs/FCM delivery or coexistence on a device.

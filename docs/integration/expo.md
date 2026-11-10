# Expo SDK integration

This guide targets SDK release `0.3.0`. Inspect the app's existing notification
integration before changing startup or callback ownership. Use the public project
UUID from the dashboard, never a server API key. Configure matching FCM/APNs
providers in the dashboard or use the `push-provider-setup` skill with MCP
provider tools. MCP provider setup requires `credentials:manage` and owner/admin
access. Provider secrets stay on the server.

Canonical source: [this guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/expo.md).
The [platform README](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/expo/README.md) covers released installation and usage.

TypeScript API over the **released** notification.dev native SDKs. Android uses
`dev.notification:android-sdk:0.3.0` from Maven Central. iOS uses the remote
`NotificationDev` Swift package at exact version `0.3.0`, including its
checksum-verified Kotlin core XCFramework. The npm package contains bridge and
configuration code only, with no native SDK sources or binaries.

Compatibility: Expo SDK 57–58; the example pins SDK 58 / React Native 0.88, Android API 24+, iOS
16.4+, and Apple Silicon simulators. Requires a development or production build;
Expo Go, web, and desktop are unsupported. Bare React Native apps require Expo
modules and equivalent native configuration.

The plugin selects Kotlin `2.3.20` to match the released native SDK. Remove
conflicting Kotlin overrides before prebuild.

## Install and configure

```sh
npx expo install @notification.dev/expo
npx expo install expo-dev-client
```

Add the plugin and Android Firebase configuration to `app.json`:

```json
{
  "expo": {
    "ios": { "bundleIdentifier": "com.example.app" },
    "android": {
      "package": "com.example.app",
      "googleServicesFile": "./google-services.json"
    },
    "plugins": [
      [
        "@notification.dev/expo",
        {
          "projectId": "YOUR_PUBLIC_PROJECT_UUID"
        }
      ]
    ]
  }
}
```

Use a real UUID for `projectId`. Configure matching FCM/APNs providers in
notification.dev and push signing for the iOS bundle ID. Provider credentials
stay on the server. Expo's Google Services configuration handles the Android
file and Gradle plugin. Direct FCM/APNs tokens are used, without Expo Push Service.

The plugin adds native startup configuration, notification permission, a default
Android icon, push entitlements, and an idempotent CocoaPods post-install hook.
React Native attaches the **remote Swift package** to the bridge; the hook
registers its release in the app workspace without linking the static product
twice. CocoaPods installs the Expo bridge/dependencies; SPM resolves the native
notification.dev SDK. Consuming iOS apps need no Kotlin build or SDK checkout.

Build locally with `npx expo run:android` / `npx expo run:ios`, or use EAS Build.
After changing native dependencies or plugin configuration, regenerate with
`npx expo prebuild --clean` and rebuild. Clean prebuild removes handwritten edits
in generated native directories; keep customizations in plugins.

| Plugin option         | Default                         | Meaning                                          |
| --------------------- | ------------------------------- | ------------------------------------------------ |
| `projectId`           | Required                        | Public project UUID                              |
| `baseUrl`             | `https://app.notification.dev/` | API base URL                                     |
| `androidIcon`         | Built-in bell                   | Monochrome transparent PNG path                  |
| `androidChannelId`    | `notification_dev`              | Notification channel ID                          |
| `androidChannelName`  | `Notifications`                 | Channel display name                             |
| `displayInForeground` | `true`                          | Show eligible foreground notifications           |
| `loggingEnabled`      | `false`                         | Sanitized native logging                         |
| `allowHttp`           | `false`                         | Allow HTTP and configure OS transport exceptions |

Configuration is baked into the native app and applies on every process startup,
before JavaScript, including background Firebase startup. Change it with a new
native build; OTA JavaScript cannot switch projects/API hosts. Clear development
app data when changing projects or API hosts.

## Use

```ts
import { NotificationDev } from "@notification.dev/expo";

const sdk = await NotificationDev.initialize();
const states = sdk.addListener("states", (state) => console.log(state));
const errors = sdk.addListener("diagnostics", (error) =>
  console.log(error.code),
);
await sdk.login("opaque-customer-id");
await sdk.setTags({ plan: "premium", paid: true });
await sdk.setEmail("customer@example.com", true);
await sdk.setPushOptedIn(true);
await sdk.track("checkout", { total: 42 });

// Call from an explicit user action:
await sdk.requestPushPermission();

// On unmount:
states.remove();
errors.remove();
```

Initialization waits for local readiness, not server registration. Methods reject
with `SdkException` (`code`, `message`). Tags accept strings, booleans, and finite
numbers; event properties accept finite JSON. `getState()`, `getTags()`, and
`getEmail()` read locally; `refresh()` fetches server state.

`states` and `pendingOpens` replay their latest snapshots. `notifications` and
`diagnostics` are live events. Subscriptions share one native event listener and
stop native collectors when the last subscription is removed. The native SDK
continues working independently of JavaScript reloads and subscriptions.

## Handle notification opens

`pendingOpens` emits unacknowledged notification taps, including the tap that
launched the app. Subscribe once after your navigation UI is ready, route the
notification, then acknowledge it. `notifications` reports received notifications;
use `pendingOpens` for taps. The SDK does not launch deep links automatically.

```ts
let queue = Promise.resolve();
const opens = sdk.addListener("pendingOpens", (pending) => {
  queue = queue
    .then(async () => {
      for (const open of pending) {
        await navigateFromNotification(
          open.notification.deepLink,
          open.notification.data,
        );
        await sdk.acknowledgeOpen(open.interactionId);
      }
    })
    .catch(reportOpenError);
});
```

The promise queue keeps handling serial and reports failures. Use one subscription
in your app's navigation owner. Call `opens.remove()` to stop receiving snapshots;
already-queued work still finishes, so keep the router available until `queue`
settles. Native integration forwards tap callbacks automatically.

The app supplies `navigateFromNotification` and `reportOpenError`. Navigation
must complete before acknowledgment. Route only links/data your app supports.
Snapshots can include the same open more than once, even while acknowledgment is
in progress, so routing should safely select an already-open destination. For
non-repeatable actions, deduplicate by `interactionId` in your routing layer.
If handling fails, the open stays pending; retry through your app's retry flow or
restart the observer. A new snapshot is not guaranteed immediately.

## Notification ownership

Other methods include `logout()`, `removeTags(keys)`, `removeEmail()`,
`setEmailOptedIn(enabled)`, `getPushPermissionStatus()`, and `openPushSettings()`.
Opening settings does not confirm permission.

The SDK handles messages carrying `notification_dev`. Its iOS delegate forwarder
passes unrelated notifications to the previous delegate. Initialize other provider
delegates first. The optional OneSignal example demonstrates coexistence without
intercepting OneSignal's Android receiver. The plugin still rejects
`expo-notifications` and `@react-native-firebase/messaging`, which require separate
host callback/service integration. See the
[coexistence guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/advanced-push.md#coexistence-with-another-push-provider).

See [shared semantics](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/README.md#shared-behavior) and
[payload formats](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/contract/README.md#push-payloads). Android requires data-only
FCM, without an FCM `notification` block. iOS background alerts can appear before
SDK checks; pending sync resumes when the OS permits execution.

## Shared identity and synchronization behavior

Initialize once per process with stable configuration. Initialization establishes
local readiness; inspect state and diagnostics for server registration and sync
failures. Setters update local state and queue synchronization rather than proving
server acceptance. `refresh()` retains pending local edits.

Tags and email belong to the user; push preference belongs to the installation.
Email preference, push preference, and OS permission are independent. Login uses
unverified external IDs: use opaque random IDs and retry the same ID after an
ambiguous result. Association changes discard pending work; logout creates a new
anonymous association without unsubscribing the old user's email.

Events persist offline and retry for up to seven days. App opens are automatic;
`notification_dev.app_opened` and `notification_dev.subscriber_created` are reserved.
Pending notification opens replay until acknowledged. Handle them serially with
routing that safely handles repeats, and acknowledge only after handling the open.
The SDK does not launch deep links automatically.

## Verify the integration

Use a disposable project and a signed app on a device with matching providers:

1. Start the app and inspect registration, push token status, sync state, and diagnostics.
2. Request permission from a user action, enable SDK push preference, and verify the
   installation appears in the dashboard. Also test denial and return from settings.
3. Send a campaign from the dashboard while foregrounded and backgrounded. Android
   requires data-only FCM; Firebase console notification messages do not exercise
   the SDK path. Provider acceptance alone does not prove device delivery.
4. Tap a notification while running, backgrounded, and after process termination.
   Verify routing, replay before acknowledgment, and no replay after acknowledgment.
   Android force-stop prevents delivery until the app is opened again.
5. Edit tags/email and track an event offline; restart and reconnect, then verify
   sync. Log in/out and verify the new association and notification handling.

## Troubleshooting

- No server registration: verify the project UUID and API host, inspect diagnostics
  and pending state, and check network access. Keep provider secrets on the server.
- Registered but no push: check OS permission, SDK push preference, token status,
  provider credentials, Android Firebase project, and iOS bundle ID/environment.
- Missing opens: check callback ownership and forwarding, subscribe to pending
  opens at app startup, and avoid acknowledging before navigation completes.
- Local storage failure: inspect the diagnostic code; iOS needs signing and
  Keychain access. Clear development app data when changing projects or hosts.
- HTTP development backend: explicitly allow HTTP in SDK configuration and host OS
  transport policy. Android emulators reach the host at `10.0.2.2`; iOS simulators
  can use `localhost`. Keep production endpoints on HTTPS.

Copyright 2026 The Bold Apps, LLC. Licensed under
[Apache-2.0](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/LICENSE).

## Advanced push and coexistence

See the [advanced integration guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/advanced-push.md) for payload options, app/APNs routing, server-acknowledged `pushTokenRegistered`, foreground suppression, images, revocation, and provider forwarding. Native adapters provide these capabilities underneath Flutter and Expo.

## Images on iOS

Set `imageUrl`; the backend automatically enables APNs image processing. A
Notification Service Extension must be embedded in the installed iOS app. The main SDK
alone does not add that extension. Link the `NotificationDevServiceExtension`
Swift package product to a native extension target, use the app's signing team
and an app-prefixed extension bundle ID, and configure its principal class as
shown in the [iOS image setup](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/ios.md#ios-image-notifications).
Use a direct HTTPS JPEG/PNG/GIF URL, no redirects, at most 2 MiB, downloadable
within five seconds. Rebuild the native app after adding the extension, then
send a fresh background notification; a hot reload cannot install an extension.

## Live Activities

This facade is available in wrapper release `0.3.0` and uses matching native
SDK `0.3.0` artifacts from Maven Central and the released Swift package.

The client exposes `liveActivities` for capability checks, persisted provider
configuration, local start/end, listing, and current-association activity snapshots.
Use the default `DefaultLiveActivityAttributes` type and embed a native SwiftUI
widget extension linked to `NotificationDevActivityKit`. Remote start/update/end
and grant creation stay on your authenticated backend; never ship a project API
key in the app. Local start requires a fresh grant for the activity ID, current
installation/association, type and provider. Android reports unsupported and does
not attempt ActivityKit operations.

See the [complete facade guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/live-activity-facades.md)
for API examples, startup restoration, the shared widget example, JSON wrapping,
state/diagnostic streams, dismissal dates and device checks. Configure once after
initialization; the native bridge persists public options and restores observers
on background/restart launches before Dart/JavaScript runs. Lists and streams do
not include registration grants or APNs tokens, and exclude other accounts' activities.

```ts
const capabilities = await sdk.liveActivities.getCapabilities();
if (capabilities.supported) {
  await sdk.liveActivities.configure({
    providerConfigurationId: "YOUR_APNS_PROVIDER_UUID",
  });
  const subscription = sdk.liveActivities.addListener(handleActivities);
  const current = await sdk.liveActivities.getActivities();
  const started = await sdk.liveActivities.start({
    activityId: "order-123",
    attributes: { name: "Delivery" },
    content: { status: "Preparing" },
    registrationToken: grantFromYourBackend,
  });
  await sdk.liveActivities.end("order-123", { status: "Delivered" });
  subscription.remove();
}
```

Set `iosLiveActivities: true` in the notification.dev Expo config plugin to add
`NSSupportsLiveActivities`. It does not create a widget target: embed a SwiftUI
widget using `ActivityConfiguration(for: DefaultLiveActivityAttributes.self)`
and link only `NotificationDevActivityKit` there. Rebuild the native app; Expo Go
cannot load the facade. To obtain a local-start grant, your backend calls
`POST /v1/projects/{projectId}/live-activities/{activityId}/registration-grants`
with `activityType`, `providerConfigurationId`, `installationId`, and current
`associationId`. Wait for identity synchronization and authorize the installation
against the signed-in user before issuing the grant. Never log grants or put
project API keys in the app. Remote sends use `eventAttributes: {"data":{...}}`
and `eventUpdates: {"data":{...}}`; the facade takes unwrapped dictionaries.
Local `end` accepts an optional dismissal timestamp in milliseconds since Unix
epoch; omission requests immediate dismissal. On Android, capabilities are false,
activity lists/streams are empty, and mutations throw `UNSUPPORTED_PLATFORM`.
Registration errors use the client's `diagnostics` listener.

Identical Live Activity configuration is idempotent. A valid provider/options
change is saved for the next native launch and reports
`LIVE_ACTIVITY_RESTART_REQUIRED`; restart the app to activate it. Invalid
configuration leaves working startup settings intact.

# Expo SDK

TypeScript API over the **released** notification.dev native SDKs. Android uses
`dev.notification:android-sdk:0.2.0` from Maven Central. iOS uses the remote
`NotificationDev` Swift package at exact version `0.2.0`, including its
checksum-verified Kotlin core XCFramework. The npm package contains bridge and
configuration code only, with no native SDK sources or binaries.

Initial compatibility: Expo SDK 57 / React Native 0.86, Android API 24+, iOS
16.4+, and Apple Silicon simulators. Requires a development or production build;
Expo Go, web, and desktop are unsupported. Bare React Native apps require Expo
modules and equivalent native configuration.

The plugin selects Kotlin `2.3.20` to match the released native SDK. Remove
conflicting Kotlin overrides before prebuild.

## Integrate with an AI agent

Connect an MCP client using Streamable HTTP to
`https://app.notification.dev/mcp` and complete the OAuth sign-in and consent flow.
Ask: “Use `get_sdk_integration_guide` with `platform: "expo"` and integrate
notification.dev into this app.” The tool supports `android`, `ios`, `flutter`,
and `expo`; retrieving a guide requires no additional connection permissions.

The [integration guides](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/expo.md) cover configuration, usage, troubleshooting,
and device verification. This README remains usable without MCP. Agents use OAuth;
mobile apps initialize with the public project UUID, not the agent token or a
server API key.

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

## Opens and notification ownership

```ts
const opens = sdk.addListener("pendingOpens", (pending) => {
  // Send pending opens to your app's serialized navigation queue.
  // After successful navigation: await sdk.acknowledgeOpen(open.interactionId)
});
```

Opens replay until acknowledged. Deduplicate in-flight navigation by
`interactionId`; the SDK does not automatically launch deep links. Other methods
include `logout()`, `removeTags(keys)`, `removeEmail()`, `setEmailOptedIn(enabled)`,
`getPushPermissionStatus()`, and `openPushSettings()`. Opening settings does not
confirm permission.

This version requires exclusive push callback ownership. Do not combine it with
`expo-notifications`, `@react-native-firebase/messaging`, a custom Firebase service,
or another notification center delegate. The plugin rejects known conflicting
packages; iOS reports delegate conflicts. Manual integration is not supported.

See [shared semantics](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/README.md#shared-behavior) and
[payload formats](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/contract/README.md#push-payloads). Android requires data-only
FCM, without an FCM `notification` block. iOS background alerts can appear before
SDK checks; pending sync resumes when the OS permits execution.

## Development

```sh
npm ci
npm test
ruby test/spm_test.rb
npm pack --dry-run
```

The Ruby test requires the `xcodeproj` gem, included with CocoaPods. See the
[example](https://github.com/TheBoldApps/notification.dev-SDKs/tree/main/example/expo-example) for native builds and device checks.
Native version overrides and local SDK substitutions are not provided.

Copyright 2026 The Bold Apps, LLC. Apache-2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

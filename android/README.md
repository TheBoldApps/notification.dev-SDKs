# Android SDK

Requires Android API 23+, JDK 17, and Android SDK 36.

## Integrate with an AI agent

Connect an MCP client using Streamable HTTP to
`https://app.notification.dev/mcp` and complete the OAuth sign-in and consent flow.
Ask: “Use `get_sdk_integration_guide` with `platform: "android"` and integrate
notification.dev into this app.” The tool supports `android`, `ios`, `flutter`,
and `expo`; retrieving a guide requires no additional connection permissions.

The [integration guides](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/android.md) cover configuration, usage, troubleshooting,
and device verification. This README remains usable without MCP. Agents use OAuth;
mobile apps initialize with the public project UUID, not the agent token or a
server API key.

## Install from Maven Central

Include `google()` and `mavenCentral()` in your app's dependency repositories,
then add to the app module:

```kotlin
dependencies {
    implementation("dev.notification:android-sdk:0.2.0")
}
```

Configure the Google Services Gradle plugin and place the Firebase Android client
configuration in `app/google-services.json`. Its package name must match your
application ID. Configure an FCM provider in notification.dev for the same Firebase
project; service-account credentials stay on the server. Firebase Messaging and
the required notification permissions are included by the SDK.

Add a monochrome notification drawable and retain it during resource shrinking.
See the [integration guide](../docs/integration/android.md) for host configuration.

## Install locally

Add the SDK repository to your app's `settings.gradle.kts`:

```kotlin
includeBuild("/path/to/notification.dev-SDKs") {
    dependencySubstitution {
        substitute(module("dev.notification:android-sdk")).using(project(":android"))
    }
}
```

Add `implementation("dev.notification:android-sdk:0.2.0")` to your app dependencies. Configure Firebase with the Google Services plugin and your `google-services.json`. Firebase Messaging is included by the SDK.

## Initialize and use

Initialize in `Application.onCreate`, including background process startup:

```kotlin
NotificationDev.initialize(this, SdkConfig(
    projectId = "YOUR_PUBLIC_PROJECT_UUID",
    smallIcon = R.drawable.ic_notification,
))
```

Call suspending methods from a coroutine:

```kotlin
NotificationDev.login("opaque-customer-id")
NotificationDev.setTags(mapOf("plan" to "premium"))
NotificationDev.setEmail("customer@example.com", optedIn = true)
NotificationDev.setPushOptedIn(true)
NotificationDev.track("checkout")
NotificationDev.state.collect { state -> /* Update your UI. */ }
```

See [shared behavior](../README.md#shared-behavior). Collect `diagnostics` for errors; `loggingEnabled = true` enables sanitized Logcat output under `NotificationDev`. Custom WorkManager hosts must initialize WorkManager before the SDK. Use only the main application process.

## Notifications and permissions

The SDK supplies its own Firebase service. Send data-only FCM messages with the [notification envelope](../contract/notification.example.json) serialized into the `notification_dev` data field. An FCM `notification` block bypasses SDK eligibility checks.

Forward launcher activity intents from both `onCreate` and `onNewIntent` using `lifecycleScope.launch { NotificationDev.handleIntent(intent) }`. Collect `pendingOpens`, navigate, then call `acknowledgeOpen(interactionId)`.

Request permission explicitly with `requestPushPermission(activity, settingFallback = true)`. Read live OS status with `getPushPermissionStatus()` or open settings with `openPushSettings(activity)`. Opening settings does not mean permission was granted.

If your app owns the Firebase service, remove the SDK service in your manifest:

```xml
<service android:name="dev.notification.sdk.NotificationMessagingService"
         tools:node="remove" />
```

Declare `xmlns:tools="http://schemas.android.com/tools"` on the manifest. Forward `onNewToken` to `NotificationDev.onNewToken(token)` and `onMessageReceived` to `NotificationDev.handleRemoteMessage(message)`; finish local persistence before returning, using `runBlocking` in those service callbacks. Only one service may own `MESSAGING_EVENT`.

See the [runnable example](../example/android-example/README.md) for host setup and device checks.

## License

Copyright 2026 [The Bold Apps, LLC](https://theboldapps.com/).

The SDK, examples, and documentation in this repository are licensed under the
[Apache License, Version 2.0](../LICENSE). See [NOTICE](../NOTICE) for attribution.
Third-party components retain their respective licenses. The hosted notification.dev
service and its separate backend are not covered by this license.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions.

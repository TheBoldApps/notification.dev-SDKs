# Android SDK

Requires Android API 23+, JDK 17, and Android SDK 36.

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
the SDK option does not override OS restrictions. The 0.2.0 flag replaces the
previous API without compatibility aliases or startup configuration migration.

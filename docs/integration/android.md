# Android SDK integration

This guide targets SDK release `0.2.0`. Inspect the app's existing notification
integration before changing startup or callback ownership. Use the public project
UUID from the dashboard, never a server API key. Configure matching FCM/APNs
providers in the dashboard; MCP does not configure provider credentials.

Canonical source: [this guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/android.md).
The [platform README](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/android/README.md) covers released installation and usage.

Requires Android API 23+, JDK 17, and Android SDK 36.

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
See the [integration guide](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/docs/integration/android.md) for host configuration.

## Application startup

Declare the class below as `android:name=".ExampleApplication"` in the application
manifest. Use your package's generated `R` class for the icon.

```kotlin
import android.app.Application
import dev.notification.sdk.NotificationDev
import dev.notification.sdk.SdkConfig

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationDev.initialize(this, SdkConfig(
            projectId = "YOUR_PUBLIC_PROJECT_UUID",
            smallIcon = R.drawable.ic_notification,
        ))
    }
}
```

## Initialize and use

Call suspending methods from a coroutine:

```kotlin
NotificationDev.login("opaque-customer-id")
NotificationDev.setTags(mapOf("plan" to "premium"))
NotificationDev.setEmail("customer@example.com", optedIn = true)
NotificationDev.setPushOptedIn(true)
NotificationDev.track("checkout")
NotificationDev.state.collect { state -> /* Update your UI. */ }
```

See [shared behavior](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/README.md#shared-behavior). Collect `diagnostics` for errors; `loggingEnabled = true` enables sanitized Logcat output under `NotificationDev`. Custom WorkManager hosts must initialize WorkManager before the SDK. Use only the main application process.

## Notifications and permissions

The SDK supplies its own Firebase service. Send data-only FCM messages with the [notification envelope](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/contract/notification.example.json) serialized into the `notification_dev` data field. An FCM `notification` block bypasses SDK eligibility checks.

Forward launcher activity intents from both `onCreate` and `onNewIntent` using `lifecycleScope.launch { NotificationDev.handleIntent(intent) }`. Collect `pendingOpens`, navigate, then call `acknowledgeOpen(interactionId)`.

Request permission explicitly with `requestPushPermission(activity, settingFallback = true)`. Read live OS status with `getPushPermissionStatus()` or open settings with `openPushSettings(activity)`. Opening settings does not mean permission was granted.

If your app owns the Firebase service, remove the SDK service in your manifest:

```xml
<service android:name="dev.notification.sdk.NotificationMessagingService"
         tools:node="remove" />
```

Declare `xmlns:tools="http://schemas.android.com/tools"` on the manifest. Forward `onNewToken` to `NotificationDev.onNewToken(token)` and `onMessageReceived` to `NotificationDev.handleRemoteMessage(message)`; finish local persistence before returning, using `runBlocking` in those service callbacks. Only one service may own `MESSAGING_EVENT`.

See the [runnable example](https://github.com/TheBoldApps/notification.dev-SDKs/blob/main/example/android-example/README.md) for host setup and device checks.

## Handle notification opens

`pendingOpens` emits unacknowledged notification taps, including the tap that
launched the app. Subscribe once after your navigation UI is ready, route the
notification, then acknowledge it. `notifications` reports received notifications;
use `pendingOpens` for taps. The SDK does not launch deep links automatically.

```kotlin
val opensJob = lifecycleScope.launch {
    NotificationDev.pendingOpens.collect { pending ->
        for (open in pending) {
            navigateFromNotification(open.notification.deepLink, open.notification.data)
            NotificationDev.acknowledgeOpen(open.interactionId)
        }
    }
}
```

Import `androidx.lifecycle.lifecycleScope`, `kotlinx.coroutines.launch`, and
`kotlinx.coroutines.flow.collect`. Collection is serial, and the job is canceled
when the Activity is destroyed. Call `opensJob.cancel()` to stop observing sooner.
Handle failures through your app's coroutine error handler; an uncaught failure
ends collection.

The app supplies `navigateFromNotification`. Navigation must complete before
acknowledgment. Route only links/data your app supports.
Snapshots can include the same open more than once, even while acknowledgment is
in progress, so routing should safely select an already-open destination. For
non-repeatable actions, deduplicate by `interactionId` in your routing layer.
If handling fails, the open stays pending; retry through your app's retry flow or
restart the observer. A new snapshot is not guaranteed immediately.

## HTTP development endpoints

HTTPS is required by default. Explicitly set `allowHttp: true` (Swift:
`allowHTTP: true`) to permit HTTP to any host, including private LAN IPs. Configure
Android cleartext policy or iOS App Transport Security in the host app as needed;
the SDK option does not override OS restrictions.

## Launcher Activity callbacks

In your existing `ComponentActivity` or `AppCompatActivity`, forward both intents:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch { NotificationDev.handleIntent(intent) }
    // Create the UI and collect pendingOpens in your navigation layer.
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    lifecycleScope.launch { NotificationDev.handleIntent(intent) }
}
```

Import `android.content.Intent`, `android.os.Bundle`,
`androidx.lifecycle.lifecycleScope`, and `kotlinx.coroutines.launch`.

## Android host checklist

Register your `Application` subclass with `android:name` in the manifest so SDK
initialization also runs when Firebase starts the process in the background.
Use `compileSdk = 36`, Java 17, and `minSdk` of at least 23.
Keep `google-services.json` in the app module and apply
`id("com.google.gms.google-services")` there, using the plugin version selected
by your host project. Keep `google()` and `mavenCentral()` available.

Supply `R.drawable.ic_notification` as `smallIcon`. Create
`res/raw/keep.xml` for release resource shrinking:

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_notification" />
```

Use a Google Play services device or emulator for FCM. Request Android 13+
notification permission from an Activity after an explicit user action. Keep a
single Firebase service owner. Forward launcher intents on both cold startup and
`onNewIntent`; collect pending opens independently of the Activity intent path.

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

# Live Activities

The SDK manages ActivityKit token synchronization through `sdk.liveActivities`.
Your app supplies a Widget Extension and its UI. Local activities need iOS 16.1+;
push-to-start needs iOS 17.2+. The rest of the SDK continues to support iOS 15.

Configure an APNs `.p8` provider matching the main app's bundle ID and environment.
Enable Push Notifications and set `NSSupportsLiveActivities` to `YES` in the main
app's Info.plist. Live Activity authorization is independent of ordinary push
permission and `setPushOptedIn`.

Link the `NotificationDev` package product to the app. Link only
`NotificationDevActivityKit` to the widget: it contains the shared attributes and
JSON types without UIKit, SDK storage, or the Kotlin framework. Do not initialize
the SDK from an extension. These additions require a release containing this
feature; the previously published 0.2.0 binary does not contain the new core API.
For source development use the local `ios/` package and build its framework with
`./gradlew :core:stageSwiftFramework` from the repository root.

## Automatic setup

After initialization, call setup at **every launch**, including background launches:

```swift
import NotificationDev

if #available(iOS 16.1, *) {
    try sdk.liveActivities.setupDefault(providerConfigurationId: "YOUR_APNS_PROVIDER_UUID")
}
```

Setup watches existing and incoming activities, current and rotating tokens,
ActivityKit authorization, and ended/dismissed activities. The SDK retains the
observers; the app does not need to retain Tasks. Identical setup calls are safe.
Calling setup again with different options/provider for the same type throws
`CONFIGURATION_CHANGED`. `LiveActivitySetupOptions` defaults both
`enablePushToStart` and `enablePushToUpdate` to `true`.

The widget uses `ActivityConfiguration(for: DefaultLiveActivityAttributes.self)`.
Default attributes and content state both contain a `data` dictionary of
`JSONValue`. For remote starts use `activityType: "DefaultLiveActivityAttributes"`,
`eventAttributes: { "data": { ... } }`, and
`eventUpdates: { "data": { ... } }`. Updates replace the complete content state.
The server supplies the reserved `notification_dev` attribute automatically.

## Start locally

Your **own authenticated backend** must authorize the signed-in user, activity,
installation ID, and current association ID, then call:

```text
POST /v1/projects/{projectId}/live-activities/{activityId}/registration-grants
Authorization: Bearer <project API key>
Idempotency-Key: <stable request key>

{
  "activityType": "DefaultLiveActivityAttributes",
  "providerConfigurationId": "<APNs provider UUID>",
  "installationId": "<sdk.state.installationId>",
  "associationId": "<sdk.state.associationId>"
}
```

It returns `{ registrationToken, expiresAt }`. Return the grant to the app through
your authenticated API. The grant expires in ten minutes. Establish the
installation-to-account binding in that authenticated flow; SDK external IDs
alone are unverified assertions. Never embed a project API key or expose the
installation credential in app integration code.

Wait for an association ID and `identityChangePending == false`, obtain the grant,
then:

```swift
if #available(iOS 16.1, *) {
    let activity = try await sdk.liveActivities.startDefault(
        "order-123",
        attributes: ["orderId": .string("order-123")],
        content: ["status": .string("Preparing"), "minutesRemaining": .number(25)],
        registrationToken: grantFromYourBackend
    )
    // Keep activity if the app will update or end its UI locally.
}
```

Use a fresh activity ID for every event. The server retains ended IDs and does
not allow reuse. `startDefault` requires `setupDefault` first. A successful return
means ActivityKit created the local activity, not that APNs or the backend has
acknowledged its token.

## Custom typed attributes

Compile the same attributes into the app and widget targets:

```swift
import NotificationDevActivityKit

@available(iOS 16.1, *)
struct DeliveryAttributes: NotificationDevLiveActivityAttributes {
    struct ContentState: Codable, Hashable {
        var status: String
        var minutesRemaining: Int
    }
    var orderId: String
    var notification_dev: LiveActivityRegistrationMetadata?
}
```

Call `try sdk.liveActivities.setup(DeliveryAttributes.self,
providerConfigurationId: providerId)` at startup. Remote starts use
`activityType: "DeliveryAttributes"`, attributes such as `{ "orderId": "order-123" }`,
and state such as `{ "status": "Preparing", "minutesRemaining": 25 }`.

For a local start, obtain a grant for that type and create an ActivityKit activity
with `pushType: .token`. Populate `notification_dev` with
`.create(activityId: "order-123", registrationToken: grantFromYourBackend)`.
The SDK discovers and observes it. Activities without metadata are left alone.
The example app contains the complete typed start and widget implementation.

## Manual token APIs

Apps managing their own ActivityKit observers can use:

```swift
try await sdk.liveActivities.enter(
    "order-123", withToken: updateTokenHex, registrationToken: initialGrant)
try await sdk.liveActivities.enter("order-123", withToken: rotatedUpdateTokenHex)
try await sdk.liveActivities.exit("order-123")

try await sdk.liveActivities.setPushToStartToken(
    "DeliveryAttributes", withToken: startTokenHex,
    providerConfigurationId: providerId, enabled: activitiesEnabled)
try await sdk.liveActivities.removePushToStartToken(
    "DeliveryAttributes", providerConfigurationId: providerId)
```

Tokens are even-length hexadecimal ActivityKit tokens, **not** ordinary APNs
device tokens. The start-token methods also accept an attributes type on iOS 17.2+.
Use manual methods without automatic setup, or disable the corresponding setup
option when your app owns that observer. Automatic setup manages start-token
eligibility and may resynchronize it during later lifecycle changes.

`exit` stops server updates and suppresses automatic re-registration of that ID.
It does not end the local UI. End the activity with ActivityKit's `end` API and a
final content state separately. Automatic setup also unregisters when it observes
an ended/dismissed activity. Updating the UI locally does not broadcast to other
devices.

## Remote starts and audiences

Your backend sends a remote start through
`POST /v1/projects/{projectId}/live-activities/{activityId}/start`, using its project
API key and an idempotency key. An `external_users` audience accepts
`externalUserIds`; a `subscribers` audience accepts `subscriberIds`. Target one
user or up to 1,000 unique users. Each eligible installation gets its own activity.
Devices sharing an activity ID receive the same updates/end events. Use separate
IDs for personalized content, such as individual orders.

Update or end the shared event through
`POST /v1/projects/{projectId}/live-activities/{activityId}/notifications`, with
`event: "update"` or `event: "end"` and the complete `eventUpdates`. An accepted
operation means queued, not displayed. Tokens must already be registered before
remote starts can reach a device.

## Retry and identity behavior

Mutations validate and queue work locally. The SDK encrypts tokens/grants with its
existing state, preserves captured requests and idempotency keys across retries,
and resumes when iOS next permits execution. Storage failures are reported and
work may remain only in memory. Background execution and display are not
guaranteed. Watch `sdk.diagnostics`, `state.lastError`, and `hasPendingChanges`.
Terminal server rejections stop retrying; a rejected/expired grant needs renewed
authorization from your backend. Repeating the identical token/grant does not
restart a permanently rejected request; supply a fresh grant.

Activity registrations retain their original association. Identity transitions
suspend observers/uploads and discard obsolete pending registrations. Existing
private activities are not rebound to the next user. Use new activity IDs and
grants after login, logout, or account switching. Start-token eligibility is
resynchronized for the new association. A registration token is not a general
account credential and must not be logged.

## Verify on a physical device

Use your own sandbox provider and the example widget. Verify local start → token
registration → server update → server end; remote start while backgrounded →
update-token registration → update → end; process relaunch, rotation, permission
changes, and login/logout. Test a shared activity on two users' devices and a
separate private activity for each user. Confirm visible widget content; APNs
acceptance alone does not establish successful decoding or display.

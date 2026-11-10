# Live Activities in Flutter and Expo

The native facade is available in SDK `0.3.0`; native tag `0.2.0` does not
contain it. The Flutter and Expo facades remain unreleased until their package
versions and native dependency pins are updated. Use matching native/Flutter/Expo
sources for contributor builds.

Both bridges expose the same dictionary-based ActivityKit facade. It requires
an iOS app with a native SwiftUI widget extension. Local activities require iOS
16.1+; push-to-start requires iOS 17.2+. The included widget targets iOS 16.2+.
Expo requires a development or production build; Expo Go cannot load the module.

The facade configures the fixed `DefaultLiveActivityAttributes` schema. Custom
Swift attribute types remain a native integration. Android capability detection
returns all false, activity lists/streams are empty, and mutations fail with
`UNSUPPORTED_PLATFORM`. OS Live Activity permission is independent of ordinary
push permission and SDK push opt-in.

## Widget setup

Use the [shared example widget](../example/live-activity-widget/README.md), or add
a Widget Extension target yourself. Link **only** `NotificationDevActivityKit` in
that target. Use `ActivityConfiguration(for: DefaultLiveActivityAttributes.self)`
and read attributes from `context.attributes.data` and state from
`context.state.data`. Do not duplicate the attributes struct under another module.

Embed the extension in the app, match signing teams, use an app-prefixed extension
bundle ID, and set `NSSupportsLiveActivities = true` in the app's Info.plist.
The Expo plugin can set that flag with `iosLiveActivities: true`; it does not
create a widget target. Re-run the example attachment helper after Expo prebuild.

## Configure and observe

Flutter:

```dart
final sdk = await NotificationDev.initialize(config);
final capabilities = await sdk.liveActivities.getCapabilities();
if (capabilities.supported) {
  await sdk.liveActivities.configure(const LiveActivityConfiguration(
    providerConfigurationId: 'YOUR_APNS_PROVIDER_UUID',
  ));
}
final subscription = sdk.liveActivities.states.listen((activities) {
  for (final activity in activities) {
    print('${activity.activityId}: ${activity.state} ${activity.content}');
  }
});
final activities = await sdk.liveActivities.getActivities();
// Cancel the subscription when its owning screen/service is disposed.
```

Expo:

```ts
const sdk = await NotificationDev.initialize();
const capabilities = await sdk.liveActivities.getCapabilities();
if (capabilities.supported) {
  await sdk.liveActivities.configure({
    providerConfigurationId: "YOUR_APNS_PROVIDER_UUID",
  });
}
const subscription = sdk.liveActivities.addListener((activities) => {
  console.log(activities);
});
const activities = await sdk.liveActivities.getActivities();
// subscription.remove() when its owning screen/service is disposed.
```

`configure` defaults `enablePushToStart` and `enablePushToUpdate` to true. It
persists only the public provider/options, scoped to the SDK project and API
origin. Native startup restores observers before Dart/JavaScript executes, so
remote-started activities can register update tokens during background launches
and after process restarts. Repeat identical configuration freely. A valid change to provider/options is
saved for the next native launch and reports `LIVE_ACTIVITY_RESTART_REQUIRED`;
restart the app to activate it. Existing observers retain their configuration
until that restart. Invalid configuration does not replace working settings.

The stream replays its current list, then emits content/state changes and removals.
Each snapshot contains `activityId`, `nativeActivityId`, `state`, `attributes`, and
`content`. Lists include active/stale/pending activities bound to the current
association, including remote starts; they clear on an account change. A newly
started activity can appear after binding reconciliation. Ended/dismissed UI is
removed from the list, even when the OS retains its final lock-screen card.
No APNs token or registration grant is exposed by snapshots. Registration/upload
errors use the existing SDK diagnostics stream. Initialization and local start
success do not prove the backend has acknowledged token registration.

## Local start and end

Your authenticated backend issues a short-lived registration grant using
`POST /v1/projects/{projectId}/live-activities/{activityId}/registration-grants`
with `activityType: "DefaultLiveActivityAttributes"`, `providerConfigurationId`,
`installationId`, and current `associationId`. Authorize that installation against
the signed-in user before returning a grant to the app. Use the SDK's current
state, wait for `identityChangePending == false`, and do not log the grant.
Project API keys belong on the backend, never in Dart/JavaScript or app config.

Flutter:

```dart
final activity = await sdk.liveActivities.start(
  activityId: 'order-123',
  attributes: {'name': 'Delivery test'},
  content: {'status': 'Preparing'},
  registrationToken: grantFromYourBackend,
);
await sdk.liveActivities.end('order-123', content: {'status': 'Delivered'});
```

Expo:

```ts
const activity = await sdk.liveActivities.start({
  activityId: "order-123",
  attributes: { name: "Delivery test" },
  content: { status: "Preparing" },
  registrationToken: grantFromYourBackend,
});
await sdk.liveActivities.end("order-123", { status: "Delivered" });
```

Choose a fresh activity ID for each new activity. Starting an already active ID,
including concurrent local start calls, fails with `LIVE_ACTIVITY_ALREADY_EXISTS`.
A failed start releases the local fence so it can be retried after correction.

`end` ends the local ActivityKit UI and durably queues unregistration. Its optional
dismissal date is a Dart `DateTime` or an Expo number in milliseconds since Unix
epoch. Omission requests immediate dismissal. Only current-association activities
can be ended; unknown/old-account IDs fail with `LIVE_ACTIVITY_NOT_FOUND`.

## Remote start, update and end

Remote operations stay on the backend. No server-send or server-key API is exposed
in the facade. Use `activityType: "DefaultLiveActivityAttributes"` and the configured
provider for a remote start. The generic widget's dictionaries are wrapped in `data`:

```json
{
  "activityType": "DefaultLiveActivityAttributes",
  "providerConfigurationId": "YOUR_APNS_PROVIDER_UUID",
  "audience": { "type": "external_users", "externalUserIds": ["customer-123"] },
  "eventAttributes": { "data": { "name": "Delivery test" } },
  "eventUpdates": { "data": { "status": "Preparing" } },
  "alert": { "title": "Delivery", "body": "Track your order" }
}
```

Updates/end use `eventUpdates: {"data":{"status":"On the way"}}`. The facade's
`start`/`end` methods accept unwrapped dictionaries and perform the wrapping
natively. iOS controls delivery/coalescing; provider acceptance is not proof that
the widget displayed. See the [native guide](../ios/LIVE_ACTIVITIES.md) for server
endpoints, limits, timestamps, relevance, stale/dismissal dates, and routing.

## Device verification

1. Build the app with the widget embedded and configure its matching APNs provider.
2. Verify capabilities with Live Activities disabled/enabled in system settings.
3. Obtain a fresh grant and start locally. Check the widget and state stream.
4. Remote update, then remote end; verify content, stream removal, and server results.
5. Remote start with the app backgrounded; verify native update-token registration.
6. Terminate/reopen the app with an active activity; update/end from the server.
7. Switch accounts: previous activities disappear from the facade and cannot be
   ended through it. Existing OS UI may remain; account switches do not promise
   to recall already accepted pushes or end another account's ActivityKit UI.
8. Test invalid/expired grants, unknown activity IDs, JSON validation, and Android
   capability/unsupported responses. Repeat through TestFlight/production APNs.

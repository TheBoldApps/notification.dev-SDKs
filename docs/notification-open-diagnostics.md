# Trace a missing notification tap

Use one QA delivery. Record project/campaign/subscriber, platform, app version
and build, native SDK version, OS version, exact UTC time, and app state
(foreground, background, terminated).

## Interpret the evidence

- Provider acceptance means APNs/FCM accepted the request. It does not prove display.
- A receipt means the SDK observed the notification. iOS foreground presentation
  is observed; OS background display does not guarantee an SDK receipt callback.
- `pendingOpens` is the local navigation queue, separate from analytics upload.
  Capture identifiers before acknowledgment. Acknowledgment removes navigation
  work only; it does not acknowledge API acceptance.
- `handleResponse` returning `true` means SDK notification ownership, not successful
  analytics queuing or API acceptance.
- Correlate `opened:<deliveryId>` with its individual `/operations` result.
  HTTP 200 is insufficient: check `results[].operationId`, `succeeded`, `code`
  and `retryable`. Then verify persistence and campaign/Journey attribution.
  An `app_opened` or paywall event cannot substitute for a notification tap.

## Capture SDK diagnostics

SDK 0.2.0 exposes live diagnostics and replayable `pendingOpens` natively. Check
the shipped wrapper version for its API. There is no remote debugging endpoint,
retrospective diagnostics archive, or analytics queue export. An installed build
needs existing capture instrumentation to export diagnostics. Compatible Expo
OTA updates may add JavaScript listeners under the app's update policy. Native
callback instrumentation or changed native startup settings require a rebuild
unless already present.

Attach listeners before the controlled send and before acknowledging navigation.
This Expo example assumes `capture` is the app's existing QA-only, bounded sink:

```ts
const record = (kind: string, details: unknown) =>
  capture({ utc: new Date().toISOString(), kind, details });

const subscriptions = [
  sdk.addListener("diagnostics", ({ code }) => record("diagnostic", { code })),
  sdk.addListener("states", (s) => record("state", {
    installationId: s.installationId, associationId: s.associationId,
    identityChangePending: s.identityChangePending, availability: s.availability,
    pushOptedIn: s.pushOptedIn, syncing: s.syncing,
    hasPendingChanges: s.hasPendingChanges, lastSyncedAt: s.lastSyncedAt,
    lastErrorCode: s.lastError?.code,
  })),
  sdk.addListener("pendingOpens", (pending) => record("pendingOpens",
    pending.map(({ interactionId, openedAt, notification: n }) => ({
      interactionId, openedAt, deliveryId: n.deliveryId,
      associationId: n.associationId, version: n.version, expiresAt: n.expiresAt,
    })))),
];

// Stop only after the controlled trace finishes.
const stopCapture = () => subscriptions.forEach((s) => s.remove());
```

`hasPendingChanges` covers all SDK work, not one specific upload. An empty
`pendingOpens` snapshot can mean the app already acknowledged navigation.
Live diagnostics cannot reconstruct errors from before subscription.

In an app-owned native response callback, record UTC, application state, action
identifier, envelope presence/version, delivery ID, association ID and expiry.
Forward the original response as required by the integration. Record whether
navigation came from `pendingOpens` or another deep-link handler. Do not reset
SDK storage or log out during the trace, since this changes identity evidence.

If upload instrumentation is available, capture HTTP status and the matching
individual result. The API operator correlates the delivery/installation,
operation result, persisted interaction and campaign/Journey metrics. Never log
authorization headers, installation credentials, push tokens, registration
secrets, full payloads, tags or paywall contents.

## Retry behavior and new diagnostics

In SDK 0.2.0, network/retryable failures retain the analytics operation. A permanent
rejection emits its error code and removes that operation. Identity changes can
discard previous-user work with `STALE_ASSOCIATION`; retention filtering reports
`EVENT_DISCARDED`. Storage failures report `STORAGE_FAILURE`. Diagnostics require
an active observer. Navigation may remain pending after a permanent rejection.

The native 0.3.1 reliability patch prioritizes opens over custom events within the
existing queue limits, reporting `EVENT_DISCARDED` when making room. If capacity
cannot be freed safely, `QUEUE_FULL` is reported while display/navigation remain
available. A local open rejection reports `NOTIFICATION_OPEN_` followed by
`UNSUPPORTED_VERSION`, `CREDENTIAL_REVOKED`, `IDENTITY_PENDING`, `OPTED_OUT`,
`EXPIRED`, or `ASSOCIATION_MISMATCH`. Replaced iOS delegate ownership reports
`NOTIFICATION_DELEGATE_REPLACED`; fix provider initialization order or explicitly
forward SDK responses. These new diagnostics cannot be recovered retrospectively
from an installed SDK 0.2.0. Backend deployment does not update native SDKs.

The native patch requires an app rebuild with the updated native SDK. Expo and
Flutter 0.3.0 pin native 0.3.0 and do not receive this patch automatically. Their
updated packages must be published with available native dependencies before
customers can upgrade through those wrappers.

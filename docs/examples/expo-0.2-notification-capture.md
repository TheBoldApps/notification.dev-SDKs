# Passive capture with Expo wrapper 0.2.0

Copy [the TypeScript example](expo-0.2-notification-capture.ts) into the app.
It uses APIs exposed by the published Expo wrapper 0.2.0. It does not upgrade
the native SDK, install a notification delegate, or send telemetry to a server.

Reuse your existing bounded, sanitized QA journal. Attach once immediately after
the shared SDK initializes, before the app's navigation observer acknowledges an
open. Capture initialization rejection codes in the app's existing startup
error handler, since an observer cannot capture a failure before it attaches.

```ts
import { NotificationDev } from '@notification.dev/expo';
import { attachNotificationCapture } from './expo-0.2-notification-capture';

const sdk = await NotificationDev.initialize();
const capture = attachNotificationCapture(sdk, async (entry) => {
  await qaJournal.append(entry);
});
```

Keep the observers attached for the entire test. Existing navigation handling
stays in its current owner. At QA export, include `capture.captureFailed` so
missing records are visible. Only after testing finishes, remove these capture
observers:

```ts
capture.stop();
```

`qaJournal` is the app's existing bounded storage/export mechanism, not an SDK
API. The example catches synchronous and asynchronous journal failures so a
logging failure cannot interrupt the SDK's other observers. `captureFailed`
indicates that a journal write failed; the example does not add durable storage.
Wait for the app journal's own persistence flush before exporting or closing it.

Entries include selected state fields, diagnostic codes and pending-open
identifiers only. They omit the user object, external user ID, tags, email,
tokens, credentials, notification content/data and deep links. Application build,
binary provenance, OS/platform and test-case identifiers come from the app's own
QA record. Keep capture output restricted to the agreed support investigation.

`observedAtUtc` is JavaScript observation time. `openedAt` is the SDK's recorded
open time; neither proves native callback-entry time. No native callback action,
delegate identity, analytics queue count/bytes or per-upload result is exposed.
Those require existing native instrumentation or a diagnostic native build,
instrumenting inside the existing callback without replacing its delegate.

`states` and `pendingOpens` replay current snapshots. Diagnostics are live only;
acknowledged opens and rotated journal records cannot be recovered by attaching
this observer later. An empty queue or a cleared `lastError` does not rule out
an earlier failure. Observe a fresh, non-expired delivery and correlate its ID
with individual API acceptance, persistence and campaign counters.

To add this code to an installed build, use the app's already configured,
runtime-compatible JavaScript update mechanism or a new app build. SDK 0.2.0
has no remote switch to inject these listeners into an uninstrumented binary.

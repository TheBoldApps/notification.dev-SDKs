# randoms Live Activity server

A Python 3 server that starts a widget named **randoms** through notification.dev,
then sends a random integer from 0 to 999,999 every two seconds. It uses the
server-to-server REST API with a project API key. No third-party Python packages
or APNs keys are needed in this process; notification.dev owns APNs delivery.

Use the iOS example widget from this branch. It displays
`DefaultLiveActivityAttributes.data.name` as the title and
`ContentState.data.number` as the number. The example enables frequent Live
Activity updates. iOS may still throttle/coalesce pushes; the two-second cadence
is the server's send interval, not a guaranteed screen refresh interval. This
demo uses APNs priority 10 for immediate delivery.

## Run

1. Run the backend API/worker and iOS example. Configure the SDK with the project
   and APNs provider, call `setupDefault`, and allow Live Activities on the phone.
2. Create a project API key with `messages:send`. Keep it on your server.
3. Create an ignored, owner-only `.env.randoms.json` in this directory:

```json
{
  "base_url": "http://127.0.0.1:53158",
  "project_id": "YOUR_PROJECT_UUID",
  "provider_id": "YOUR_APNS_PROVIDER_UUID",
  "subscriber_id": "THE_SIMULATOR_SUBSCRIBER_UUID",
  "api_key": "YOUR_SERVER_API_KEY"
}
```

The subscriber ID identifies the SDK subscriber, not the installation or
association. The app must already have uploaded its push-to-start token.

```sh
chmod 600 .env.randoms.json
python3 server.py --config .env.randoms.json
```

Alternatively supply `NOTIFICATION_DEV_BASE_URL`, `NOTIFICATION_DEV_PROJECT_ID`,
`NOTIFICATION_DEV_PROVIDER_ID`, `NOTIFICATION_DEV_SUBSCRIBER_ID`, and
`NOTIFICATION_DEV_API_KEY` as environment variables. Environment variables take
precedence over the configuration file. HTTPS is required except on loopback.

Status is available at <http://127.0.0.1:8765/status>. It includes the latest
number, sequence, accepted request count, and error code. It never includes the
API key. Accepted means queued by notification.dev; use the backend's delivery
reports and the visible widget to verify actual delivery.

`--port` changes the loopback status port; `--interval` changes the send interval.
Stop with Ctrl+C or SIGTERM. Stopping leaves the activity showing its last value;
it does not send an end event. Restarting creates a new unique activity ID, since
ended IDs cannot be reused. Pass `--activity-id <active-id>` to resume sending to
an existing activity without starting another widget. Activities have the backend's eight-hour lifetime.

Transient HTTP/network failures retry the same body and idempotency key before
sending a newer value. Missed ticks are skipped, so no stale backlog accumulates.
Terminal failures stop sending. An unresolved start failure also stops instead
of automatically starting another activity with a different request key.

## Tests

```sh
python3 -m unittest discover -v
```

Tests use local HTTP servers and do not contact APNs or notification.dev.

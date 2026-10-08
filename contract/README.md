# SDK HTTP contract

[openapi.json](openapi.json) is the SDK-only snapshot exported from the backend. It contains native SDK operations and their referenced schemas. Fixtures: [state](state.example.json) and [notification](notification.example.json).

## Protocol

- Registration returns an installation credential and state. Subsequent calls use bearer authentication; mutations use `Idempotency-Key` and association IDs where specified.
- State reads and user writes return a unified snapshot. Tags/email belong to the subscriber; push state belongs to the installation.
- Login uses an unverified external ID. Logout creates a new anonymous association. Stale-association mutations are rejected.
- Network failures, HTTP 408/429, and server errors retry with backoff. Event retries retain their IDs and original occurrence time.

## Push payloads

FCM uses a data-only message: `data.notification_dev` is the serialized notification fixture. Do not include an FCM `notification` block.

APNs uses `aps.alert` plus a `notification_dev` JSON object. Background alerts can appear before SDK validation; receipt reporting covers SDK-observed messages.

## API generation

Gradle generates internal Kotlin APIs/models from this snapshot; builds need no backend checkout or Node.js. Generated sources are not committed.

Maintainers update backend schemas and generate the backend contract first. From the backend checkout, export to this repository (replace the path):

```sh
pnpm export:sdk /path/to/notification.dev-SDKs/contract/openapi.json
pnpm export:sdk /path/to/notification.dev-SDKs/contract/openapi.json --check
```

Commit the snapshot with matching SDK changes; do not edit it manually. From the SDK repository root:

```sh
./gradlew :core:generateSdkApi
```

# Changelog

## 0.2.0

- Breaking: replace `allowLocalhostHttp` with `allowHttp`, defaulting to false. Explicit opt-in now permits HTTP for any host, including private LAN addresses.
- Update native Android and Swift dependencies to 0.2.0.
- Android cleartext and iOS App Transport Security configuration remain the host app's responsibility. Old persisted startup configuration is not migrated.

## 0.1.0

- Thin Flutter Android/iOS bridge with full user API, typed state and notification streams, permissions, durable opens, and automatic native startup/callback integration.
- Android integration using Maven Central and iOS Swift Package Manager integration using the released native SDK, plus a runnable example.

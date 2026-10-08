# Architecture

- **Core:** Kotlin Multiplatform engine for identity, local edits, encrypted-state serialization, HTTP, retries, event queues, and notification eligibility. Internal clients/models are generated from the committed OpenAPI snapshot.
- **Android:** Firebase Messaging, permissions, lifecycle, WorkManager wakeups, notification display/taps, and Keystore-backed storage.
- **iOS:** Swift facade over the static core XCFramework, direct APNs delegate forwarding, Keychain-backed storage, foreground timers, and bounded UIKit background time.
- **Flutter:** Typed Dart API and streams over native SDKs, with automatic startup/callback integration. No separate Dart state engine or background isolate.

The core owns persisted retry deadlines. Platform adapters provide execution opportunities; pending work resumes when the OS permits. See the [contract](contract/README.md) and [platform guides](README.md).

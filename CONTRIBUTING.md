# Contributing

Open an issue with reproduction steps, platform/tool versions, and sanitized logs. For security vulnerabilities, follow [SECURITY.md](SECURITY.md).

Keep pull requests focused and include a short description and verification results. Follow the existing code style and add regression tests for behavior changes.

Run the relevant checks in the [development guide](README.md#development), [iOS guide](ios/README.md#verify), or [Flutter guide](flutter/README.md#verify). Real push-delivery checks need a disposable project and configured providers.

Update documentation when behavior changes. Refresh the [OpenAPI snapshot](contract/README.md#api-generation) through the backend exporter; do not edit generated contracts or Kotlin sources manually.

Keep credentials, local configuration, and build outputs out of commits.

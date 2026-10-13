# Contributing

Open an issue with reproduction steps, platform/tool versions, and sanitized logs. For security vulnerabilities, follow [SECURITY.md](SECURITY.md).

Keep pull requests focused and include a short description and verification results. Follow the existing code style and add regression tests for behavior changes.

Run the relevant checks in the [development guide](README.md#development), [iOS guide](ios/README.md#verify), or [Flutter guide](flutter/README.md#verify). Real push-delivery checks need a disposable project and configured providers.

Update documentation when behavior changes. Refresh the [OpenAPI snapshot](contract/README.md#api-generation) through the backend exporter; do not edit generated contracts or Kotlin sources manually.

Keep credentials, local configuration, and build outputs out of commits.

## MCP integration guides

Maintain `docs/integration/{android,ios,flutter,expo}.md` alongside SDK changes.
Use absolute links so the guides work when read through MCP. Keep release setup
and API examples consistent with the platform READMEs and runnable examples.

The product backend bundles these guides. From its `apps/app/backend` directory:

```sh
pnpm import:sdk-guides /path/to/notification.dev-SDKs
pnpm import:sdk-guides /path/to/notification.dev-SDKs --check
pnpm test:sdk-guides
```

Commit guide updates in this repository and the refreshed bundle in the product
repository together. Normal product builds need only the committed bundle.

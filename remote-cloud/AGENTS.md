# Remote Cloud Knowledge

## Overview

Cloudflare Worker plus Durable Object session broker and static browser client. TypeScript is the source of truth; JavaScript counterparts are present in the checkout and must be kept consistent when the established workflow requires it.

## Where To Look

- `src/worker.ts`: Worker routes, static assets, and Durable Object binding.
- `src/session-broker.ts`: participant lifecycle, controller lease, heartbeat, command and signal relay.
- `src/protocol.ts`: Zod message schemas and emitted event contract.
- `static/app.js`, `static/app.css`, `static/index.html`: browser control and media UI.
- `tests/broker-core.test.ts`: behavioral contract for authorization, sequencing, leases, and signaling.
- `wrangler.jsonc`: local/deploy bindings; never add secrets here.

## Commands

```powershell
pnpm install
pnpm typecheck
pnpm test
pnpm build
pnpm dev --local
```

`build` performs TypeScript compilation and Wrangler dry-run validation. `deploy` is an external mutation and requires deliberate account/secret selection.

## Conventions And Safety

- Keep the browser client version independent from the Android APK version. For every user-visible change to `static/index.html`, `static/app.css`, or `static/app.js`, increment the patch version in `package.json` and synchronize the visible `WEB vX.Y.Z` label and page description in `static/index.html`.
- Keep control authorization and sequence monotonicity independent from WebRTC signaling.
- Preserve the one-host/one-controller lease and camera read-only role model.
- Keep session secrets, account IDs, API tokens, TURN credentials, and provisioning credentials out of source, static assets, and `wrangler.jsonc`.
- Preserve the 60-minute session default and heartbeat expiry unless the product policy explicitly changes.

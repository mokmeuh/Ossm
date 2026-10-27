# OSSM Remote Cloud 2.0

Cloudflare Worker and Durable Object broker for OSSM Remote sessions. It owns the authenticated WSS control plane; WebRTC/Cloudflare Realtime signaling is relayed but intentionally has no account-specific implementation or embedded credential.

## Guarantees

- A session lasts 60 minutes by default and its URL is `/s/<sessionId>#k=<secret>`.
- Each session admits exactly one host, exactly one controller lease, and up to four `camera` participants. Cameras cannot issue commands.
- A controller command is relayed to the host only when its sequence is strictly greater than the preceding sequence from that controller.
- All participants receive `emergency_stop`; a caller needs to be host or controller and must use a newer sequence.
- The browser sends a heartbeat every 500 ms. The Durable Object removes a participant after 1.5 s without one.
- Media signaling uses typed `signal` messages and cannot gate controls. The mobile UI requests camera/microphone only after **Join Call** is pressed; host video begins muted.

## Local Run

Requirements: Node.js 24+ and pnpm. No Cloudflare login is required for the local Worker.

```powershell
Set-Location 'C:\Users\mikki\Documents\Ossm\remote-cloud'; pnpm install; pnpm dev --local
```

In a second terminal, create a local 60-minute session. `local-admin-token` is only a local development token; use a secret in deployment.

```powershell
Invoke-RestMethod -Method Post -Uri 'http://localhost:8787/api/sessions' -Headers @{ Authorization = 'Bearer local-admin-token' } -ContentType 'application/json' -Body '{}'
```

Open the returned `url` in a browser. The response also carries an additive `cameraUrl` (`/s/<id>?role=camera#k=<cameraSecret>`): its camera-only key admits the `camera` role and nothing else, and is meant for the phone owner to share or embed. It contains the browser-only fragment key and is not sent as part of normal HTTP requests. A second browser opened on the same URL will be rejected as a second controller; use a host or camera client that sends its chosen role in its initial WSS `hello` message.

## Tests And Build

```powershell
Set-Location 'C:\Users\mikki\Documents\Ossm\remote-cloud'; pnpm typecheck; pnpm test; pnpm build
```

`pnpm build` uses Wrangler's dry-run deploy validation, so it does not deploy or require account credentials.

## Deploy

1. Create a Cloudflare Worker project and bind a Durable Object by retaining `wrangler.jsonc`.
2. Set the provisioning credential, never commit it:

```powershell
Set-Location 'C:\Users\mikki\Documents\Ossm\remote-cloud'; pnpm exec wrangler secret put ADMIN_TOKEN
```

3. Optionally set `SESSION_TTL_SECONDS` in `wrangler.jsonc`; production default is `3600` and should remain 60 minutes unless the safety policy changes.
4. Deploy only after selecting the intended Cloudflare account:

```powershell
Set-Location 'C:\Users\mikki\Documents\Ossm\remote-cloud'; pnpm exec wrangler deploy
```

The browser client is static Worker asset content. For Cloudflare Realtime/WebRTC, configure provider credentials as Worker secrets and add an adapter that exchanges SDP/ICE through existing `signal` messages. Do not put account IDs, API tokens, TURN credentials, or session secrets in `static/`, source control, or `wrangler.jsonc`.

## Protocol

Client-to-broker JSON messages are `hello`, `heartbeat`, `command`, `emergency_stop`, and `signal`. The Zod schema in `src/protocol.ts` rejects malformed messages. The server emits `ready`, `presence`, `lease_lost`, `command`, `emergency_stop`, `signal`, and typed `error` events. See the tests for the behavioral contract.

The host also publishes `limits` (`speedMax`, `depthMin`, `depthMax`, and optionally `sensationMin` + `sensationMax` together, or the legacy `sensationMax` alone; all fractions 0..1). Sensation is bipolar with 0.5 as neutral; the broker relays and replays the last `limits` to the controller exactly as published.

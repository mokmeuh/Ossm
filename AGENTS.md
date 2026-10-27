# OSSM PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-06
**Commit:** 5dd682a0
**Branch:** codex/ossm-rust-firmware-plan

## OVERVIEW

Hybrid OSSM repository: Android Kotlin/Compose client, ESP32 PlatformIO firmware, Cloudflare Worker/Durable Object remote broker, and local PowerShell/Node tooling. The checkout is intentionally dirty; preserve unrelated user changes.

## STRUCTURE

```text
app/                    Android client and unit tests
firmware/               ESP32 firmware; read firmware/AGENTS.md first
remote-cloud/           Cloudflare Worker broker and browser client
tools/                  Local APK/update/remote-control helpers
docs/                   Operational notes
```

## WHERE TO LOOK

| Task | Location | Notes |
|---|---|---|
| Android UI, BLE, sessions | `app/src/main/java/com/ossm/remote/` | `app/AGENTS.md` is authoritative for client conventions and safety. |
| Android tests/build | `app/src/test/`, root Gradle files | Run one Gradle build at a time. |
| Firmware behavior/protocol | `firmware/Software/src/` | Read `firmware/AGENTS.md` and `firmware/Software/CLAUDE.md`; preserve hardware gates. |
| Remote web sessions | `remote-cloud/src/`, `remote-cloud/static/` | Child guide documents Worker protocol and local validation. |
| Android remote relay | `tools/remote-control/` | Loopback relay, ADB forwarding, optional temporary tunnel. |
| APK update serving | `tools/update-server/` | Existing child guide covers secure binding and manifest rules. |

## CODE MAP

| Surface | Entry point | Contract |
|---|---|---|
| Android | `app/src/main/java/com/ossm/remote/MainActivity.kt` | Compose navigation and app shell |
| Android BLE | `app/src/main/java/com/ossm/remote/ble/BleManager.kt` | Existing text protocol and serialized writes |
| Android state | `app/src/main/java/com/ossm/remote/session/ControlSessionStore.kt` | Shared state used by app and overlay |
| Firmware | `firmware/Software/src/main.cpp` | PlatformIO/Arduino startup and tasks |
| Firmware commands | `firmware/Software/src/command/commands.hpp` | BLE command parsing |
| Cloud broker | `remote-cloud/src/worker.ts` | Worker routes and Durable Object binding |
| Cloud protocol | `remote-cloud/src/protocol.ts` | Zod-validated client/server messages |

## CONVENTIONS

- Scope is separated by subsystem; do not move protocol or safety logic into UI-only helpers.
- `remote-cloud` contains both TypeScript sources and checked-in JavaScript counterparts; follow the existing package scripts and do not casually regenerate unrelated files.
- Root instructions are additive: child `AGENTS.md` files override only their own domain and must not duplicate parent rules.

## ANTI-PATTERNS (THIS PROJECT)

- Never reset, clean, stash, rebase, or auto-commit this dirty checkout.
- Never modify or delete originals under capture/evidence areas; extract copies only when requested.
- Never invent BLE commands such as `set:depthMin` or `set:depthMax`.
- Never bypass firmware homing, speed-knob, keepout, disconnect-ramp, or update verification gates.
- Never commit credentials, tokens, WiFi passwords, local build products, or generated secrets.
- FunScript, Video Sync, Adaptive, listening mode, USB firmware flashing and the local Remote web server were removed in 2.3.13; do not reintroduce them unless the user asks.

## COMMANDS

```powershell
./gradlew testDebugUnitTest
./gradlew installDebug
./gradlew assembleNamedDebugApk
Set-Location .\remote-cloud; pnpm typecheck; pnpm test; pnpm build
Set-Location .\firmware\Software; pio test -e test
```

## NOTES

- The protected Android validation target is `SM-S938W - 16`; select an ADB serial explicitly when more than one target is present.
- `installDebug` must remain usable on the detected Samsung device. Do not install or flash hardware as part of documentation-only work.
- Codegraph was attempted during generation but failed with an internal `unresolved_refs` SQLite error; LSP reported no active clients. Directory decisions therefore rely on local files and existing subsystem guides.

## CONTEXT AND TEAM WORKFLOW

- For project work, inspect the current checkout, child guides, `.omo/plans/`, `.omo/evidence/`, and relevant prior Codex sessions before splitting the problem.
- Use at most **five delegated agents at once**. `/root` remains the leader and owns synthesis, integration, and final QA.
- Split by concrete subsystem or evidence lens; each agent receives only its scoped guide and task, with no full-history fork. Store read-only findings in the active team `artifacts/` directory.
- Treat old plans and session notes as leads, not truth: classify every claim as implemented, host-validated, device-validated, planned, partial, or stale against the current checkout.
- Keep one final validation pass proportional to the changed surface, then archive the team state; never leave an abandoned active roster.

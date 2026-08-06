# local-network-updater - Work Plan

## TL;DR (For humans)
<!-- Fill this LAST, after the detailed plan below is written, so it summarizes the REAL plan. -->
<!-- Plain English for a non-engineer: NO file paths, NO todo numbers, NO wave/agent/tool names. -->

**What you'll get:** The app can periodically find a PC update server on the local network, show an optional update, verify the downloaded APK, and hand it to Android's installer. The control screen also gets sensation +/- controls and a solid, clear mode picker.

**Why this approach:** mDNS makes normal LAN use automatic, while a manual address works on restrictive networks. APK checksum plus Android's same-signer install check protects the update path without requiring ADB.

**What it will NOT do:** No silent APK installation, cloud hosting, background update download, or firmware update is included.

**Effort:** Large
**Risk:** Medium - Android installer restrictions and LAN discovery vary by device/network.
**Decisions to sanity-check:** Updates are limited to a local PC, mDNS is primary with manual fallback, and Android always asks before installation.

Your next move: execute via `$start-work local-network-updater`. Full execution detail follows below.

---

> TL;DR (machine): Large, medium-risk LAN updater plus the remaining requested control UI changes.

## Scope
### Must have
- Keep the completed depth-range GATT serialization and its regression test.
- Add +/- to centered Sensation using the same press-and-hold behavior as other sliders.
- Add listening targets for speed, depth, and sensation; the last selected sensation side drives the single firmware scalar.
- Make the pattern picker opaque, edge-to-edge within the control surface, and give it a visible close icon.
- Add a Windows-PC runnable local update server advertising `_ossm-update._tcp.` with `update.json` and the APK.
- Add Android mDNS discovery, persisted manual URL fallback, 12-hour startup throttle, a manual check button in Reglages, checksum verification, and user-approved installation.
### Must NOT have (guardrails, anti-slop, scope boundaries)
- No automatic/silent install, no APK without a newer versionCode, no cloud endpoint, no weakening of APK signing checks, and no deletion of existing unrelated worktree changes.
- No claim that the one-scalar firmware can drive Aller and Retour independently at the same time.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD with JUnit/MockK/coroutines tests; direct server protocol tests; ADB install and device launch.
- Evidence: `.omo/evidence/<task>-local-network-updater.md`.

## Execution strategy
### Parallel execution waves
- Wave 1: updater protocol/server contract, Android updater domain/settings, and control UI primitives.
- Wave 2: installer/activity/UI integration and integration tests.
- Wave 3: build, ADB install, real local-server update check, and visual QA.

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | none | 4, 7 | 2, 3 |
| 2 | none | 5, 6, 7 | 1, 3 |
| 3 | none | 6, 7 | 1, 2 |
| 4 | 1 | 6, 7 | 5 |
| 5 | 2 | 6, 7 | 4 |
| 6 | 3, 4, 5 | 7 | none |
| 7 | 1-6 | none | none |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [ ] 1. Lock the depth-range BLE pair and preserve its test
  What to do / Must NOT do: Keep `liveSetDepthRange()` as the only live depth/stroke dispatch path; preserve depth-before-stroke, 40 ms spacing, and conflation. Do not remove the safety cap or invent physical calibration mapping without real-device evidence.
  Parallelization: Wave 1 | Blocked by: none | Blocks: 4, 7
  References (executor has NO interview context - be exhaustive): `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt:setDepthLive`; `app/src/main/java/com/ossm/remote/ble/BleManager.kt:liveSetDepthRange`; `app/src/test/java/com/ossm/remote/viewmodel/ControlViewModelTest.kt`.
  Acceptance criteria (agent-executable): The unit test verifies one `liveSetDepthRange(80, 60)` call and no direct `liveSet(depth|stroke)` calls.
  QA scenarios (name the exact tool + invocation): happy `testDebugUnitTest --tests ControlViewModelTest`; failure assertion with reverted VM delegation; Evidence `.omo/evidence/1-local-network-updater.md`.
  Commit: Y | fix(ble): serialize live depth range writes
- [ ] 2. Add press-and-hold +/- to the centered Sensation slider
  What to do / Must NOT do: Reuse the existing slider step/ramp behavior for signed `-1..1` sensation and preserve the existing state/callback mapping. Do not duplicate firmware mapping logic in Compose.
  Parallelization: Wave 1 | Blocked by: none | Blocks: 5, 7
  References (executor has NO interview context - be exhaustive): `app/src/main/java/com/ossm/remote/ui/screens/ControlScreen.kt:CenteredSensationSlider`; `app/src/main/java/com/ossm/remote/ui/components/ControlSlider.kt:RampButton`; `MainActivity.kt:onSensationCommit`; `ControlViewModel.kt:requestSensationSignedChange`.
  Acceptance criteria (agent-executable): Minus/plus adjust sensation by 1% steps from the latest signed value, clamp at -100/100%, and call live/commit callbacks.
  QA scenarios (name the exact tool + invocation): Compose/unit callback test happy at zero and boundaries; device screenshot after ADB install; Evidence `.omo/evidence/2-local-network-updater.md`.
  Commit: Y | feat(control): add sensation step buttons
- [ ] 3. Redesign the pattern picker surface
  What to do / Must NOT do: Replace the translucent card treatment with an opaque full-width control surface, close on selection, and use a large icon close affordance. Update `DESIGN.md` to match the final list/grid behavior.
  Parallelization: Wave 1 | Blocked by: none | Blocks: 6, 7
  References (executor has NO interview context - be exhaustive): `ControlScreen.kt:PatternPickerOverlay`; `ControlScreen.kt:PatternOpenerButton`; `ui/components/GlassCard.kt`; `DESIGN.md:23`; `res/values/strings.xml` and `values-en/strings.xml` picker strings.
  Acceptance criteria (agent-executable): The overlay is opaque, spans the content width, can be dismissed by a visible icon, and selecting a pattern closes it.
  QA scenarios (name the exact tool + invocation): device screenshot in open/closed states; content description assertion; Evidence `.omo/evidence/3-local-network-updater.md`.
  Commit: Y | feat(control): improve pattern picker
- [ ] 4. Create the local update-server contract and PC launcher
  What to do / Must NOT do: Add a small PC-side server that advertises `_ossm-update._tcp.`, serves `update.json` plus a named APK, generates SHA-256, and has a documented Windows launcher. Do not expose it outside the LAN or make it modify Android code signing.
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 6, 7
  References (executor has NO interview context - be exhaustive): `app/build.gradle.kts:assembleNamedDebugApk`; `app/build/outputs/named-apk`; `wifi_connect.bat`; `documentation.txt`; user decision: mDNS primary + manual fallback.
  Acceptance criteria (agent-executable): A local request receives a typed manifest containing versionCode, versionName, APK path, SHA-256, and release notes; missing APK returns a clear non-200 response.
  QA scenarios (name the exact tool + invocation): start server, fetch manifest and APK, then run missing-artifact failure case; Evidence `.omo/evidence/4-local-network-updater.md`.
  Commit: Y | feat(updater): add local update server
- [ ] 5. Add Android updater domain, settings, and discovery
  What to do / Must NOT do: Add typed manifest parsing, mDNS discovery, manual base-URL fallback, 12-hour persisted throttle, and non-intrusive unavailable state. Do not rely on a hard-coded PC address or surface unavailable-server errors during normal startup.
  Parallelization: Wave 1 | Blocked by: 2 | Blocks: 6, 7
  References (executor has NO interview context - be exhaustive): `data/repository/ControlSafetySettingsRepository.kt`; `data/repository/UserHabitsRepository.kt`; `VideoSyncViewModel.kt:loadFunscriptFromUrl`; `OssmApplication.kt`; `MainActivity.kt`; Android NSD and FileProvider official docs.
  Acceptance criteria (agent-executable): A newer valid manifest produces update-available state; same/older version, invalid JSON/checksum, and missing server do not prompt or crash; manual check bypasses only the time throttle.
  QA scenarios (name the exact tool + invocation): repository tests with local HTTP fake for newer/same/invalid/unreachable manifests; Evidence `.omo/evidence/5-local-network-updater.md`.
  Commit: Y | feat(updater): discover and check local updates
- [ ] 6. Integrate the optional installer and settings UI
  What to do / Must NOT do: Download to app cache, verify SHA-256 before prompt, expose only via FileProvider, launch Android's installer, and add server/edit/check-now controls in Reglages. Do not silently install or bypass unknown-sources settings.
  Parallelization: Wave 2 | Blocked by: 3, 4, 5 | Blocks: 7
  References (executor has NO interview context - be exhaustive): `AndroidManifest.xml`; `MainActivity.kt:OssmApp`; `ui/screens/ProfilesScreen.kt`; `ui/navigation/NavGraph.kt`; `res/values/strings.xml`; official Android FileProvider and package installer docs.
  Acceptance criteria (agent-executable): User can edit/reset manual server URL, tap check-now, see version/release notes, decline safely, or invoke the Android installer only after a verified APK is staged.
  QA scenarios (name the exact tool + invocation): Android intent/unit tests for checksum mismatch and FileProvider URI; ADB manual test with update server available and unavailable; Evidence `.omo/evidence/6-local-network-updater.md`.
  Commit: Y | feat(updater): offer verified local installs
- [ ] 7. Validate the complete Android build and device paths
  What to do / Must NOT do: Build a named APK, install it via the paired Moto G Wi-Fi ADB target, verify launch and the updater unavailable/available states, and visually validate the control changes. Do not alter unrelated user worktree files.
  Parallelization: Wave 3 | Blocked by: 1-6 | Blocks: none
  References (executor has NO interview context - be exhaustive): `app/build.gradle.kts`; `gradle/wrapper/gradle-wrapper.properties`; paired ADB target `10.215.214.164:38201`; `AGENTS.md` verification guidance.
  Acceptance criteria (agent-executable): `testDebugUnitTest`, `assembleNamedDebugApk`, and ADB install pass; app is resumed; a server-present check reaches the installer prompt and a server-absent check stays quiet.
  QA scenarios (name the exact tool + invocation): Gradle unit/build commands, `adb install`, `adb shell dumpsys activity activities`, and device screenshots; Evidence `.omo/evidence/7-local-network-updater.md`.
  Commit: N | verification only

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [ ] F1. Plan compliance audit - verify every requested capability and guardrail has an implemented artifact and evidence.
- [ ] F2. Code quality review - inspect updater parsing, installer URI permissions, GATT ordering, and Compose accessibility.
- [ ] F3. Real manual QA - run local server, check/update through Moto G Wi-Fi ADB, and capture control UI states.
- [ ] F4. Scope fidelity - reject cloud update, silent install, signing bypass, and unrelated PhoneScript changes.

## Commit strategy
- Keep depth fix/test, control UI, local server, Android updater, and final verification as separate atomic commits. Do not commit `.debug-journal.md`, SDK paths, local properties, or credentials.

## Success criteria
- A compatible newer signed APK can be discovered on LAN or via manual URL, checksum-verified, and offered through Android's installer.
- No-server, malformed manifest, checksum mismatch, same-version, and user-decline paths are safe and non-intrusive.
- Sensation has visible `-` and `+` controls; pattern picker is opaque/full-width with a prominent close action.
- Existing depth live updates stay ordered and unit-tested.

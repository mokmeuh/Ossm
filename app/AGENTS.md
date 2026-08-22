# Android App Knowledge

## Overview
- Single Gradle module `:app`, namespace `com.ossm.remote`, Kotlin 2.0.21, Compose, Hilt, Room, WorkManager, DataStore and Media3.
- Entry points: `OssmApplication`, `MainActivity`, and the special-use foreground `OverlayService`.

## Where To Look
- BLE boundary: `src/main/java/com/ossm/remote/ble/BleManager.kt`, `BleConstants.kt`, `StreamingBehavior.kt`.
- Shared control state: `src/main/java/com/ossm/remote/session/ControlSessionStore.kt`. `ControlViewModel` is a delegate; the overlay uses the store directly.
- UI shell: `MainActivity.kt`, `ui/navigation/NavGraph.kt`, `ui/screens/ControlScreen.kt`.
- Firmware counterpart: `../firmware/Software/src/command/commands.hpp`, `../firmware/Software/src/ossm/OSSM.cpp`, and `../documentation.txt`.

## Conventions
- Java/Kotlin target is 17; SDK is compile/target 35, min 26. Formatting follows `kotlin.code.style=official`; no Android lint formatter is configured.
- Increment `versionCode` for every delivered APK. Use a patch bump for minor rebuilds (`1.5` to `1.5.1`) and a minor bump for major feature releases (`1.5.x` to `1.6`).
- French is the default resource language in `src/main/res/values/`; keep `values-en/` synchronized for new strings.
- Navigation is a sealed-screen registry plus `HorizontalPager`; do not introduce a second navigation model casually.
- Hilt WorkManager coupling is intentional: manifest removes WorkManager auto-init and `OssmApplication` supplies `HiltWorkerFactory`.
- Room database name is `ossm_db` and currently uses destructive migration fallback; schema changes can erase local data.

## BLE Safety Rules
- Use only the existing text protocol. Do not send `set:depthMin` or `set:depthMax`; translate ranges to `stroke = max - min`, `depth = max`.
- Mode entry must remain sequential and state-verified: configure depth before stroke, verify notifications, and preserve the `streamingReady` gate.
- Live mapping is the device-tested inverse (`100 - raw`, clamped to 2..98); latency compensation stays off for touch Live. Require a new filmed device test before changing either.
- Suppress identical consecutive stream positions. Preserve the speed-knob-limit reset and per-pattern speed-zero safety behavior.
- Do not add concurrent GATT writers. Android does not provide a reliable application-level GATT write queue; serialize mode-entry writes and avoid overlapping control jobs.

## Tests And Commands
```bash
./gradlew testDebugUnitTest
./gradlew installDebug
./gradlew assembleNamedDebugApk
```
- Unit tests use JUnit4, MockK, Turbine, coroutines-test and Robolectric; fixtures are normally in code, not `src/test/resources`.
- The protected device gate is `SM-S938W - 16`; verify hardware claims with the device, not only host tests.
- Run one Gradle build at a time. Do not claim physical BLE behavior when no device is attached.

## Hotspots
- `ControlScreen.kt`, `ControlSessionStore.kt`, and `BleManager.kt` are oversized/high-centrality files. Make the smallest seam-level change and extend adjacent tests before refactoring.

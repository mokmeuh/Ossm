# Android App Knowledge

## Overview
- Single Gradle module `:app`, namespace `com.ossm.remote`, Kotlin 2.3.21 (AGP 8.9.3, Gradle 8.11.1, KSP 2.3.4, Hilt 2.58, Room 2.7.2, build-tools 36.0.0), Compose, Hilt, Room and DataStore.
- Entry points: `OssmApplication`, `MainActivity`, and the special-use foreground `OverlayService`.

## Where To Look
- BLE boundary: `src/main/java/com/ossm/remote/ble/BleManager.kt`, `BleConstants.kt`, `StreamingBehavior.kt`.
- Shared control state: `src/main/java/com/ossm/remote/session/ControlSessionStore.kt`. `ControlViewModel` is a delegate; the overlay uses the store directly.
- UI shell: `MainActivity.kt`, `ui/navigation/NavGraph.kt`, `ui/screens/ControlScreen.kt`.
- Local APK OTA: `updater/LocalOtaTransfer.kt`, `updater/LocalOtaViewModel.kt`, and the OTA section in `ui/screens/ProfilesScreen.kt`; either phone may send or receive over the same Wi-Fi.
- Firmware counterpart: `../firmware/Software/src/command/commands.hpp`, `../firmware/Software/src/ossm/OSSM.cpp`, and `../documentation.txt`.

## Conventions
- Java/Kotlin target is 17; SDK is compile/target 35, min 26. Formatting follows `kotlin.code.style=official`; no Android lint formatter is configured.
- Increment `versionCode` for every delivered APK. Use a patch bump for minor rebuilds (`1.5` to `1.5.1`) and a minor bump for major feature releases (`1.5.x` to `1.6`).
- French is the default resource language in `src/main/res/values/`; keep `values-en/` synchronized for new strings.
- Navigation is a sealed-screen registry plus `HorizontalPager`; do not introduce a second navigation model casually.
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
- Local OTA keeps a short-lived authenticated receiver, package/version/signature checks, and final Android installer confirmation. Quick Share is not the primary update path.

## Hotspots
- `ControlScreen.kt`, `ControlSessionStore.kt`, and `BleManager.kt` are oversized/high-centrality files. Make the smallest seam-level change and extend adjacent tests before refactoring.

## Removed In 2.3.13
- Funscript, Video Sync, Adaptive mode, listening (microphone) mode, periodic update checks, USB-C firmware flashing and the local Remote web server were deleted. Do not reintroduce them without an explicit user request. The Internet remote session (`remote/RemoteSession*`) and local phone-to-phone OTA remain.

## Naturel (2.3.18)
- Mode key `natural` (`PatternControlMode.NATURAL`, Teasing Pounding id 1). Pure core in `natural/` (`NaturalGestures.kt`: generator, shaper, engine); Store section "Naturel" drives it through `liveSet`/`liveSetDepthRange` only, after a verified speed-0 entry and an explicit start (local button or browser `set_speed` > 0).
- Optional Ollama planner is off by default, address user-entered (private/Tailscale only), and only replaces future gestures; any failure falls back to the local generator.
- 2.3.20: AI in two steps. "Charger Qwen3" pre-loads the model (single POST /api/generate with empty prompt, keep_alive 15m, 300 s budget; read-only /api/ps every 15 s, then every 30 s while loaded). The pre-load is never cancelled by STOP/pause/mode exit, only by AI off or address change. Plans are requested only when "Utiliser l'IA" is on AND the model is loaded; a plan cancelled by STOP is not a failure. Causes are logged under tag NATURAL_AI without URL or content.
- 2.3.23: optional "Passer par le relais de raisonnement (PC)" (off by default, DataStore `natural_ai_reasoning_relay`): same host, port 11435 (`../tools/ollama-reasoning-relay`), plans with think:true and up to 6 phrases. AI look-ahead is adaptive (`NaturalAiPacing`): next plan requested when queued gesture time < 2 x recent max latency + 10 s (15..240 s), one request at a time; later AI plans are appended, the first replaces the local future. The plan envelope parser ignores `thinking`.

## Local OTA (2.3.25, protocol 2 since 2.3.32)
- Receiver binds 0.0.0.0 on port 47474 (ephemeral fallback), NSD TXT `package/vc/vn/dev/inst/proto=2/pk` (public key only, no token); the sender ignores its own `inst`.
- 2.3.32 replaces the 6-digit code: receiver shows a QR `ossm-ota://pair?h=<ip1>,<ip2>&p=<port>&k=<pk b64url>&v=<vc>&n=<dev>` (ZXing core, `ui/components/QrCodeImage.kt`); sender "Scanner le QR" (zxing-android-embedded `ScanContract`, CaptureActivity `fullSensor` in the manifest), "Envoyer à une adresse" (no code) or discovery. All three run the same handshake.
- Handshake (`updater/LocalOtaCrypto.kt`, `LocalOtaReceiverServer.kt`, `LocalOtaSenderClient.kt`, pure JVM, tested over real sockets): `POST /ossm-ota/hello` {c=SHA256(pkS,nS) commitment, vc, vn, name, pkg} -> {pk, n, sid, vc, vn, name} (409 not_newer/busy, 422 wrong_package); `POST /ossm-ota/confirm` {sid, pk, n} long poll (60 s, closed connection = cancel) -> {accepted, reason}; `POST /ossm-ota/upload` + `X-OSSM-Session` + `X-OSSM-Mac` (HMAC-SHA256 of sid+body, key = SHA256 of ECDH secret + transcript). ECDH P-256 (X25519 needs API 33). Number = SHA256(secret, pkS, pkR, nS, nR) mod 100, shown on both phones; receiver asks Oui/Non. QR/TXT key is pinned (mismatch = blocked). Package/signer/versionCode re-checked on the file; Android installer confirmation unchanged.
- 2.3.25..2.3.31 receivers capped bodies at 64 MiB while the APK is ~77 MB: every upload got 400 "Bad request". Limit is now 256 MiB; early errors half-close and drain so the sender reads the status. Senders map 400/404 on hello to "version trop ancienne" (update once by ADB or APK share); old senders get 426.
## Voix (2.3.26, base)
- Pattern key `voice` (`KnownVoicePattern`, mode STREAMING, phone-only). Pure model in `voice/VoiceSteps.kt` (VoiceStep, VoiceStepValidator, VoicePlanner, VoiceCommandInterpreter), `voice/VoiceAudioSource.kt` (AudioRecord 16 kHz, unwired), executor `session/VoiceSequenceRunner.kt`; Store section "Voix" (`voiceExecuteSteps`, `voiceStopNow`, `interpretVoiceInput`). No SpeechRecognizer, no grammar, no network: the on-device model (AI Edge/Gemma) is a later step.

## Voix IA sur l'appareil (2.3.27)
- 100 % on the phone, no network: `AudioRecordVoiceAudioSource` 16 kHz -> Silero VAD -> Parakeet TDT v3 int8 (sherpa-onnx `OfflineRecognizer`, `nemo_transducer`, one decode per VAD segment) -> STOP keyword (`voice/VoiceStopKeywords.kt`, before any LLM) -> Gemma 3 1B int4 (LiteRT-LM 0.18.0, GPU then CPU) -> `VoiceLlmJsonParser` (fences tolerated, strict JSON/integers) -> store `VoiceStepValidator` -> `VoiceSequenceRunner`.
- Pure, JVM-tested: `voice/VoiceAiPipeline.kt` (`SpeechTranscriber`, `VoiceStepGenerator`, `OnDeviceVoiceInterpreter`), `VoiceLlmJsonParser.kt`, `VoiceLlmPrompt.kt`, `VoiceModelFiles.kt`. Native, never loaded by tests: `voice/engine/` (`SherpaSpeech.kt`, `LiteRtStepGenerator.kt`, `VoiceAiController` singleton, `VoiceAiViewModel`).
- Models are read by absolute path (AssetManager null) from `getExternalFilesDir("models")`: `gemma/gemma3-1b-it-int4.litertlm`, `asr/silero_vad.int8.onnx`, `asr/parakeet/{encoder,decoder,joiner}.int8.onnx`, `asr/parakeet/tokens.txt`. Missing -> "modèle manquant" + paths; unreadable (one byte cannot be read) -> "modèle illisible".
- Warm-up starts when Voix is entered; the interpreter is handed to the store and the microphone can start only once everything is loaded. Microphone closes on mode exit and in `MainActivity.onStop`. sherpa-onnx is a local AAR `app/libs/sherpa-onnx-1.13.8.aar` (git-ignored); `abiFilters` = arm64-v8a only.

## Voix 2.3.28
- STOP compact: portrait uses `ui/components/StopBar.kt` (56dp wide bar + 56dp pause, status banner on its own line below); `StopButton` (round) stays for landscape/other screens. No STOP in the Voix panel; header "i" button (`VoiceInfoButton`).
- Voix depth range: `voice/VoiceDepthRange.kt` (pure), DataStore `voice_depth_min/max`, store `setVoiceDepthRange` (cancels a running plan cleanly, re-anchors). Plans/motifs arm the Live speed via `BleManager.armLiveSpeed("voix")` and join the range via `LiveTargetSlew` when needed.
- Continuous motif: `voice/VoiceMotif.kt` (model, validator, linear levels, modulations), runner `submitMotif`/`modulate`, store `voiceExecuteMotif`/`voiceModulate`, parser keys `motif`/`modulation`, few-shots in `VoiceLlmPrompt`.

## Voix 2.3.29
- Producer `LiveTargetProducer.VOICE` (timeline, duration honoured). Runner segments: `SEGMENT_MS` 200, `MAX_STEP` 25, emitted at segment start; motif emits turning points.
- Safety: `ControlSessionStore.onAppBackground()` (MainActivity.onStop), `BleManager.linkSuspectEvents` (missed ping) -> STOP, no resume; `hardwareStop()` + `MainActivity.dispatchKeyEvent` (volume keys, `voice/VoiceSafetyStops.kt`); short-word STOP and `VoiceStopSource` in the same file; wider `VoiceStopKeywords`.
- Parakeet language cannot be forced in sherpa-onnx 1.13.8; KeywordSpotter needs a KWS model (not shipped).

## Voix 2.3.30
- ASR = Whisper small int8 via sherpa-onnx `OfflineWhisperModelConfig` (language "fr", task "transcribe", tailPaddings -1, modelType "whisper", 4 threads, cpu). Pure spec `VoiceAsrConfig.whisper(paths)` (`WhisperAsrSpec`) mapped in `engine/SherpaSpeech.kt` (`SherpaWhisperTranscriber`).
- Files: `asr/whisper/small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt`. `AsrEngineSelector`: Parakeet only when NO Whisper file exists; a partial Whisper install stays Whisper and reports the missing file. Panel line "Transcription : Whisper small (français)" / "Parakeet (langue non forcée)".
- VAD segments capped at `VoiceAsrConfig.MAX_SEGMENT_SECONDS` (10 s); latency = VAD segment delivered -> text. Safety order unchanged (short-word STOP before ASR, keyword STOP before LLM, volume keys, no auto-resume).

## 2.3.31
- Voix: `voice/VoiceRuleParser.kt` (pure; `VoiceRuleContext` from `ControlSessionStore.voiceRuleContext()`: start position, motif running, last accepted plan for "encore") runs before Gemma in `OnDeviceVoiceInterpreter`; null = not certain -> Gemma. Motif values equal the Gemma few-shots (a test enforces it). `voice/VoiceTranscriptGuard.kt` ignores hallucinations/low energy AFTER the STOP keyword check. Telemetry `onPlanned(..., source)` / `onIgnored(reason)`; `VoiceAiUiState.interpretSource/lastIgnoredReason`.
- Remote: `RemoteSessionClient` reconnects transient transport losses (`RemoteReconnectPolicy`, 1/2/4/8/15 s, 20 attempts) without `beginSession`/`endSession` (envelope kept, STOP via `safety.onTransportLost()`); internal constructor takes the delay function for tests.
- Cleanup: Media3/WorkManager/hilt-work removed from `libs.versions.toml`; 36 orphan string keys removed (FR+EN); `RandomModeRow`, `WarningScreen.kt` and unused `ControlScreen` params (`onStreamPosition`, `onRangeWizardStart`, `onHome`) deleted; remaining hard-coded French UI text in `ControlScreen` moved to resources.

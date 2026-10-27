# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Lire en premier
- Répondre en français, simplement. Avant toute opération Git qui modifie l'historique, les branches ou supprime quelque chose : expliquer et attendre l'accord.
- `docs/reprise/*.md` : résumés importés des conversations Chat (décisions, ce qui est confirmé sur l'appareil, ce qui reste à faire). C'est l'état le plus récent du travail.
- `AGENTS.md` (racine) puis le guide du sous-projet touché : `app/AGENTS.md`, `firmware/AGENTS.md` + `firmware/Software/CLAUDE.md`, `remote-cloud/AGENTS.md`. Ils font foi pour les conventions ; ce fichier ne les répète pas.
- `HANDOFF_CLAUDE.md`, `.debug-journal*.md` et `docs/LOCAL_UPDATES.md` sont historiques (v1.x, juin à août 2026) : pistes, pas vérité. Funscript, Video Sync, Adaptatif, mode écoute et le serveur web local ont été retirés en 2.3.13 ; ne pas les réintroduire sans demande.

## Dépôt
Trois projets dans un même dépôt :
- `app/` : appli Android OSSM Remote (Kotlin, Compose, Hilt, Room, DataStore), module Gradle unique `:app`, package `com.ossm.remote`. Version dans `app/build.gradle.kts` (`versionName`/`versionCode`, à incrémenter pour chaque APK livré).
- `firmware/Software/` : firmware ESP32 (PlatformIO). Le binaire livré dans l'appli est `app/src/main/assets/firmware/ossm-1.2.0.bin`.
- `remote-cloud/` : broker de session à distance (Cloudflare Worker + Durable Object) et client web statique, versionné à part (`package.json`).
- `tools/ollama-reasoning-relay/` : relais PC (port 11435) pour le planificateur IA optionnel du mode Naturel.
- `Marche/` est un projet d'escalier sans rapport, volontairement non suivi par Git.

## Commandes

Build Android sous Windows : `local.properties` contient `sdk.dir=/home/mikki/AndroidSdk` (chemin Linux). Avant Gradle, le remplacer par le SDK Windows (`%LOCALAPPDATA%\Android\Sdk`, avec des `/`), puis restaurer depuis `local.properties.bak`.

```powershell
.\gradlew.bat testDebugUnitTest                                   # tous les tests JVM
.\gradlew.bat testDebugUnitTest --tests '*LocalOtaHandshakeTest'  # un seul test
.\gradlew.bat testDebugUnitTest --tests '*Live*' --tests '*StringsParityTest' --tests '*LocalOta*' assembleDebug -x lint
.\gradlew.bat assembleNamedDebugApk   # app\build\outputs\named-apk\OSSM-Remote-v<version>-debug.apk
.\gradlew.bat installDebug            # package com.ossm.remote.debug
```
- Un seul build Gradle à la fois.
- `build_install.bat` choisit l'appareil via `adb_target.ps1` (USB puis Wi-Fi). `install.bat` pointe vers un APK figé en 2.3.10 : ne pas s'y fier.
- Téléphones : S25 Ultra (`SM-S938W`, appareil de validation) et Moto G, en ADB Wi-Fi/Tailscale. Toujours passer `-s <serial>` quand plusieurs sont connectés. Ne pas affirmer un comportement BLE/machine sans test sur l'appareil.

```powershell
# Firmware, depuis firmware\Software
$env:RELEASE_BUILD_SHA=(git rev-parse --short HEAD); pio run -e development
pio test -e test
# Remote cloud, depuis remote-cloud
pnpm typecheck; pnpm test; pnpm build
```

## Architecture de l'appli
- `ble/BleManager.kt` est la seule frontière vers la machine : protocole texte, écritures GATT sérialisées (pas d'écrivain concurrent). `BleConstants.kt` contient les UUID GATT ; les chaînes de commande (`set:…`, `go:…`) sont construites dans `BleManager.kt`. Contrepartie firmware : `firmware/Software/src/command/commands.hpp`.
- `session/ControlSessionStore.kt` est l'état de contrôle partagé (singleton Hilt) : modes, Live, Naturel, Voix, sécurité STOP. `ControlViewModel` lui délègue ; `overlay/OverlayService` (service de premier plan) l'utilise directement. Toute logique de sécurité ou de protocole va dans le Store ou `ble/`, jamais dans l'UI.
- Navigation : registre d'écrans scellé + `HorizontalPager` (`ui/navigation/NavGraph.kt`, `MainActivity.kt`). Pas de second modèle de navigation.
- Modes : `natural/` (gestes générés, planificateur Ollama optionnel), `voice/` (pipeline 100 % sur le téléphone : VAD, Whisper/Parakeet via sherpa-onnx, Gemma via LiteRT-LM ; modèles lus depuis `getExternalFilesDir("models")`, AAR `app/libs/sherpa-onnx-*.aar` non suivi), `remote/` (session Internet via `remote-cloud`), `updater/` (OTA locale téléphone à téléphone, QR + ECDH), `usb/` (flash firmware ESP32 par OTG).
- Le cœur de ces modes est en Kotlin pur testé sur JVM ; les parties natives (`voice/engine/`) ne sont jamais chargées par les tests.
- `ControlScreen.kt`, `ControlSessionStore.kt` et `BleManager.kt` sont très gros : faire le plus petit changement possible et étendre les tests voisins avant de refactorer.

## Règles qui ne se devinent pas
- Pas de `set:depthMin` / `set:depthMax` : une plage `min/max` devient `stroke = max - min`, `depth = max`. Configurer depth avant stroke et garder la porte `streamingReady`.
- Live : mapping et vitesses confirmés sur l'appareil (voir `app/AGENTS.md` et `docs/reprise/`) ; ne pas les changer sans nouveau test filmé.
- Le STOP BLE de l'appli est envoyé comme `set:speed:0`.
- Chaînes : FR par défaut (`res/values/`), EN dans `res/values-en/` ; `StringsParityTest` échoue si l'une manque. Fichiers en UTF-8 sans BOM.
- Room (`ossm_db`) utilise la migration destructive : un changement de schéma efface les données locales.
- Aucune mention d'IA, de nom ou d'initiales dans le code.

## Git
- Branche de travail : `ossm/v2.3.42` (locale, non poussée). `main` est en retard. Les branches `claude/*` et `codex/*` restantes datent de juillet et ne servent plus.
- Ne jamais faire `reset`, `clean`, `stash`, `rebase` ni de commit automatique. Ne pas pousser sans demande.
- Ne pas commiter `Marche/`, `local.properties*`, les modèles, ni les adresses IP des téléphones.

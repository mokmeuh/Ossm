# Handoff pour Claude Code — bug « mode Live » + feature vidéo/funscript

Ce fichier est fait pour être **collé/référencé dans une nouvelle conversation Claude Code**
ouverte à la racine du repo. Il décrit (1) comment builder/installer, (2) le bug prioritaire
du mode Live, (3) la nouvelle feature vidéo à construire.

## Repo & build

- Repo : `https://github.com/mokmeuh/Ossm` — branche `main`.
- Doc protocole OFFICIELLE incluse dans le repo : **`documentation.txt`** (racine) — BLE, streaming,
  patterns, GPIO. C'est la source de vérité du protocole.
- SDK Android : `local.properties` pointe déjà `sdk.dir`. Build + install :
  ```
  .\gradlew.bat assembleDebug
  adb install -r app\build\outputs\apk\debug\app-debug.apk
  ```
- Fichiers .bat utilitaires à la racine : `build.bat`, `install.bat`, `build_install.bat`,
  `commit.bat`, `push.bat`, `all.bat` (bump+build+install+commit+push).
- Convention : bump `versionCode`/`versionName` dans `app/build.gradle.kts` entre chaque build.

## BUG PRIORITAIRE — mode Live : l'actuateur reste/va au HOME

### Symptôme (rapporté sur appareil, plusieurs versions)
Quand l'utilisateur monte le slider/pad Live vers 100 %, **l'actuateur va (ou reste) au HOME**
au lieu d'aller au FOND. Persiste malgré plusieurs corrections de sens.

### Déjà essayé (sans succès confirmé)
- Inversion du mapping `mapLiveStreamPositionPercent` (slider 100 → `stream:2`) — `StreamingBehavior.kt`.
- Ajout d'un interrupteur ON-DEVICE « Inverser le sens du Live » sous le pad (écran Contrôle) —
  permet de basculer sans rebuild. **À vérifier : est-ce que basculer cet interrupteur change QUELQUE
  CHOSE ?** Si NON → ce n'est pas un problème de sens, mais de commandes non exécutées (voir ci-dessous).

### Hypothèse forte à investiguer EN PREMIER
Le fait que les DEUX sens donnent « home » suggère que les commandes `stream:` **ne s'exécutent pas**,
donc la machine reste à sa position de repos (home). Pistes dans le code :
- `BleManager.sendCommand()` : `if (!_streamingReady.value) return` — si l'entrée streaming n'est jamais
  « vérifiée » (bande `stroke=100/depth=100`), AUCUN `stream:` n'est envoyé.
- `triggerStreamingEntry()` / `bandVerified()` dans `BleManager.kt` : la vérif peut échouer
  silencieusement (voir logs « Bande jamais confirmée »).
- Gate vitesse : le firmware ignore `stream:pos:time` si `speed=0`. Voir `writeSpeedKnobIndependent()`
  et `set:speed:100` à l'entrée.

### Diagnostic à faire (NE PAS deviner — mesurer)
1. Écran **Diagnostics** de l'app : affiche chaque `stream:X:Y` envoyé + l'état machine (JSON `position`,
   `stroke`, `depth`, `speed`, `state`).
2. `adb logcat -s OSSM` : l'app miroite les logs BLE, dont chaque `stream:pos:time`.
3. En Live, monter le slider à 100 % et noter : (a) `streaming prêt` apparaît-il ? (b) quel `stream:X:Y`
   est envoyé ? (c) quelle `position` la machine renvoie-t-elle ?
   - Si aucun `stream:` n'est envoyé → bug d'entrée streaming (`streamingReady` reste faux).
   - Si `stream:` envoyé mais position ne bouge pas → gate vitesse / firmware.
   - Si position bouge dans le mauvais sens → vrai problème de mapping (utiliser l'interrupteur).

### Fichiers clés
- `app/src/main/java/com/ossm/remote/ble/StreamingBehavior.kt` (mapping + latency)
- `app/src/main/java/com/ossm/remote/ble/BleManager.kt` (sendCommand, triggerStreamingEntry, sendStreamingSetup, bandVerified)
- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt` (ticker streaming ~ligne 1049+, constantes STREAM_* ~1415+)
- `app/src/main/java/com/ossm/remote/ui/components/LiveStreamPad.kt` (pad : doigt haut = logicalPos 100)
- `app/src/main/java/com/ossm/remote/ui/screens/DiagnosticsScreen.kt`

## FEATURE — aligner l'actuateur sur une vidéo (funscript) jouée dans l'app

### Objectif
Jouer une vidéo DANS l'app (depuis un fichier local OU un lien) et piloter l'actuateur en synchro,
le **traitement fait localement sur le téléphone** (pas de serveur).

### Ce qui existe déjà (à réutiliser)
- Lecture funscript : `model/FunscriptAction.kt`, `viewmodel/FunscriptViewModel.kt`, `ui/screens/FunscriptScreen.kt`.
- Streaming BLE `stream:pos:time` (le transport de synchro est déjà là).

### MVP recommandé (fiable, livrable)
1. Lecteur vidéo in-app avec **Media3/ExoPlayer** (fichier local via picker, ou URL).
2. Charger un **funscript** associé (fichier `.funscript` local ou URL) — format JSON `{actions:[{at,pos}]}`.
3. Synchroniser : à chaque position de lecture de la vidéo, envoyer le `stream:pos:time` correspondant
   (interpoler entre points funscript). Réutiliser `FunscriptViewModel`.
4. Afficher la vidéo + overlay de contrôle (play/pause, offset de latence).

### Étape ultérieure (recherche, NON MVP)
Génération AUTOMATIQUE du mouvement depuis la vidéo, sur l'appareil (analyse d'image/flux optique/IA).
Lourd et approximatif — à traiter comme R&D séparée, pas dans la 1re version.

### Décision à confirmer avec l'utilisateur
- Source vidéo : lien web, fichier local, ou les deux ?
- Mouvement : funscript existant (recommandé) vs génération auto on-device (gros chantier) vs
  enregistrement manuel au doigt (l'enregistreur Live existe déjà).

## Prompt prêt-à-coller pour Claude Code
> Lis `HANDOFF_CLAUDE_CODE.md` et `documentation.txt` à la racine. Corrige d'abord le bug « mode Live :
> actuateur reste au home » en suivant la section Diagnostic (mesure via Diagnostics + `adb logcat -s OSSM`
> AVANT de changer le mapping). Ensuite, commence le MVP vidéo/funscript décrit. Build + installe via adb
> et bump la version entre chaque essai.

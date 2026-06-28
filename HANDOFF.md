# Handoff Codex - 2026-06-22

## Reprise du contexte
Ce clone ne contenait pas initialement `TASK.md`, `HANDOFF.md`, `CLAUDE.md` ni `AGENTS.md`.

La reprise Codex a ete reconstruite a partir de:
- `README.md`
- `git status`
- les logs de build locaux (`build-out.log`, `compile-err.log`, etc.)
- lecture ciblee de:
  - `MainActivity.kt`
  - `BleManager.kt`
  - `ControlViewModel.kt`
  - `ControlScreen.kt`
  - `ProfilesScreen.kt`
  - `ControlCommand.kt`
  - `Pattern.kt`
  - `Preset.kt`
  - `PresetEntity.kt`
  - `ControlViewModelTest.kt`
- et de deux threads Codex fournis ensuite par l'utilisateur:
  - `019e90dd-21fe-7620-83fc-bfa1d156c7bc`
  - `019e8a07-05c1-7a11-97d7-b59597395e79`

## Ce qui etait vrai avant les changements
- L'app Android native compilait et s'installait deja.
- Le controle etait modele comme `4 sliders + 3 patterns factices`.
- `strokeEngine` etait force au branchement BLE.
- Les patterns etaient locaux et inventes.
- La profondeur etait representee de facon trompeuse avec un `100 %` qui ne correspondait pas a l'intuition utilisateur.

## Ce que Codex a implemente
- Remplacement du modele de patterns factices par un modele `pattern actif` dynamique.
- `strokeEngine` devient un pattern reel connu localement.
- Ajout d'une liste de patterns disponible cote `BleManager` avec tentative de lecture BLE si la caracteristique existe.
- `ControlUiState` remodelise autour de:
  - `availablePatterns`
  - `activePatternKey`
  - `speed`
  - `depthMin`
  - `depthMax`
  - garde anti-saut
- La garde anti-saut est persistante via DataStore.
- Les presets sont migres vers:
  - `patternKey`
  - `patternName`
  - `speed`
  - `depthMin`
  - `depthMax`
- L'ecran Controle affiche maintenant:
  - une section patterns dynamique
  - un mode `strokeEngine` avec `Vitesse` + plage `Profondeur`
  - un popup de confirmation pour les gros sauts
  - un switch global de securite
- Les autres patterns sont affiches comme `lancables` sans sliders inventes.
- La base Room est passee en version `2` avec `fallbackToDestructiveMigration()`.

## Confirmation importante venue des anciens threads
Les anciens threads OSSM confirment le protocole BLE texte reel du firmware et evitent une mauvaise derive:
- oui a `go:strokeEngine`
- oui a `set:speed`, `set:stroke`, `set:depth`, `set:sensation`, `set:pattern`
- non confirme pour `set:depthMin` / `set:depthMax`

Codex a donc recale l'implementation:
- l'UI expose `depthMin/depthMax`
- le BLE traduit cette plage en:
  - `stroke = depthMax - depthMin`
  - `depth = depthMax`

## Etat verifie
- `./gradlew.bat testDebugUnitTest` passe
- `./gradlew.bat installDebug` passe
- installation verifiee sur:
  - `SM-S938W - 16`

## Zones encore ouvertes
- La vraie forme du payload `pattern list` n'est pas confirmee dans ce clone.
- La logique de parsing de patterns cote `BleManager` est volontairement defensive et minimale.
- Si le firmware expose une liste plus structuree que le parseur actuel ne comprend pas, il faudra l'ajuster sur preuve reelle.
- Le mode Funscript a ete adapte pour compiler avec le nouveau modele de commande, mais son comportement reel avec `strokeEngine` devra idealement etre revalide sur appareil.

## Point d'attention pour une reprise Claude
- Ne surtout pas reintroduire `Pattern 1/2/3`.
- Ne pas inventer `set:depthMin` / `set:depthMax` tant qu'une preuve firmware n'existe pas.
- Garder la traduction `depthMin/depthMax -> stroke/depth` tant que le protocole reste celui confirme par les threads historiques.

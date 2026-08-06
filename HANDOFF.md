# OSSM Handoff

Date: 2026-08-05

## Message a l'autre AI

Une autre AI travaille deja sur ce depot. Ne pas faire de `reset`, `checkout`,
`clean`, `stash`, rebase ou commit automatique. Le working tree contient un
grand ensemble de changements non commites qui doivent etre preserves.

Ce fichier est une passation d'etat. Il ne constitue pas une validation finale
du code et les observations ci-dessous sont celles faites au moment de sa
creation.

## Ce que Sisyphus a fait

### Configuration OpenCode-Go

- Les modeles OpenCode-Go ont ete verifies avec `opencode models`.
- Les 18 modeles du classement utilisateur etaient disponibles.
- `~/.config/opencode/oh-my-openagent.json` a ete configure selon le classement:
  - GPT-5.6 Luna pour orchestration, raisonnement, architecture et `deep`.
  - Qwen3.8 Max pour Android/Kotlin et implementation.
  - DeepSeek V4 Pro pour le quotidien.
  - GLM-5.2 pour exploration/documentation.
  - Kimi K2.7 Code, Grok-4.5, MiMo et Flash selon les specialisations.
- `~/.config/opencode/opencode.jsonc` utilise maintenant
  `opencode-go/gpt-5.6-luna` comme modele principal et
  `opencode-go/deepseek-v4-flash` comme petit modele.
- Les deux fichiers de configuration ont ete parses avec succes.
- La cle OpenCode-Go a ete testee: API accessible et `opencode run` a retourne
  `SMOKE OK`.

### Reload du TUI

- Avant le redemarrage, `task()` echouait avec `Provided authentication token
  is expired` car le serveur opencode etait ancien.
- L'utilisateur a redemarre le TUI.
- Le smoke test post-redemarrage passe:
  `SMOKE OK - OpenCode-Go task active`.
- Le parent utilisait `opencode-go/gpt-5.6-luna`; la categorie `quick` a utilise
  `opencode-go/deepseek-v4-flash`.

## Ce que l'autre AI a deja modifie

Le `git status` observe contient beaucoup de fichiers modifies, supprimes ou
non suivis. Les groupes principaux observes sont:

### Overlay

- `app/src/main/java/com/ossm/remote/overlay/OverlayService.kt`
- `app/src/main/java/com/ossm/remote/overlay/OverlayUi.kt`
- `app/src/main/java/com/ossm/remote/data/repository/OverlayPreferencesRepository.kt`
- `app/src/main/java/com/ossm/remote/viewmodel/OverlaySettingsViewModel.kt`
- modifications de `MainActivity.kt`, `ProfilesScreen.kt`, `AndroidManifest.xml`
  et des ressources de strings.

### Statistiques et sessions

- `SessionEntity.kt`, `SessionDao.kt`
- `SessionStatsRepository.kt`, `StatsSnapshot.kt`
- `SessionStatsTracker.kt`
- `StatisticsViewModel.kt`, `StatisticsSection.kt`
- `ObservedPositionRange.kt` et leurs tests associes.

### Mode adaptatif

- nouveau package `app/src/main/java/com/ossm/remote/adaptive/`
- `AdaptiveSettingsRepository.kt`
- `AdaptiveViewModel.kt`
- modifications de `Pattern.kt`, `ControlViewModel.kt`, `ControlScreen.kt`,
  `BleManager.kt` et fichiers de tests.

### Updater et infrastructure

- nouveau package `app/src/main/java/com/ossm/remote/updater/`
- modifications de `OssmApplication.kt`, `AppModule.kt`, manifeste et ressources
- dependances WorkManager/Hilt et Robolectric dans Gradle
- modification de `app/build.gradle.kts`

### Version actuellement visible

Dans `app/build.gradle.kts`, l'etat observe contient deja:

- `versionCode = 117`
- `versionName = "1.4"`

Cela reste non valide tant que les tests, le build, l'installation et la QA ne
sont pas refaits sur l'ensemble coherent des changements.

## Etat du working tree

- Nombreux fichiers modifies non commites.
- Nombreux fichiers nouveaux non suivis.
- `LiveStreamPad.kt` apparait supprime dans `git status`.
- Le working tree contient aussi des artefacts de diagnostic, de build, de WiFi
  et de documentation. Ne pas les supprimer sans attribution claire.
- Sisyphus n'a pas modifie le code applicatif apres avoir constate le travail
  concurrent.

## Point exact ou Sisyphus etait rendu

Le plan etait de lancer la Vague 1 overlay par delegation, puis:

1. contre-verifier les fichiers et l'integration;
2. executer les diagnostics LSP et les tests cibles;
3. confirmer `versionCode 117` / `versionName 1.4`;
4. lancer `assembleDebug`;
5. installer en WiFi sur le Moto G Fast avec
   `adb connect 10.86.86.164:40887`;
6. effectuer la QA de l'overlay;
7. poursuivre ensuite les vagues statistiques et adaptatives.

La delegation n'a pas ete relancee apres la decouverte du travail concurrent,
afin de ne pas produire une seconde implementation de l'overlay dans les memes
fichiers.

## Points a verifier avant toute release

Les observations suivantes proviennent d'une lecture ponctuelle et doivent etre
revalidees apres que l'autre AI a fini:

- `OverlayPreferencesRepository` utilise encore une transparence entiere
  `20..100` et un defaut `100`, alors que le contrat cible est une opacite
  `0.35..1.0` avec defaut `0.85`.
- `overlayEnabled` avait un defaut `true` dans le repository, alors que le
  contrat cible est `false`.
- Aucun `PositionSnapper.kt` n'etait visible au moment de l'inspection.
- `MainActivity` demarrait l'overlay via `onStop`; le contrat cible demande
  `onUserLeaveHint` et l'arret lors du retour au premier plan.
- Les chemins BLE doivent rester ceux deja existants; ne pas inventer de
  commandes firmware.
- Le service ne doit pas injecter `ControlViewModel`.
- Les changements de l'autre AI doivent etre testes ensemble, pas fichier par
  fichier en isolation.

## Suite recommandee

1. L'autre AI termine son lot et signale les fichiers qu'elle considere stables.
2. Sisyphus relit tous les fichiers touches et compare le resultat au contrat.
3. LSP et tests unitaires cibles, puis test complet.
4. Build debug unique a la fois pour eviter une corruption KSP.
5. Installation WiFi sur le Moto G Fast et QA reelle.
6. Seulement apres validation, continuer vers la release suivante.

## Regles de coordination

- Ne pas editer un fichier que l'autre AI est en train de modifier.
- Ne pas restaurer les fichiers a partir de Git pour "nettoyer" le depot.
- Ne pas faire de commit tant que les deux AI n'ont pas confirme le perimetre.
- Conserver les fins de ligne existantes, notamment CRLF si presentes.
- Ne pas considerer la presence de `versionCode 117` comme une release valide.

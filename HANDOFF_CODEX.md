# Handoff Codex

## 2026-06-29 03:39:06 - Debut de tache

### Tache
- Installer sur appareil la version courante qui compile apres les changements `AUTO_RANDOM`.
- Changer la convention de handoff pour un journal cumulatif, sans supprimer les taches precedentes.

### Etat au debut
- Repo cible: `C:\Users\mikae\Documents\Ossm\Ossm`
- Device ADB detecte:
  - `ZY227FZL7W`
- Derniere validation connue:
  - `.\gradlew.bat assembleDebug`
  - resultat: `BUILD SUCCESSFUL`

### Intention
- Installer la build actuelle sur le telephone USB.
- Lancer l'application.
- Ajouter le resultat a la suite de ce handoff plutot que creer un handoff isole.

## 2026-06-29 03:39:06 - Fin de tache

### Resultat
- `.\gradlew.bat installDebug`
  - resultat: `BUILD SUCCESSFUL`
  - APK installee sur 1 appareil
- lancement effectue:
  - `adb -s ZY227FZL7W shell am start -n com.ossm.remote.debug/com.ossm.remote.MainActivity`

### Etat final
- La version courante du repo est installee sur le telephone USB `ZY227FZL7W`.
- L'application a ete lancee.
- Le handoff utilise maintenant un journal cumulatif unique:
  - `C:\Users\mikae\Documents\Ossm\Ossm\HANDOFF_CODEX.md`

### Regle pour la suite
- Ajouter chaque nouvelle tache a la suite dans ce fichier.
- Ne pas supprimer les taches precedentes.

## 2026-06-29 03:43:43 - Debut de tache

### Tache
- Rendre `AUTO_RANDOM` plus ajustable:
  - intensite reglable
  - randomness reglable
- Faire en sorte que ces sliders influencent reellement le moteur de mix.

### Etat au debut
- `AUTO_RANDOM` compile et l'app a deja une UI de base.
- Le moteur actuel monte son intensite automatiquement mais sans reglages utilisateur dedies.

## 2026-06-29 03:43:43 - Fin de tache

### Changements appliques
- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt`
  - ajout de `autoIntensityCap`
  - ajout de `autoRandomness`
  - ajout des setters:
    - `setAutoIntensityCap()`
    - `setAutoRandomness()`
  - le moteur `startAutoMix()` utilise maintenant:
    - `autoIntensityCap` pour plafonner la montee d'intensite
    - `autoRandomness` pour influencer:
      - la probabilite de changer de pattern
      - l'amplitude des variations de vitesse
      - l'amplitude des variations de profondeur
      - l'amplitude des variations de sensation
      - l'ecart entre les temps d'attente

- `app/src/main/java/com/ossm/remote/ui/screens/ControlScreen.kt`
  - ajout du slider `Intensite max`
  - ajout du slider `Randomness`
  - ces sliders sont visibles dans la section `AUTO_RANDOM`

- `app/src/main/java/com/ossm/remote/MainActivity.kt`
  - branchement des nouveaux callbacks:
    - `onAutoIntensityCapChange`
    - `onAutoRandomnessChange`

### Verification
- `.\gradlew.bat assembleDebug`
  - resultat: `BUILD SUCCESSFUL`
- `.\gradlew.bat installDebug`
  - resultat: `BUILD SUCCESSFUL`
  - installe sur 1 appareil
- lancement effectue:
  - `adb -s ZY227FZL7W shell am start -n com.ossm.remote.debug/com.ossm.remote.MainActivity`

### Etat final
- L'app actuellement installee sur `ZY227FZL7W` contient maintenant deux reglages Auto Random supplementaires:
  - `Intensite max`
  - `Randomness`
- Le mix aleatoire est donc plus ajustable qu'avant:
  - plus doux / plus intense

## 2026-07-06 13:30:38 - Essai live direct + diagnostics

### Demande
- Essayer une variante du mode Live ou le firmware recoit des `stream:pos:time` quasi instantanes au lieu d'un temps de trajet pilote par l'app.
- Garder les anciennes branches en commentaire pour rollback rapide si l'essai ne convient pas.
- Ajouter un bouton copier pour les logs Diagnostics et retirer la limite de 500 entrees.

### Changements appliques
- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt`
  - nouveau mode Live d'essai:
    - l'app continue d'interpoler entre les samples du doigt
    - chaque envoi streaming part maintenant avec `timeMs = 1`
    - `liveMaxAccel` sert maintenant a regler la cadence d'envoi et la finesse du seuil d'emission
  - l'ancienne logique de budget de pas / `timeMs` variable a ete laissee en commentaire dans le code, a la demande de l'utilisateur, pour retour arriere rapide
  - la fin de geste Live envoie maintenant directement la derniere cible en `time=1`
- `app/src/main/java/com/ossm/remote/viewmodel/DiagnosticsViewModel.kt`
  - suppression de la limite `takeLast(500)`; l'ecran conserve maintenant tout l'historique collecté pendant la session
- `app/src/main/java/com/ossm/remote/ui/screens/DiagnosticsScreen.kt`
  - ajout d'un bouton copie (`ContentCopy`) qui copie l'integralite des logs visibles au presse-papiers
- `app/src/test/java/com/ossm/remote/viewmodel/ControlViewModelTest.kt`
  - ajout de tests pour:
    - `computeLiveDirectCadenceMs`
    - `computeLiveDirectEpsilon`
- `app/src/test/java/com/ossm/remote/viewmodel/DiagnosticsViewModelTest.kt`
  - ajout d'un test qui verifie que les logs ne sont plus tronques a 500

### Verification
- `.\gradlew.bat testDebugUnitTest`
  - resultat: `BUILD SUCCESSFUL`
- `.\gradlew.bat installDebug`
  - resultat: `BUILD SUCCESSFUL`
  - installe sur `ZY227FZL7W`
- lancement effectue:
  - `adb -s ZY227FZL7W shell am start -n com.ossm.remote.debug/com.ossm.remote.MainActivity`
- verification visuelle minimale:
  - capture de l'app sur appareil: `ossm_diag_trial.png`
  - la barre de navigation du bas reste lisible, sans overlap visible

### Limites / suite utile
- Le ressenti reel du nouveau Live direct ne peut pas etre valide sans test physique du slider.
- Une capture Diagnostics precise n'a pas ete obtenue: un tap adb ulterieur a ouvert la fiche systeme Android au lieu de l'onglet `Diag`.
  - plus stable / plus imprevisible

## 2026-07-06 01:22:12 - Fin de tache

### Tache
- Rendre le mode Live plus rapide et plus fluide entre `0 -> 100`.
- Conserver une vitesse ressentie coherente avec le mouvement du doigt sur le slider.
- Eliminer un overlap visible dans la navigation basse.

### Changements appliques
- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt`
  - remplacement du lissage `ease-out` a gros paliers par une interpolation temporelle du geste entre deux echantillons tactiles
  - cadence Live resserree (`20 ms`) avec pas plus petits et `move time` dynamique
  - plafond de vitesse Live garde, mais converti en budget de deplacement par seconde au lieu d'un gros pas fixe par tick
  - ajout de tests unitaires sur l'interpolation tactile et le budget de pas
- `app/src/main/java/com/ossm/remote/ble/BleManager.kt`
  - plafond `set:speed` du streaming monte de `80` a `100` pour ne plus freiner le chariot sur les grands deplacements
- `app/src/main/java/com/ossm/remote/MainActivity.kt`
  - labels de navigation basse forces sur une seule ligne
- `app/src/main/java/com/ossm/remote/ui/navigation/NavGraph.kt`
  - libelles de navigation raccourcis (`Diag`, `Reglages`) pour eviter les retours a la ligne

### Verification
- `.\gradlew.bat assembleDebug`
  - resultat: `BUILD SUCCESSFUL`
- `.\gradlew.bat testDebugUnitTest`
  - resultat: `BUILD SUCCESSFUL`
- `.\gradlew.bat installDebug`
  - resultat: `BUILD SUCCESSFUL`
  - installe sur `ZY227FZL7W`
- lancement effectue:
  - `adb -s ZY227FZL7W shell am start -n com.ossm.remote.debug/com.ossm.remote.MainActivity`

### Notes
- Deux tentatives de verification ont echoue pour une raison d'outillage seulement:
  - build et tests lances en parallele sur KSP/Hilt
  - caches Kotlin incrementaux corrompus/verrouilles dans `app/build`
- Apres arret des daemons et purge des caches de build, la validation en serie est passee.

## 2026-07-06 13:56:35 - Correctif Live documente

### Tache
- Corriger la regression du mode Live introduite par l'essai `stream:pos:1`.
- Tenir compte de la documentation OSSM BLE sur `stream:<pos>:<time>` et sur la caracteristique de compensation de latence.

### Decision
- La compensation de latence doit rester desactivee (`false`) pour le Live tactile.
- Raison: la doc indique qu'elle ne doit etre activee que si le delai reel entre commandes correspond au `time` envoye; ce n'est pas le cas d'un pilotage au doigt.

### Changements appliques
- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt`
  - retrait du mode Live direct en `timeMs = 1`
  - retour a la logique precedente:
    - interpolation du geste
    - budget de pas borne par `liveMaxAccel`
    - `timeMs` variable coherent avec le deplacement
    - nudge periodique de rattrapage
- `app/src/main/java/com/ossm/remote/ble/BleManager.kt`
  - ajout de la caracteristique BLE `latency compensation`
  - ecriture explicite de `false` pendant le setup streaming
  - conservation du `speed knob` en mode independent (`false`) pour que le bouton physique ne plafonne pas les commandes BLE en streaming
- `app/src/test/java/com/ossm/remote/viewmodel/ControlViewModelTest.kt`
  - suppression des tests lies a l'essai direct retire

### Verification
- `.\gradlew.bat testDebugUnitTest`
  - resultat: `BUILD SUCCESSFUL`
- `.\gradlew.bat installDebug`
  - resultat: `BUILD SUCCESSFUL`
  - installe sur `ZY227FZL7W`

### Git
- L'utilisateur a demande commit + push apres recompilation.
- Etat a verifier apres ce handoff:
  - commit du correctif Live
  - tentative de push sur `origin`

## 2026-07-06 14:17:09 - Rebuild stream pos 5

### Tache
- Rebuild en integrant le concept `stream:pos:5` dans le mode Live.
- Penser a differencier clairement la build par une nouvelle version.

### Changements appliques
- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt`
  - le flux Live tactile envoie maintenant les commandes streaming manuelles avec `timeMs = 5`
  - les rappels de fin de geste Live utilisent eux aussi `timeMs = 5`
  - la logique de lissage/interpolation precedente est conservee
- `app/build.gradle.kts`
  - `versionCode` passe a `96`
  - `versionName` passe a `1.28.4`

### Verification
- `.\gradlew.bat testDebugUnitTest`
  - resultat: `BUILD SUCCESSFUL`
- installation non verifiee ce tour:
  - `adb devices` ne listait aucun appareil branche

### Git
- un commit + push est attendu apres cette recompilation

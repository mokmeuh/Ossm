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
  - plus stable / plus imprevisible

## 2026-07-05 23:35:00 - Fin de tache

### Tache
- Corriger le mode Live apres validation appareil:
  - plus de cogne
  - slider utilisateur a remettre dans le bon sens
  - vitesse max Live a rendre perceptible
  - mouvement a rendre moins saccade

### Changements appliques
- `app/src/main/java/com/ossm/remote/model/LiveStreamMapping.kt`
  - ajout d'un mapping affichage utilisateur -> valeur raw firmware
  - convention utilisateur visee:
    - `0 = home`
    - `100 = fond`

- `app/src/test/java/com/ossm/remote/model/LiveStreamMappingTest.kt`
  - ajout du test de semantique d'affichage Live

- `app/src/main/java/com/ossm/remote/ui/components/LiveStreamPad.kt`
  - le pad garde l'affichage utilisateur `0 = home, 100 = fond`
  - les cibles envoyees au firmware passent maintenant par l'inversion d'affichage

- `app/src/main/java/com/ossm/remote/viewmodel/ControlViewModel.kt`
  - re-synchro du Live sur `raw home = 100`
  - lissage du stream retabli avec easing
  - plafond de pas Live reduit pour que `Acceleration max du Live` ait plus d'effet

- `app/build.gradle.kts`
  - version montee a `1.28.17` (`versionCode 109`)

### Verification
- test cible:
  - `./gradlew testDebugUnitTest --tests com.ossm.remote.model.LiveStreamMappingTest`
  - resultat: `BUILD SUCCESSFUL`
- build:
  - `./gradlew assembleDebug`
  - resultat: `BUILD SUCCESSFUL`
- installation appareil:
  - `adb -s ZY227FZL7W install -r -d app/build/outputs/apk/debug/app-debug.apk`
  - resultat: `Success`
- version confirmee sur appareil:
  - `versionName=1.28.17`
  - `versionCode=109`
- lancement application:
  - `adb -s ZY227FZL7W shell am start -n com.ossm.remote/.MainActivity`
  - ecran controle visible sur appareil

### Point a revalider sur machine
- confirmer physiquement que:
  - `0` va vers `home`
  - `100` va vers le `fond`
  - `Acceleration max du Live` change vraiment la vitesse / douceur
  - le mouvement Live est moins saccade

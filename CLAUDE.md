# Instructions de reprise pour Claude

## Contexte rapide
- Workspace: `C:\Users\mikae\Documents\Ossm\Ossm`
- Projet: app Android native OSSM Remote en Kotlin/Compose/Hilt
- Etat actuel: build debug et install debug passent

## Ce qu'il faut croire en priorite
- Les threads OSSM historiques fournis par l'utilisateur sont plus fiables que l'ancienne UI locale de ce clone.
- `strokeEngine` est un pattern reel parmi d'autres.
- Le protocole BLE texte confirme localement n'inclut pas `set:depthMin` ni `set:depthMax`.
- Pour l'instant, la plage utilisateur `min/max` doit etre convertie en:
  - `stroke = max - min`
  - `depth = max`

## Ce qu'il ne faut pas faker
- Ne pas remettre des patterns generiques `Pattern 1`, `Pattern 2`, `Pattern 3`.
- Ne pas afficher des sliders speculatifs pour les patterns non confirmes.
- Ne pas retransformer `strokeEngine` en mode global implicite.

## Ce que Codex a deja change
- nouveaux modeles `Preset`, `Pattern`, `StrokeEngineCommand`
- nouvelle garde anti-saut via DataStore
- nouvel ecran Controle oriente `pattern actif`
- presets adaptes a `patternKey/patternName + speed + depthMin + depthMax`
- base Room version `2`

## Si tu reprends apres Codex
- verifier d'abord le format reel de la liste de patterns si tu as acces a un appareil/firmware qui la publie
- conserver la garde anti-saut et son popup
- verifier le mode Funscript sur appareil si tu touches encore au modele de commande
- ne pas annuler les changements locaux non lies sans preuve qu'ils bloquent reellement

## Validations minimales a relancer
- `.\gradlew.bat testDebugUnitTest`
- `.\gradlew.bat installDebug`

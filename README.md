# OSSM Remote

Application Android native de contrôle OSSM/OFFM via Bluetooth Low Energy.

## Fonctionnalités

| Écran | Description |
|-------|-------------|
| **Scanner** | Découverte BLE des appareils OSSM/OFFM à proximité |
| **Contrôle** | Sliders vitesse, profondeur, stroke, sensation + patterns 1/2/3 |
| **Funscript** | Lecture de fichiers `.funscript` (JSON) synchronisée |
| **Profils** | Sauvegarde/restauration locale de presets |
| **Diagnostics** | Logs BLE temps réel, UUID GATT, dernière commande envoyée |

### Sécurité
- Bouton **STOP** rouge permanent visible sur l'écran Contrôle
- Arrêt automatique sur déconnexion BLE inattendue
- Debounce 50 ms (commandes trop rapides ignorées)
- Avertissement affiché au premier lancement

---

## Installation

### Prérequis
- Android Studio Hedgehog ou supérieur
- JDK 17
- Android SDK 34
- Appareil physique Android 8.0+ (Bluetooth LE requis — l'émulateur ne supporte pas le BLE réel)

### Build de l'APK debug

```bash
git clone <repo>
cd OssmRemote

# Depuis Android Studio :
#   Build → Build Bundle(s)/APK(s) → Build APK(s)
# ou en ligne de commande :
./gradlew assembleDebug
```

L'APK se trouve dans :
```
app/build/outputs/apk/debug/app-debug.apk
```

### Installation sur l'appareil

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Ou copiez directement le fichier APK sur le téléphone et ouvrez-le (activer "Sources inconnues" requis).

---

## Permissions requises

| Permission | Android | Usage |
|------------|---------|-------|
| `BLUETOOTH_SCAN` | 12+ | Scan BLE |
| `BLUETOOTH_CONNECT` | 12+ | Connexion GATT |
| `BLUETOOTH` + `ACCESS_FINE_LOCATION` | < 12 | Scan BLE legacy |

---

## Protocoles BLE supportés

L'app détecte automatiquement le firmware et s'adapte :

### OSSM natif (ESP32 Arduino)
- Service : `19b10000-e8f2-537e-4f6c-d104768a1214`
- Commandes binaires : `[cmd, speed, depth, stroke, sensation, 0]`

### Nordic UART Service (NUS)
- Service : `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Commandes texte : `spd:75;dep:100;str:80\n`, `stop\n`

---

## Test BLE

1. Allumez votre OSSM/OFFM et assurez-vous que le BLE est actif
2. Ouvrez l'app → acceptez l'avertissement
3. Onglet **Scanner** → "Scanner les appareils BLE"
4. Sélectionnez votre appareil dans la liste (OSSM affiché en vert)
5. L'app se connecte et passe automatiquement à l'onglet Contrôle
6. Utilisez les sliders — les commandes sont envoyées en temps réel

### Vérification via l'onglet Diagnostics
- UUID des services GATT détectés
- Protocol sélectionné (natif / NUS)
- Chaque commande envoyée avec timestamp
- Toutes les erreurs GATT

---

## Format Funscript

Les fichiers `.funscript` sont du JSON standard :

```json
{
  "actions": [
    {"at": 0,    "pos": 0},
    {"at": 1000, "pos": 100},
    {"at": 2000, "pos": 0}
  ]
}
```

- `at` : timestamp en millisecondes
- `pos` : position 0-100

Chargez le fichier depuis l'onglet **Funscript** → icône dossier.

---

## Architecture

```
MVVM + Hilt DI
├── BleManager (Singleton) — gestion GATT, scan, commandes
├── ViewModels — état UI via StateFlow
├── Room — persistence des profils
└── Jetpack Compose — UI réactive
```

---

## Limites connues

- Le BLE Android peut être instable sur certains appareils — reconnectez si nécessaire
- L'API OSSM BLE est expérimentale et peut changer selon la version du firmware
- Les UUID peuvent différer selon la version du firmware OSSM — vérifiez dans l'onglet Diagnostics
- Le mode funscript nécessite une connexion BLE stable pour la synchronisation
- Testé sur Android 12-14 ; comportement variable sur Android 8-11

---

## License

Usage personnel uniquement. Respectez les consignes de sécurité et utilisez cet appareil de manière responsable.

---

## Rebuild & installation (référence rapide)

```bat
cd C:\Users\mikae\Documents\Ossm\Ossm
rebuild_install.bat        :: tests unitaires -> build -> install (log: build_result.log)
```

Ou manuellement :

```bat
.\gradlew.bat testDebugUnitTest    :: tests
.\gradlew.bat installDebug         :: build + install (téléphone branché, débogage USB actif)
```

APK produit : `app\build\outputs\apk\debug\app-debug.apk` (package installé : `com.ossm.remote.debug`).
Vérifier la version installée : `adb shell dumpsys package com.ossm.remote.debug | findstr versionName`.

## Architecture (couches)

```
ui/screens + ui/components   Compose UI (ControlScreen, ScanScreen, ...)
viewmodel/                   État & logique de présentation (ControlViewModel: tickers live,
                             enregistreur de boucle, gardes de sécurité asymétriques)
ble/BleManager               Couche protocole OSSM : commandes texte (set:/go:/stream:),
                             séquençage GATT strict (MTU -> notifications -> config),
                             transitions pilotées par l'état NOTIFY réel + vérifications
model/                       Modèles purs (OssmCommand, MachineState, Pattern, Preset)
data/repository              Persistance (Room presets, DataStore réglages/habitudes/ordre patterns)
```

Points spécifiques Android (à isoler pour un futur port iOS) : `BleManager` (API BluetoothGatt),
DataStore/Room. La logique métier (mapping profondeur/course, enregistreur, gardes) vit dans les
ViewModels et modèles, portables.

### Faits protocole confirmés sur appareil (firmware 1.0.31)
- `stream:100` = home/début, `stream:0` = fond (l'app inverse ; marge 2 % aux deux butées).
- L'état NOTIFY (JSON) exige MTU ≥ ~180 : négocier le MTU AVANT l'abonnement aux notifications.
- `set:speed` BLE est plafonné par le bouton physique par défaut → caractéristique `…1010` à `false`.
- Le firmware réinitialise ses réglages à l'entrée d'un mode → application vérifiée avec réessais.
- Ne jamais envoyer deux `stream:` consécutifs à la même position (crash firmware).

Voir `HANDOFF_CLAUDE.md` pour l'historique détaillé et les chantiers en cours.

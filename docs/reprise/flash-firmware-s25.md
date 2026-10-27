# Reprise OSSM Android (v2.3.42, versionCode 190)

Source : conversation Chat « Flash firmware depuis S25 » (groupe ossm), résumé importé le 2026-10-26.

## Contexte

* Projet : `C:\Users\mikki\Documents\Ossm` (Windows, appli Kotlin dans `app\`, firmware ESP32 dans `firmware\Software`, PlatformIO env `development`).
* Téléphones ADB : Moto G `10.0.0.232:38325`, S25 `100.120.56.115:45089` (Tailscale). La 2.3.42 est installée sur les deux.
* Préférences de Mikael :
  * Réponses en français.
  * Corrections sûres appliquées sans demander.
  * Aucune mention d'IA, de nom ou d'initiales dans le code.
  * Aucun commit fait ; s'il y en a un, ajouter les trailers demandés par le système.

## Décisions prises

* Flash firmware depuis l'appli (Réglages, carte « Firmware de la machine ») : protocole bootloader ESP32 via OTG/CP2102, journal affiché dans la carte, navigation bloquée pendant le flash (`flashHoldsNavigation` dans `MainActivity`).
* Live, géométrie :
  * Zéro fixe en bas du pad (ou sur la ligne de limite basse si elle existe), ancre au centre horizontal. Géométrie radiale conservée (`LiveAnchorState`).
  * Le premier toucher émet la valeur sous le doigt ; l'actuateur le rejoint lentement.
  * Rejoindre le doigt, vers le haut comme vers le bas, se fait à 20 %/s (`LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND`), avec un premier pas de 2 % (`LIVE_JOIN_INITIAL_STEP_PERCENT`).
  * Suivi direct une fois à moins de 5 % du doigt. La descente après relâchement est inchangée (20 %/s).
* Limites haute/basse :
  * Deux boutons dans l'en-tête Live (état dans `LiveLimitsState`, fichier `ui/components/LiveLimits.kt`, icônes dessinées en Canvas).
  * Cycle : absent → ligne déplaçable → verrouillée → retirée.
  * C'est un simple écrêtage du doigt côté appli : rien n'est envoyé à la machine et rien n'est persisté.
  * Un premier toucher hors des lignes ramène le zéro sur la ligne.
* Écran Live nu : uniquement quand Live est actif (`liveBareScreen` dans `ControlScreen`, plus la condition équivalente dans la `bottomBar` de `MainActivity`) :
  * STOP, bouton de rotation, bandeau d'état et barre d'onglets masqués ; marges réduites ; portrait forcé (orientation précédente restaurée à la sortie).
  * On quitte le Live via le bouton main (sélecteur de modes).
* Mise à jour entre deux téléphones :
  * Le QR contient un secret `s` ; l'expéditeur envoie `qs` dans `/confirm`.
  * Le récepteur (`LocalOtaReceiverServer.qrSecret`) accepte seul, sans nombre à 2 chiffres ni Oui/Non.
  * Sans scan (adresse manuelle ou « Partager ma version »), le nombre de vérification reste.

## Confirmé sur l'appareil (par Mikael)

* Flash firmware depuis l'appli : « Firmware installé. La machine redémarre. » (v2.3.40, ~66 s).
* Live en 2.3.41, premier toucher = 0 avec descente graduelle : « très proche de la perfection ».
* Les deux boutons de limite sont visibles dans l'en-tête (photo).

## Compilé et testé en unitaire seulement (non vérifié à l'écran ni sur la machine)

* Tout ce qui est propre à 2.3.42 : zéro fixe en bas, montée lente au premier toucher, écran nu, portrait forcé, comportement réel des lignes de limite.
* Le QR sans nombre de vérification (tests `LocalOtaHandshakeTest` ajoutés : bon secret et mauvais secret).
* Firmware multi-téléphones BLE et correctif paysage (faits plus tôt dans la session, jamais testés par lui).
* Le nouveau firmware qui doit revenir en « USB-C actif » après redémarrage.

## Reste à faire

1. Verrouillage du Live face aux autres connexions (USB + BLE) :
   * Principe : un propriétaire du Live (USB ou une connexion BLE) avec une fenêtre d'activité d'environ 8 s. Les commandes et cibles Live des non-propriétaires sont rejetées.
   * Fichiers firmware (`firmware\Software\src\services\communication\`) : `usb_serial.cpp`, `command.hpp`, `nimble.cpp`, `ble_remote_control.h`, `queue.cpp`, `command\commands.hpp`.
   * Trou actuel : `command.hpp` laisse passer `go:menu` et `set:speed:0` quand l'USB est réclamé ou qu'un autre bail BLE existe.
   * Piège : le STOP BLE de l'appli est envoyé comme `set:speed:0` (`BleManager.kt` ~l. 1294). Il est indiscernable des commandes automatiques envoyées à la connexion (`set:speed:0`, `go:menu`, `go:strokeEngine`, `set:pattern:0`, `set:depth/stroke/sensation`). Il faut un critère distinct ou masquer ces commandes automatiques pour un non-propriétaire.
2. Compiler le firmware : `$env:RELEASE_BUILD_SHA=(git rev-parse --short HEAD); pio run -e development` depuis `firmware\Software`. Copier `.pio\build\development\firmware.bin` vers `app\src\main\assets\firmware\ossm-1.2.0.bin`. Incrémenter la version.
3. Compiler et installer l'APK, puis flasher via l'appli en pilotant par adb : onglet Réglages (~446,1462), « Flasher le firmware inclus », bouton « Flasher » du dialogue. Suivre les logs avec le tag `[FLASH]`.
4. Question en suspens : le message de Mikael s'arrêtait sur « Je veux que… » (après la demande de finesse maximale). Rien n'a été changé pour la finesse : la position envoyée reste un pourcentage entier.
5. Vérifier si Mikael veut aussi retirer STOP hors du Live. Il a écrit « complètement NON nécessaire » après un message ambigu ; seul le Live a été modifié.
6. Mettre à jour les commentaires périmés : l'en-tête de `LiveAnchorPad.kt` et la doc de `LiveAnchorState.onDown` disent encore « ancre = position courante ».

## Pièges

* Build Windows : `local.properties` contient `sdk.dir=/home/mikki/AndroidSdk` (chemin Linux). Avant Gradle, le remplacer par `sdk.dir=<%LOCALAPPDATA%\Android\Sdk avec des />`, puis restaurer depuis `local.properties.bak`. Commande : `.\gradlew.bat testDebugUnitTest --tests '*Live*' --tests '*StringsParityTest' --tests '*LocalOta*' assembleDebug -x lint`. L'APK sort dans `app\build\outputs\apk\debug\app-debug.apk`.
* Chaînes : toute nouvelle chaîne doit exister en FR et EN (`values` et `values-en`), sinon `StringsParityTest` échoue. Écrire les fichiers en UTF-8 sans BOM.
* PowerShell : la console casse les motifs accentués dans les boucles de surveillance. Utiliser des motifs sans accents.
* Outils : `write_file` exige `mode: "rewrite"` pour remplacer un fichier existant. Les paramètres d'`edit_block` ne se contrôlent que par relecture.
* Flash : `SPI_ATTACH` est obligatoire avant `FLASH_BEGIN` (sinon aucune réponse pendant ~58 s). Le port série doit être suspendu (`suspendForFirmwareUpdate`) et restauré après.
* Pad Live : quand le pad est désactivé, les touchers sont maintenant consommés au lieu d'être ignorés. L'écrêtage par les lignes utilise la hauteur du Canvas : ne pas le passer par le Store.
* Alimentation USB : recharger le téléphone pendant la connexion USB hôte nécessite un hub USB-C avec passage de charge PD. Un simple changement de rôle sur le S25 ne marchera pas avec une carte CP2102 ordinaire (elle n'apparaît pas comme source d'alimentation). Aucune solution logicielle.

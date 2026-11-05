# Serveur APK du PC

Permet de mettre à jour l'appli sans ADB : le PC publie le plus récent APK d'un dossier, et le téléphone vient le chercher.

## Côté PC

```powershell
.\Start-OssmApkServer.cmd                                 # dossier par défaut : app\build\outputs\named-apk
.\Start-OssmApkServer.ps1 -Dossier D:\mes-apk            # autre dossier
.\Stop-OssmApkServer.cmd
```

- Écoute sur le port 8091, uniquement sur `127.0.0.1` et l'adresse Tailscale du PC (jamais `0.0.0.0`).
- Le nom des fichiers ne compte pas. La version est lue dans chaque APK avec `aapt2` (SDK Android). Pour chaque package, c'est le `versionCode` le plus haut qui gagne.
- Le dossier est relu à chaque demande, donc un nouvel APK déposé est vu tout de suite, sans redémarrer.
- Lecture seule. Le téléchargement se fait par empreinte SHA-256 (`/apk/<sha256>.apk`), jamais par nom de fichier.

Routes : `/` (liste par package), `/p/<package>/update.json` (manifeste d'un package), `/update.json` (le plus récent, tous packages confondus).

## Firmware de la machine

Le serveur publie aussi l'image firmware de l'OSSM, pour la flasher depuis l'appli sans recompiler l'appli.

```powershell
.\Start-OssmApkServer.ps1 -Firmware D:\fw\mon-firmware.bin    # fichier, ou dossier de .bin
```

- Par défaut : `firmware\Software\.pio\build\development\firmware.bin`. Le nom du fichier n'a pas d'importance.
- Le fichier n'est publié que s'il ressemble à une image d'application ESP32 (octet magique `0xE9`, 1 à 16 segments, puce ESP32, taille entre 32 Ko et `0x1E0000`). Dans un dossier, le `.bin` valide le plus récent est choisi ; `bootloader.bin`, `partitions.bin` et `ota_data_initial.bin` sont trop petits pour passer pour une application.
- Routes : `/firmware/update.json` (empreinte SHA-256, taille, date, nom) et `/firmware/<sha256>.bin` (téléchargement par empreinte).
- Le firmware ne compte pas dans l'arrêt « 2 appareils servis » : seul le téléchargement d'un APK le compte. La limite de durée reste.
- L'appli ne flashe jamais seule : elle télécharge, vérifie le SHA-256 et l'image, puis demande la confirmation habituelle avant d'écrire sur la machine par USB-C.

## Côté téléphone

1. Réglages > Mise à jour > **Mise à jour depuis le PC**.
2. Saisir l'adresse affichée par le script de démarrage, par exemple `http://<ip-tailscale-du-pc>:8091/`.
3. Appuyer sur **Installer la dernière version du PC**.

Pour le firmware : Réglages > Firmware de la machine. L'appli vérifie discrètement le serveur en ouvrant la section et affiche « Firmware disponible sur le PC » si l'image diffère de la dernière flashée depuis l'appli ; le bouton « Chercher une mise à jour du firmware » force la vérification.

L'appli demande au serveur le manifeste de son propre package, puis télécharge l'APK. Elle vérifie le SHA-256, le package, la signature et que la version est bien plus récente. Ensuite, elle ouvre l'installeur Android, qui demande toujours la confirmation habituelle.

## Tests

```powershell
node --test
```

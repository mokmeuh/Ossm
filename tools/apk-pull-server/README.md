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

## Côté téléphone

1. Réglages > Mise à jour > **Mise à jour depuis le PC**.
2. Saisir l'adresse affichée par le script de démarrage, par exemple `http://<ip-tailscale-du-pc>:8091/`.
3. Appuyer sur **Installer la dernière version du PC**.

L'appli demande au serveur le manifeste de son propre package, puis télécharge l'APK. Elle vérifie le SHA-256, le package, la signature et que la version est bien plus récente. Ensuite, elle ouvre l'installeur Android, qui demande toujours la confirmation habituelle.

## Tests

```powershell
node --test
```

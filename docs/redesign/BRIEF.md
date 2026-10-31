# Redesign UI/UX : consignes de départ

À lire en premier dans la conversation de redesign. Ce worktree est `C:\Users\mikki\Documents\Ossm-redesign`, branche `ossm/redesign`, partie de `v2.3.46`.

## Le but
- Refaire l'UI et l'UX de l'app Android OSSM Remote (`app/`, Kotlin + Jetpack Compose).
- L'utilisateur se dit peu créatif côté visuel : c'est toi qui mènes.
- **Contrainte absolue : aucune fonctionnalité perdue, rien de cassé.**

## L'équipe
Délègue au maximum à des agents collègues, puis valide ou rejette leur travail.

### 1. Inventaire
Un agent liste chaque écran, onglet, bouton, slider, case, mode et réglage. Il l'écrit dans `docs/redesign/contrat-fonctionnel.md` : c'est le contrat de non-régression. L'inventaire couvre :
- Contrôle : modes, Teasing Pounding avec Aléatoire et Max.
- Live : demi-cercle avec ancre.
- Naturel : Qwen en 2 étapes.
- Voix.
- Remote : session, caméra, enveloppe.
- Réglages, et la carte Mise à jour : OTA locale par QR et mise à jour depuis le PC.
- Barre STOP, overlay, statistiques, habitudes.

### 2. Direction visuelle
- Skill `ui-ux-pro-max` : `--design-system`, puis `--stack jetpack-compose`.
- Contexte d'usage : app de contrôle utilisée d'une main, souvent dans le noir, thème sombre.
- Persister avec `--persist --output-dir <racine du worktree>`.
- Traduire en tokens Compose (couleurs, typo, espacements, formes) dans `ui/theme/`.

### 3. Implémentation
- Un agent par écran, une seule livraison à la fois : un seul Gradle à la fois.

### 4. Revue
Deux relecteurs par écran :
- **Parité** : rien ne manque par rapport au contrat.
- **Accessibilité et sécurité** : contraste 4,5:1, cibles de 48 dp, STOP toujours visible et prioritaire, aucune logique de sécurité ni de protocole dans l'UI.

## Règles
- **UI seulement.** Ne pas modifier :
  - la logique de `session/ControlSessionStore.kt`, `ble/BleManager.kt` et `usb/` ;
  - `updater/`, sauf l'affichage ;
  - le protocole ;
  - le mapping Live, confirmé sur l'appareil.
- `ControlScreen.kt` est très gros : extraire des composables plutôt que tout réécrire d'un coup.
- **Installation à côté de l'app actuelle** :
  - package distinct (par ex. `applicationIdSuffix`, qui donne `com.ossm.remote.redesign.debug`) ;
  - nom distinct : « OSSM Redesign » ;
  - icône teintée si possible ;
  - vérifier que FileProvider, Hilt et la mise à jour depuis le PC suivent le package dynamiquement ;
  - une seule des deux apps se connecte à la machine à la fois.
- **Chaînes** : FR dans `res/values/`, EN dans `res/values-en/` (`StringsParityTest`), en UTF-8 sans BOM. Aucune mention d'IA, de nom ni d'initiales dans le code.
- **Build** :
  - `local.properties` (SDK Windows) et l'AAR `app/libs/sherpa-onnx-1.13.8.aar` ont été copiés dans ce worktree, et ne sont pas suivis.
  - Commande : `.\gradlew.bat testDebugUnitTest assembleNamedDebugApk "-Pksp.incremental=false" -x lint`.
  - 5 tests de `ControlViewModelTest` (drag, release et rampe du Live) sont connus comme périmés : les ignorer.
  - Un seul Gradle à la fois sur tout le PC, y compris le checkout principal `C:\Users\mikki\Documents\Ossm`.
- **Git** :
  - Pas de reset, clean, stash ni rebase.
  - Commit seulement avec l'accord de l'utilisateur.
  - Ne jamais pousser sans sa demande.
- **Téléphone** :
  - Moto G en ADB par Tailscale, toujours avec `-s <serial>`. Demander le port, il change.
  - Sinon, `tools/apk-pull-server` avec `--dir` pointé sur la sortie APK de ce worktree. L'app vérifie le serveur à l'ouverture.

## C'est terminé quand
- le contrat fonctionnel est écrit ;
- le design system est persisté ;
- tous les écrans sont refaits et ont passé les deux revues ;
- les tests et le build passent ;
- l'APK redesign est installé à côté de l'app actuelle sur le Moto G ;
- l'utilisateur a reçu un résumé écran par écran, avec des captures si possible.

# Livraison : Réglages (profils, session distante, overlay, mises à jour, firmware)

Code relu à la main, **non compilé** (pas de Gradle). Aucune logique, aucun callback, aucune signature publique modifiés.

## Fichiers modifiés
- `app/src/main/java/com/ossm/remote/ui/screens/ProfilesScreen.kt` (page, profils, session Internet, overlay, carte Mise à jour, briques repliables communes)
- `app/src/main/java/com/ossm/remote/ui/screens/LocalOtaSection.kt`
- `app/src/main/java/com/ossm/remote/ui/screens/PcUpdateSection.kt`
- `app/src/main/java/com/ossm/remote/ui/screens/FirmwareFlashSection.kt`

Fichiers créés : `app/src/main/res/values/strings_redesign_reglages.xml` et `values-en/strings_redesign_reglages.xml` (8 clés `rg_*`, parité FR/EN).

Signatures inchangées : `ProfilesScreen(...)` (mêmes 9 paramètres), `LocalOtaSection()`, `PcUpdateSection(viewModel)`, `FirmwareFlashCard()`, `onOverlayToggle(Context, OverlaySettingsViewModel, Boolean)`.

## Briques ajoutées (dans `ProfilesScreen.kt`, `internal`, réutilisables par les autres fichiers du package)
- `SettingsFoldSection` : section repliable (en-tête cliquable 48 dp minimum, `Role.Button`, `stateDescription` « Déplié / Replié », animation 160 ms FastOutSlowIn). L'état ouvert/fermé est en `rememberSaveable` chez l'appelant, donc il survit au défilement de la `LazyColumn`.
- `SettingsFoldCard` : même chose, enveloppée dans une carte.
- `SettingsDivider` : filet de 1 dp entre sous-sections.
- `OpenOnChange` : rouvre une section quand un nouvel état arrive pendant que l'écran est affiché, jamais au simple retour sur l'écran (un repli volontaire est respecté).

## Changements visuels
- Titre de page « Réglages » (clé existante `nav_profiles`) ; « PROFILS » devient le titre du groupe de profils. Gouttière 16 dp, espacement 16 dp.
- **Profils** : carte par profil, nom + motif, trois valeurs en colonnes égales (`OssmValue`, vitesse turquoise, Min et Max bleu profondeur, plus de vert qui voulait dire « connecté »). Boutons **Appliquer** (principal) et **Supprimer** (secondaire) avec libellé, 48 dp, 8 dp d'écart, côte à côte. Confirmation de suppression conservée (bouton Danger).
- **Session Internet** : carte à titre. Message et statut média en `OssmStatusLine` (icône + texte). Boutons Copier (principal) / Partager (secondaire) à 48 dp. Bouton Démarrer/Arrêter à 48 dp. « Régénérer et révoquer le lien » en style Danger, séparé par un filet, **sans confirmation** (comportement inchangé), aide en dessous.
- **Overlay** : carte à titre `OVERLAY`, `OssmSwitchRow` (libellé « Activer l'overlay » + aide), avertissement de permission en `OssmStatusLine`, valeur de transparence en `OssmValue`, curseur avec `contentDescription` et `stateDescription`. Les repères des 80 paliers sont masqués (le pas de 1 % est conservé).
- **Mise à jour** : une seule carte. En-tête avec « Vérifier » (48 dp). Le **résultat de « Vérifier » (et Télécharger / Installer)** est remonté juste sous l'en-tête et reste visible sections repliées. Trois sous-sections repliables séparées par des filets :
  - Mise à jour locale OTA : **fermée** par défaut ; deux groupes « Recevoir » / « Envoyer » ; repliée, elle affiche « En écoute » et le dernier message.
  - Mise à jour depuis le PC : **ouverte** par défaut (usage le plus fréquent) ; toute la rangée « Mode développement » bascule (plus seulement l'interrupteur).
  - OTA Internet (secours) : **fermée** par défaut (URL du manifeste + Enregistrer).
- **Messages OTA, PC, Internet, firmware** : tous en `OssmStatusLine` (icône + texte), annonce polie conservée pour le message OTA.
- **Dialogues OTA** : nombre de vérification en `OssmDisplayValue` agrandi (x1,75 ≈ 56 sp, sans taille en dur), couleur primaire. Émetteur : **non annulable** en touchant à côté (inchangé), un seul bouton Annuler. Récepteur : Non (secondaire) / Oui (principal), toucher à côté ou retour = refus (inchangé).
- **Firmware** : carte repliable, **fermée** par défaut, **forcée ouverte** tant que l'état n'est pas Idle (préparation, effacement, écriture, résultat non acquitté) : progression, erreur et journal ne sont jamais cachés. Journal en 12 sp monospace (10 sp avant). « Copier le journal » et « OK » à 48 dp. Dialogue de confirmation conservé (toucher à côté = annuler).
- Boutons du contrat rendus ≥ 48 dp : Copier, Partager, Démarrer/Arrêter, Régénérer, Télécharger, Installer, Vérifier, Enregistrer l'URL, Enregistrer l'adresse, Installer depuis le PC, Ouvrir l'installation, Copier le journal, OK, boutons des dialogues.

## Écarts d'ordre assumés (aucun contrôle retiré)
- Le résultat de « Vérifier » passe du bas de la carte au haut (sous l'en-tête).
- Dans OTA locale : le bouton « Partager ma version » passe avant « Envoyer à une adresse » et son champ.
- L'icône lanceur décorative de la ligne Overlay est supprimée (le libellé et l'interrupteur portent le sens).

## Tableau « élément du contrat -> emplacement dans le nouveau code »

| Élément du contrat (section) | Emplacement |
|---|---|
| Structure LazyColumn, clés `title`, `empty`/`preset.id`, `remote-session`, `overlay`, `updater`, `firmware`, `statistics` (5) | `ProfilesScreen` ; ajout de la clé `profiles-header` avant les profils ; `firmware` puis `statistics` restent les deux derniers |
| Auto-défilement vers la carte firmware (`totalItemsCount - 2`) (5) | `ProfilesScreen`, `LaunchedEffect(flashPhase)` inchangé ; carte forcée ouverte pendant le flash |
| Titre `prof_title` (5) | `ProfilesScreen`, élément `profiles-header` (le titre de page utilise `nav_profiles`) |
| État vide `prof_empty` + icône (5.1) | `ProfilesEmptyState` |
| Nom, motif, puces Vitesse/Min/Max (5.1) | `PresetCard` + `PresetValue` |
| Appliquer `prof_apply` -> `onApply` (5.1) | `PresetCard`, `OssmButton` principal |
| Supprimer + dialogue `prof_delete_title/msg`, `ctl_cancel` (5.1) | `PresetCard`, `OssmButton` secondaire + `AlertDialog` |
| Sauvegarde du profil (disquette, dialogue) (5.1) | Hors périmètre (ControlScreen) |
| Carte session : titre, aide, message, statut média (3.1) | `RemoteSessionCard` (`OssmCard`, `OssmStatusLine`) |
| Champ lien en lecture seule `remote_session_link` (3.1) | `RemoteSessionCard`, `state.shareUrl?.let` |
| Copier (presse-papiers explicite) + confirmation `ui2_remote_copied` (3.1) | `RemoteSessionCard`, `copied` |
| Partager le lien (chooser) (3.1) | `RemoteSessionCard` |
| Démarrer / Arrêter la session (3.1) | `RemoteSessionCard`, bouton pleine largeur |
| Régénérer et révoquer + aide (3.1) | `RemoteSessionCard`, style Danger, sans confirmation |
| Overlay : interrupteur, `onOverlayToggle`, aide, avertissement permission (5.2) | `OverlayCard` (`OssmSwitchRow`, `OssmStatusLine`) ; `onOverlayToggle` inchangée |
| Overlay : transparence 20-100 %, 80 paliers, valeur `NN%` (5.2) | `OverlayCard`, `Slider` (`steps = 79`) |
| En-tête Mise à jour + « Vérifier » désactivé si Checking/Downloading (5.3) | `UpdaterCard`, première ligne |
| États Idle/Checking/UpToDate/Available + Télécharger/Downloading/ReadyToInstall + Installer/Error (5.3) | `UpdaterStatusBlock` (sous l'en-tête) |
| OTA Internet : sous-titre, URL du manifeste, placeholder, Enregistrer l'URL (5.3) | `UpdaterCard`, dernière `SettingsFoldSection` |
| OTA locale : titre, bouton récepteur primaire/ambre (5.4) | `LocalOtaSection` + `ReceiverButton` |
| QR de jumelage, légende, adresses, aucune adresse (5.4) | `LocalOtaSection`, bloc `receiverInfo?.let` |
| Aide générale OTA (5.4) | `LocalOtaSection`, premier élément du corps |
| Scanner le QR (désactivé si `senderActive`) (5.4) | `LocalOtaSection`, `OssmButton` principal |
| Envoyer à une adresse + champ + « Se connecter et envoyer » (5.4) | `LocalOtaSection` (`manualOpen`, `suggestManual`), clavier Uri/Send inchangé |
| Partager ma version (5.4) | `LocalOtaSection`, `OssmButton` secondaire |
| Dialogue émetteur (nombre, non annulable, Annuler) (5.4) | `LocalOtaSection`, `otaState.pairing?.let`, hors du corps repliable |
| Dialogue récepteur (Oui/Non, refus par défaut) (5.4) | `LocalOtaSection`, `otaState.incoming?.let`, hors du corps repliable |
| Message d'état OTA (succès/erreur/info) (5.4) | `OtaMessageLine` (dans le corps, et sous l'en-tête si replié) |
| PC : titre, aide, champ adresse, erreur, Enregistrer (5.5) | `PcUpdateSection` |
| PC : Installer la dernière version (désactivé si Checking/Downloading) (5.5) | `PcUpdateSection` |
| PC : statuts + Ouvrir l'installation (5.5) | `PcUpdateStatusLine` |
| PC : mode développement (15 s) + « À l'écoute du PC » (5.5) | `PcUpdateSection`, `OssmSwitchRow` ; la ligne « à l'écoute » est aussi visible repliée |
| Firmware : titre, aide, états, barres (5.6) | `FirmwareFlashCard`, `SettingsFoldCard` |
| Firmware : erreurs `failureMessage` (5.6) | `failureMessage` inchangée |
| Firmware : journal, copie, défilement auto (5.6) | `FirmwareFlashCard` |
| Firmware : flasher le fichier inclus / choisir un .bin, masqués pendant `busy` (5.6) | `FirmwareFlashCard`, fin du corps |
| Firmware : confirmation obligatoire avant flash (5.6) | `FirmwareFlashCard`, `pending?.let` hors de la carte |
| Firmware : écran maintenu allumé pendant `busy` (5.6) | `FirmwareFlashCard`, `DisposableEffect(busy)` inchangé |
| Statistiques (5.7) | Hors périmètre, `StatisticsSection()` appelée telle quelle |

## Ce que je n'ai pas pu vérifier
- **Compilation** : jamais compilé. Points à surveiller : `TextUnit * Float` (`VerificationNumberStyle`), paramètres de `SliderDefaults.colors` (`activeTickColor`, `inactiveTickColor`) avec Material3 1.3.1, `AnimatedVisibility` appelé depuis `ColumnScope`.
- **Rendu** : aspect réel du curseur Material (piste, repères masqués), largeur des boutons Copier/Partager sur le Moto G à 130 %, hauteur de la carte Mise à jour en position ouverte.
- **Comportements d'état** : réouverture automatique des sections (`OpenOnChange`) et fermeture au retour à l'écran à tester sur appareil ; le dialogue d'appairage OTA avec la section repliée.
- **Textes non localisés** : `state.message` et `state.mediaStatus` de la session distante restent des chaînes FR codées en dur côté `RemoteSessionClient` (hors périmètre).
- Accessibilité TalkBack non testée (rôles, `stateDescription`, annonce du message OTA).

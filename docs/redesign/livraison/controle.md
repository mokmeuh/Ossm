# Livraison : Contrôle (écran principal, Live, Teasing Pounding)

Worktree `Ossm-redesign`, branche `ossm/redesign`. UI seulement : aucune ligne de `session/`, `ble/`, `usb/`, `remote/`, `voice/`, `natural/`, `updater/`, `viewmodel/` n'a été touchée. Aucune signature publique, aucun callback, aucun état de ViewModel modifié.

## Fichiers

| Fichier | Nature |
|---|---|
| `app/src/main/java/com/ossm/remote/ui/screens/ControlScreen.kt` | refait (habillage, mise en page, composables extraits). Logique des brouillons, de la synchro à 250 ms, du `key(remoteSession.active)`, de l'orientation Live, des portes `streamingReady` / `slidersEnabled` : reprise à l'identique. |
| `app/src/main/java/com/ossm/remote/ui/screens/ObservedPositionRange.kt` | commentaire de classe ajouté, code inchangé |
| `app/src/main/res/values/strings_redesign_control.xml` | nouveau (18 clés `ctlrd_*`) |
| `app/src/main/res/values-en/strings_redesign_control.xml` | nouveau (mêmes 18 clés) |

Fonctions `internal` gardées avec leur signature : `CompactControlScaffold` (`modifier, showActionRail, bare, actionRail, content`), `controlStatusKind`, `ControlStatusKind`, `shouldShowSensationSlider`, `shouldShowMaxSensationToggle`, `controlHeaderTitle`, `shouldShowLiveInvertSetting`, `shouldShowLiveAccelerationSetting`, `shouldShowLiveRecordButton`, `controlActionVisibility`, `isPrecisionEnabled`, `signedSensationPercent`, `signedSensationLabel`, `sensationIntensityLabel`, `sessionSensationDraft`, `remoteSensationBannerArgs`, `sensationLegendRes`, `shouldKeepHoldInfoVisible`, `DepthRangeEditor`, `ControlDraftEditClock`, `CONTROL_DRAFT_QUIET_MS`.

## Ce qui change visuellement

Hiérarchie : **état et connexion en haut, modes en puces, contrôles du mode au centre, STOP en bas.**

1. **Ligne d'état toujours présente en portrait** (hors Live nu). Anormal : bandeau (icône + texte, 2 lignes max) USB / non connecté / calibration / erreur, comme avant. Normal : nouvelle ligne verte « Bluetooth connecté · <état machine> » (tag `control_status_ready`). En paysage : pastille dans l'en-tête (`control_status_pill`, ou `control_status_ready`). Calibration passe de bleu à ambre (le bleu est réservé à la profondeur). Icône USB dédiée.
2. **Barre STOP, pause et verrou en bas** (portrait), à portée du pouce, hauteur 64 dp (`OssmDimens.StopHeight`), 12 dp d'écart avec ses voisins. En paysage : colonne de gauche comme avant (STOP vertical, largeur 64 dp). Le rail est lu en premier par TalkBack malgré sa place (`isTraversalGroup` + `traversalIndex = -1f`). Le sélecteur de modes ne recouvre jamais le rail (réserve en bas en portrait, à gauche en paysage).
3. **Modes en puces** : l'en-tête de la carte affiche une rangée de puces défilante (une par mode, dans l'ordre utilisateur). Mode actif : fond relevé, contour turquoise 2 dp, coche, `selected` pour TalkBack. Touche = activer (même `onPattern` qu'avant) ; appui long = réorganiser. Le bouton d'ouverture du sélecteur complet est conservé à gauche. Le titre (`controlHeaderTitle`) reste affiché tel quel en Live nu et quand la liste de modes est vide ; sinon il se lit dans la puce active.
4. **Sélecteur de modes** (overlay) : mêmes puces en grille 2 colonnes, plus de couleurs décoratives (la rotation turquoise / bleu / ambre est supprimée), coche sur le mode actif, titre « Modes » et aide qui mentionne l'appui long.
5. **Couleurs par sens** : vitesse turquoise, profondeur bleu (`OssmDepth`), sensation lilas (`OssmSensation`), ambre pour les limites (Remote, plafond d'intensité Auto Random), vert pour connecté. Les valeurs pilotées par l'aléatoire restent en rouge `OssmError`.
6. **Boutons d'en-tête** (mode, réglages, enregistrer) : un seul composable `ControlIconButton` 48 dp. **Limites Live** : l'état se lit aussi par un cadenas (ouvert = la ligne suit le doigt, fermé = verrouillée) en plus du contour. **REC** : une forme par état (point, anneau « prêt », gros point REC, triangle « rejouer », carré « arrêter la boucle »).
7. **Verrou de rotation** 48 dp (avant 44), contour épais + icône différente quand il est actif.
8. **Cases Aléatoire / Max** : 48 dp de haut partout (avant 36 en paysage) ; en paysage les deux cases du curseur Sensation s'empilent dans la colonne du libellé.
9. **Progressif** : la case « Mode aléatoire au max » devient une rangée interrupteur entièrement cliquable (avant : seule la case). Même callback `onChaosToggle`, même condition `connected`.
10. **Auto Random** : chaque mode à mélanger est une rangée entièrement cliquable (56 dp, coche + contour turquoise quand choisi) ; l'intensité courante s'affiche en `OssmDisplayValue` ; Démarrer = bouton principal, Arrêter = bouton secondaire (le rouge reste réservé au STOP). Libellés accentués (« Démarrer », « Arrêter »).
11. **Bandeau Remote** : fond surélevé, cadenas ambre, limites en une phrase, avertissement « maximum à 0 % » via `OssmStatusLine` (icône + texte), valeurs envoyées par le site en légende.
12. **Messages de préparation** (Live, assistant de plage) : `OssmStatusLine` ambre 13 sp (avant 11 sp).
13. **Réglages du contrôle** : sections titrées (Saisie, Live, Protections, Rappels), valeurs en `OssmValue`, interrupteurs et bulles d'info à la même charte, bouton « Compris » 48 dp, limites de profondeur en bleu profondeur avec zone tactile 48 dp, plus d'ombre ni de teinte d'élévation.
14. **Dialogues** (changement brusque, Auto Random, profil, réorganisation, plage aléatoire inatteignable) : boutons `OssmButton` 48 dp, fond à plat. Dans « Changement brusque », « Annuler » devient le bouton principal et « Oui, je suis sûr » le bouton secondaire (l'action sûre est la plus visible).
15. **Réorganisation** : poignée d'icône au lieu du glyphe « ☰ », la liste défile (beaucoup de modes), actions TalkBack « Monter » / « Descendre » ajoutées au glisser-déposer.
16. **Textes** : plus aucun `fontSize` ni hex dans le fichier ; tout texte ≥ 12 sp. « −100 », « +100 » et « Vitesse (auto) » passent par des ressources (« Vitesse (auto) » vient de `strings_redesign_curseurs.xml`, posé par le groupe curseurs).
17. **Corps des modes à curseurs en portrait court** : plus de bascule en métriques « Inline » sous 400 dp (trop larges pour un téléphone en portrait : la piste devenait presque nulle). Les curseurs restent répartis sur la hauteur du corps avec les métriques normales ; si le contenu dépasse (petit écran, police agrandie, cases empilées en paysage), la colonne défile en secours au lieu de rogner une commande (`fillMaxSize()` + `verticalScroll`, hauteur minimale = le corps). Quand tout tient, rien ne défile. Règle 14 du contrat amendée en conséquence.
18. **Limites imposées** : en session distante, `limited = remoteSession.active` est passé aux curseurs qui affichent l'enveloppe (vitesse du mode Stroke Engine, plafond Auto Random, vitesse max Naturel, `ProgressiveSpeedBar`, sensation, profondeur via `DepthRangeEditor(limited)`) : cadenas, mot « limité » et état lu par TalkBack. Pas appliqué à Voix (valeur locale, aucune enveloppe affichée) ni aux autres curseurs Auto Random / Naturel (réglages locaux).

Tags de test ajoutés : `control_status_ready`, `control_pattern_open`, `control_pattern_chips`, `pattern_chip_<clé>`, `control_pause`, `control_rotation_lock`, `control_settings`, `control_save`. Tags conservés : `control_main_surface`, `control_card`, `control_stop`, `control_status_line`, `control_status_pill`, `remote_session_banner`.

## Contrat → emplacement dans `ControlScreen.kt`

| Élément du contrat (inventaire A / B) | Emplacement |
|---|---|
| Gabarit portrait / paysage, rail, pas de défilement de page, signature testée | `CompactControlScaffold`, `ActionRailHeight` |
| Statut USB / déconnecté / calibration / erreur, ligne et pastille, tags | `controlStatusKind`, `ControlStatusLine`, `ControlStatusPill`, `ControlStatusBanner`, `StatusPill` |
| Nouveau : statut « prêt » | `ControlReadyLine`, `ControlReadyPill` |
| STOP (`control_stop`, `onStop`, 64 dp, jamais masqué sauf Live nu), pause, verrou | `CompactActionRail`, `PausePlayButton`, `RotationLockButton` |
| Live nu : ni STOP ni pause ni verrou ni statut, portrait forcé puis restauré | `ControlScreen` (`liveBareScreen`, `DisposableEffect`, `showStopBar`, `statusKind`) |
| Ouvrir les patterns (cd `ctl_patterns_open`) | `PatternOpenerButton` |
| Titre du mode actif | `controlHeaderTitle` : Live nu (Text) ; sinon `PatternChipRow` (puce active, ou `fallbackTitle` si liste vide) |
| Mode en puces, touche = `onPattern`, appui long = réorganiser | `PatternChipRow`, `PatternChip` |
| Limites Live haute / basse (3 états, portes `connected && streamingReady`) | `LiveLimitButton` |
| REC (5 états) | `LiveRecordButton`, `liveRecordContentDescription` |
| Bouton « i » Voix | `VoiceInfoButton` (appelé dans l'en-tête, fichier non modifié) |
| Réglages du contrôle (engrenage, dès qu'un pattern est actif) | `SettingsGearButton`, `ControlSettingsDialog` |
| Enregistrer un profil (`activePattern != null && connected && showSave`) | `ControlIconButton` dans l'en-tête, `SavePresetDialog` |
| Bandeau session distante (3 lignes, cadenas) | `RemoteSessionBanner` |
| `key(remoteSession.active)`, brouillons, calme de 250 ms | `ControlScreen` (inchangé), `ControlDraftEditClock` |
| Assistant de plage (inatteignable aujourd'hui) | branche `uiState.rangeWizard != null` de `ControlScreen` |
| Aucun pattern / `LAUNCH_ONLY` | branches `activePattern == null` et `LAUNCH_ONLY` |
| Voix | branche `isVoicePattern()` → `VoiceControlPanel` |
| Live : message de préparation + pad, `onStreamDrag` / `onStreamTouchEnd` / `onStreamTouchCancel`, clé d'époque | branche `STREAMING` → `OssmStatusLine` + `LiveAnchorPad` |
| Progressif : barre max, profondeur, sensation, Chaos | branche `PROGRESSIVE` (`ProgressiveSpeedBar`, `DepthRangeEditor`, `CenteredSensationSlider`, `OssmSwitchRow`) |
| Naturel (`depthEditor` injecté) | branche `NATURAL` → `NaturalControlPanel` |
| Auto Random : intro, modes, 3 curseurs, profondeur, intensité de départ / courante, Démarrer / Arrêter | branche `AUTO_RANDOM`, `AutoRandomPatternRow` |
| Confirmation de départ Auto Random | `AutoRandomStartDialog` |
| Vitesse (marqueur, valeur rouge si aléatoire), `RandomToggle` vitesse | branche `else` (Stroke Engine), `RandomToggle` |
| Profondeur, `RandomToggle` profondeur | `DepthRangeEditor` |
| Sensation bipolaire, détente 0, plage aléatoire, Max (T&P seulement), légendes d'échelle | `CenteredSensationSlider`, `shouldShowSensationSlider`, `shouldShowMaxSensationToggle`, `sensationLegendRes` |
| Cases Aléatoire seulement `teasingPounding` / `simpleStroke` | `isRandomCapable` dans la branche `else` |
| Précision par pattern, accélération Live 10 à 100, inversion Live | `ControlSettingsDialog`, `SettingsToggleRow`, `SettingLabelWithInfo` |
| Gardes vitesse / profondeur, limites de profondeur, aides STOP et Pause, bulle d'info à l'appui long | `ControlSettingsDialog`, `PressHoldInfo`, `InfoLine`, `shouldKeepHoldInfoVisible` |
| Changement brusque (2 interrupteurs, confirmer, annuler avec ré-alignement des brouillons) | `AbruptChangeDialog`, `GuardCheckboxRow` ; ré-alignement dans l'appel de `ControlScreen` |
| Sélecteur de patterns (Retour ferme, grille 2 colonnes, appui long) | `PatternPickerOverlay` + `BackHandler` dans `ControlScreen` |
| Réorganisation glisser-déposer | `ReorderPatternsDialog` |
| Plage aléatoire / préréglages (code mort conservé) | `RandomPresetRow`, `RandomRangePresetDialog`, `SliderGuardRow`, `editingRandomPreset` |

## Ce que je n'ai pas pu vérifier

- **Aucun Gradle, aucun test exécuté, aucun rendu vu.** Contrôle fait hors Gradle : compilation de `ControlScreen.kt` + `ObservedPositionRange.kt` avec le compilateur Kotlin 2.3.21 et le plugin Compose du cache Gradle, contre Compose 1.7.6 / Material 3 1.3.1, avec `ui/theme`, `ui/components` et `model` réels. Résultat : aucune erreur de syntaxe ni de type ; il ne reste que les symboles d'autres fichiers (`NaturalControlPanel`, `VoiceControlPanel`, `VoiceInfoButton`, `NaturalControlActions`, `VoiceControlActions`, `VoiceAiUiState`), inchangés dans leur appel. À confirmer par le vrai build.
- **Mise en page réelle** : hauteurs et largeurs calculées à la main. Points à regarder sur le Moto G : (a) en portrait 360 dp de large la rangée de puces ne laisse qu'environ 128 dp aux puces (72 dp en mode Voix) : la puce active peut être rognée à droite, la rangée défile ; (b) défilement de secours des modes à curseurs sur un corps court ; (c) à 130 % de police ; (d) Teasing Pounding paysage 640×360 avec les cases empilées sous « Sensation » (corps d'environ 190 dp pour environ 250 dp de contenu : la colonne défile de 50 à 60 dp, à mesurer).
- **TalkBack** : l'ordre « STOP lu en premier » (`traversalIndex`) et les actions « Monter / Descendre » ne sont pas testés.
- **Décision de conception à valider** : STOP déplacé en bas en portrait (suit MASTER.md §6). Pour revenir en haut, il suffit d'inverser l'ordre dans `CompactControlScaffold`.
- **Live nu sans STOP** : inchangé (point ouvert du contrat 0.2.2, à trancher par l'utilisateur, hors de mon périmètre de sécurité).
- Ma ligne « Bluetooth connecté » suppose que toute connexion non USB est Bluetooth (c'est le cas dans `BleConnectionState`).
- Éléments devenus inutilisés mais non supprimés (fichiers hors de mon périmètre) : `PatternButton`, clés `ctl_patterns`, `ctl_patterns_hint`, `ctl_start`, `ctl_stop_mix`, `ctl_rec_armed`, `ctl_rec_playing`.
- Libellés d'état venant du modèle (`MachineState.displayLabel`, `BleConnectionState.label()`) toujours codés en français dans `model/` : hors périmètre.

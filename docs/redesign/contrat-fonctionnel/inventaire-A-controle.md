# Inventaire A : Contrôle, Connexion, Diagnostics, composants, thème

Contrat de non-régression pour le redesign. Lecture seule, aucun fichier du dépôt modifié.
Racine UI : `app/src/main/java/com/ossm/remote/ui/`. Version lue : dépôt à `ossm/v2.3.46`.
Fichiers lus en entier : `screens/ControlScreen.kt` (3077 l.), `ScanScreen.kt`, `DiagnosticsScreen.kt`, `ObservedPositionRange.kt`, tous les `components/` du périmètre, `theme/`.
Hors périmètre mais appelés : `NaturalControlPanel.kt`, `VoiceControlPanel.kt` (voir 1.7), `ProfilesScreen`, `LocalOtaSection` (utilise `QrCodeImage`).

Abréviations : « FR » = `res/values/`, « EN » = `res/values-en/` (parité vérifiée par `StringsParityTest`). Les textes FR ci-dessous sont ceux de `res/values/strings*.xml`.

---

## 0. Vue d'ensemble et points critiques (à lire d'abord)

### 0.1 Câblage d'entrée
`MainActivity.kt` l.493 est le SEUL appelant de `ControlScreen`. Les callbacks sont des références `controlVm::...` (délèguent à `ControlSessionStore`) :

| Paramètre ControlScreen | Méthode appelée |
|---|---|
| `onSpeedCommit` / `onSpeedLive` | `requestSpeedChange` / `setSpeedLive` |
| `onSpeedCommitWithEpoch` / `onSpeedLiveWithEpoch` | `requestSpeedChangeAtEpoch` / `setSpeedLiveAtEpoch` (prioritaires sur les précédents quand non nuls) |
| `onDepthRangeCommit` / `onDepthLive` | `requestDepthRangeChange` / `setDepthLive` |
| `onSensationCommit` / `onSensationLive` (+ variantes `AtEpoch`) | `requestSensationChange` / `setSensationLive` |
| `onSensationRandomRangeChange` | `setRandomSensationRange` |
| `onProgressiveMaxCommit` | `setProgressiveMaxSpeed` |
| `onChaosToggle` | `setChaosAtMax` |
| `onStreamDrag` | `setStreamPositionDrag` (Live) |
| `onStreamTouchEnd` | `endLiveTouch` (relâchement : retour lent) |
| `onStreamTouchCancel` | `stopLiveTouch` (annulation : STOP) |
| `onRangeWizardCapture` / `onRangeWizardCancel` | `captureRangePoint` / `cancelRangeWizard` |
| `onPattern` | `activatePattern` |
| `onStop` | `controlVm.stop()` |
| `onPause` / `onResume` | `pause` / `resume` |
| `onAutoToggleSelected`, `onAutoMaxSpeedChange`, `onAutoIntensityCapChange`, `onAutoRandomnessChange`, `onAutoInitialIntensityChange` | `toggleAutoSelected`, `setAutoMaxSpeed`, `setAutoIntensityCap`, `setAutoRandomness`, `setAutoInitialIntensity` |
| `onAutoRequestStart` / `onAutoCancelStart` / `onAutoConfirmStart` / `onAutoStop` | `requestAutoStart` / `cancelAutoStart` / `confirmAutoStart` / `stopAuto` |
| `onSavePreset` | `profilesVm::savePreset` |
| `onSpeedGuardEnabledChange` / `onDepthGuardEnabledChange` | `setSpeedGuardEnabled` / `setDepthGuardEnabled` |
| `onDepthLimitsChange` | `setDepthLimits` |
| `onPrecisionEnabledChange(patternKey, bool)` | `setPrecisionEnabled` |
| `onConfirmPendingChange(skipSession, never)` / `onDismissPendingChange` | `confirmPendingManualChange` / `dismissPendingManualChange` |
| `onPatternOrderSave` | `savePatternOrder` |
| `onRandomToggle(RandomTarget, bool)` | `setRandomMode` ; `onDepthRandomToggle` : `setDepthRandom` |
| `onLiveInvertToggle` / `onLiveAccelerationChange` / `onLiveRecordToggle` | `setLiveInvert` / `setLiveAcceleration` / `toggleLiveRecording` |
| `onRandomPresetSave/Apply/Edit` | `saveRandomPreset` / `applyRandomPreset` / `editRandomPreset` (voir 0.3 : aucun widget visible ne les appelle) |
| `naturalActions`, `voiceActions`, `voiceAi`, `voiceModelRoot` | panneaux Naturel / Voix |

Entrées d'état : `connectionState: BleConnectionState`, `machineState: MachineState`, `uiState: ControlUiState`, `remoteSession: RemoteSessionView`.

### 0.2 Règles de sécurité/comportement à ne JAMAIS casser (synthèse)
1. **STOP toujours atteignable** : `controlActionVisibility().showStop = true` en toutes circonstances (y compris Live). Le STOP est HORS de la `GlassCard`, jamais masqué par le pad. SAUF `liveBareScreen` (voir 0.2.2).
2. **Écran Live nu** (`liveBareScreen` = pas de wizard + mode STREAMING + pas Voix) : pas de barre STOP, pas de pause, pas de verrou de rotation, pas de bandeau de statut, `padding` de la carte 6 dp, portrait FORCÉ (`SCREEN_ORIENTATION_PORTRAIT`, ancienne orientation restituée à la sortie). Attention : `showStopBar = actionVisibility.showStop && !liveBareScreen`, donc en Live nu il n'y a PAS de STOP visible à l'écran ; la sécurité repose sur le relâché (retour lent), l'annulation (`stopLiveTouch`), et le STOP via overlay/autres chemins. Un redesign ne doit pas supposer le contraire sans décision explicite (le commentaire dans `controlActionVisibility` dit « STOP doit rester atteignable » alors que le code Live nu le masque : incohérence à trancher avant redesign).
3. **Pad Live (LiveAnchorPad)** : première pression n'importe où = ancre (valeur logique courante, 0 au repos) ; la zone AU-DESSUS de la ligne de l'ancre pilote la cible par la distance radiale ancre-doigt ÷ distance à l'angle haut le plus éloigné (`target = base + fraction*(100-base)`), sur ou sous la ligne = reste à la base ; jamais sous la base. Zéro fixe en bas du pad (ou sur la limite basse). Au relâché : `onTouchEnd` (retour lent phone-owned, 20 %/s, `LIVE_RELEASE_RETURN_RATE_PERCENT_PER_SECOND = 20f` dans `session/LiveTargetSlew.kt`, logique dans `ControlSessionStore`, PAS dans l'UI). Au cancel (perte de geste, `enabled` passe à faux, dispose) : `onTouchCancel` (= `stopLiveTouch`). Ne pas appeler `onCancel` depuis le teardown d'une annulation par clé (boucle de recomposition) : voir commentaire dans `LiveAnchorCanvas`.
4. **Clé d'annulation de geste** `uiState.liveGestureEpoch` : passée à tous les sliders/pad/wizard (`gestureCancellationKey`) ; une avance de la clé (STOP/perte/sortie de mode) annule le geste en cours SANS commit. Les variantes `...AtEpoch` capturent l'époque de la composition qui possède le geste (`scalarGestureEpoch`).
5. **Portes `streamingReady`** : pad Live et wizard activés seulement si `slidersEnabled && uiState.streamingReady` ; boutons de limites/REC : `connected && streamingReady`. Message de préparation (Warning 11 sp) tant que `!streamingReady`. `slidersEnabled = connected && !(isHoming || isPreflight)`.
6. **Session distante (Remote)** : `key(remoteSession.active)` recrée tous les contrôles à l'entrée/sortie de session (annule tout drag sans commit). En session, les sliders locaux affichent et règlent l'ENVELOPPE (maximums) : `speedDraft = remote.speedMax`, `depthRangeDraft = remote.depthMin..depthMax`, sensation = intensité symétrique `|s-0,5|`. À la fin de session : brouillons ← valeurs locales sûres (vitesse locale, profondeur/sensation effectives), aucune commande envoyée par ce seul passage. Synchronisation externe → brouillons seulement après 250 ms de calme (`CONTROL_DRAFT_QUIET_MS`, `ControlDraftEditClock`), immédiate au changement de session.
7. **Sensation bipolaire** : échelle −100…0…+100 (valeur 0..1, 0,5 = neutre), `signedSensationPercent = (v-0,5)*200`. Détente magnétique invisible au neutre (`snapValue = 0.5f`, rayon `SNAP_RADIUS_PERCENT = 4`), haptique `TextHandleMove` à l'entrée dans la détente. Remplissage depuis le centre, vert positif `#34C759`, rouge négatif `#FF453A`, tick bleu fixe `#3D8BFF`. Étiquette valeur signée avec vrai signe moins `−`.
8. **Case « Max »** (sensation maximale) : uniquement pour `teasingPounding` (`shouldShowMaxSensationToggle`), appelle `onRandomToggle(RandomTarget.SENSATION_MAX, ...)`, étiquette visible `ui2_max_short` = « Max », étiquette TalkBack `ctl_max_sensation` = « Sensation maximale ».
9. **Aléatoire** : cases uniquement pour `teasingPounding` et `simpleStroke` (`isRandomCapable`). Étiquette visible courte `ui2_random_short` = « Aléatoire », TalkBack complet par cible (vitesse/profondeur/sensation). La valeur envoyée à la machine s'affiche en ROUGE (`OssmError`) quand l'aléatoire pilote la valeur (vitesse : `randomSpeedCurrent`; profondeur : `randomDepthMin/MaxCurrent`; sensation : marqueur blanc).
10. **Garde-fous de changement brusque** : dialogue `AbruptChangeDialog` (bouton « Oui, je suis sûr » `ctl_abrupt_confirm` ; annuler → les brouillons vitesse/profondeur sont ré-alignés sur l'état et `onDismissPendingChange`).
11. **STOP BLE = `set:speed:0`** (côté Store, pas UI).
12. **Précision (opt-in par pattern)** : `LocalControlPrecisionEnabled` ; drag RELATIF (un tap ne téléporte pas, sauf hors rayon de saisie) ; rétrécit la plage visible après immobilité (curseur scalaire : paliers ±30/±20/±10/±5 toutes les 1,5 s ; Live : 10–90 puis ±40…±5 toutes les 2 s) avec haptique.
13. **+/− à maintien progressif** : 1 appui = ±1 % synchrone ; maintenu : 1,5 + 0,6·t³ %/s (≈100 % vers 5 s), commit unique au relâché ; ne PAS indexer le `pointerInput` sur `value` (la rampe filerait seule).
14. **Écrans scellés** : pas de défilement de page dans les modes à curseurs (`spreadControls`) ; seul le corps « ordinaire » défile (`verticalScroll`). Live et wizard possèdent leurs gestes.
    *Amendement redesign* : le corps d'un mode à curseurs défile en secours uniquement quand son contenu dépasse la hauteur disponible (petit écran, police agrandie) ; l'aiguillage « Inline sous 400 dp » en portrait est supprimé.

### 0.3 Code mort / non atteignable depuis l'UI actuelle (à confirmer avant de supprimer ou refaire)
- `RandomPresetRow`, `SliderGuardRow`, `RandomRangePresetDialog` : définis mais JAMAIS affichés (`editingRandomPreset` n'est jamais assigné ; `RandomPresetRow` et `SliderGuardRow` n'ont aucun appelant). Les callbacks `onRandomPresetSave/Apply/Edit` sont donc inertes côté UI (gestes prévus : clic = sauvegarder, double clic = appliquer, appui long = modifier).
- `StreamPositionSlider` n'est utilisé que dans l'assistant de plage (`rangeWizard != null`). `ControlViewModel.startRangeWizard()` n'est appelé par aucune UI (seulement un test, `ControlViewModelTest` l.1225) : l'assistant « Définir le fond / retrait » est inatteignable aujourd'hui, mais le code d'état et les chaînes existent.
- `HomeButton` et `StopButton` : aucun appelant (seul `StopBar` est utilisé). `PatternButton` : un seul appelant (sélecteur de patterns).
- `ProgressiveSpeedBar` a un libellé par défaut codé en dur « VITESSE (auto) » (non traduit, non en ressource) et ne l'écrase pas à l'appel.

---

## 1. `ControlScreen` (écran principal)

### 1.1 Structure et conditions d'affichage globales
Arbre : `Box(fond OssmBackground)` > `CompactControlScaffold` (testTag `control_main_surface`) > [rail d'actions] + [bandeau de statut] + `GlassCard` (testTag `control_card`) ; superposition `PatternPickerOverlay` ; dialogues.

**Orientation**
- `landscape = maxWidth > maxHeight`.
- Paysage + rail : `Row` (padding start 4, end 8, top/bottom 2 ; espace 6 dp) = colonne rail de largeur `StopBarHeight + 8` (64 dp) + colonne contenu.
- Autre (portrait, ou paysage sans rail) : `Column` padding horizontal 16 dp (portrait) / 8 (paysage) / 4 (bare), vertical 8 / 6 / 4 ; espace 8 dp. Rail portrait = ligne de hauteur 56 dp.
- Test androidTest : ni portrait 360×640 ni paysage 640×360 ne doivent avoir de défilement de page (`SemanticsActions.ScrollBy` absent sur `control_main_surface`) ; STOP et contrôle visibles.

**Statut de connexion** (`controlStatusKind`, ordre de priorité) : `USB` (connecté et adresse `"USB-C"`) > `DISCONNECTED` (pas connecté) > `CALIBRATING` (`isHoming || isPreflight`) > `ERROR` (`machineState.isError`) > aucun. Null en Live nu. Affichage : portrait = bandeau 1 ligne sous la barre STOP (`ControlStatusLine boxed singleLine`, testTag `control_status_line`) ; paysage = pastille dans l'en-tête de la carte (`ControlStatusPill`, testTag `control_status_pill`, max 240 dp).
 - Textes : DISCONNECTED = `ui2_status_disconnected_title` « OSSM non connecté » + ` · ` + `ui2_status_disconnected_hint` « Connectez-vous pour activer les commandes » (pastille : titre seul) ; USB = `ctl_usb_active` « USB-C actif · Bluetooth OSSM désactivé » ; CALIBRATING = `ui2_status_calibrating` « Calibration en cours (%1$s) — attendez la fin » (%1 = `machineState.displayLabel`, chaîne FR codée en dur dans `MachineState`) ; ERROR = `machineState.displayLabel` (« Erreur: <état> »).
 - Couleurs/icônes : USB = `OssmConnected` + CheckCircle ; DISCONNECTED = `OssmWarning` + BluetoothDisabled ; CALIBRATING = `OssmAccent` + Info ; ERROR = `OssmError` + Warning. Fond teinte 12 % (pastille 14 %), bordure 1 dp teinte 40–45 %. Sémantique fusionnée (`mergeDescendants`), icône décorative (`contentDescription = null`). Texte 13 sp semi-gras.
 - NB : `CompactActionRail` reçoit `statusKind = null` en dur (le statut n'est jamais dans la ligne STOP).

**Rail d'actions** (`CompactActionRail`) : visible si `showStop || showPause` (`showActionRail`).
- Portrait : `Row` espacement 10 dp : STOP (poids 1, hauteur 56) + Pause/Lecture (56) + verrou de rotation (44 de large × 56 de haut).
- Paysage : colonne verticale espacement 8 : STOP vertical (`S\nT\nO\nP`, largeur 56, poids 1) + Pause/Lecture (56) + verrou (56 × 44).

### 1.2 En-tête de la carte (ligne du haut de `control_card`)
Padding de la carte : 6 dp (Live nu) / 12 dp (paysage) / 16 dp ; `GlassCard` cornerRadius 20, teinte `OssmGlass` (6 % sur `OssmSurface`), bordure `OssmGlassBorder` 1 dp. Rangée espacement 8, padding bas 8.

| # | Élément | Libellé / contentDescription | Action | Visible / activé |
|---|---|---|---|---|
| 1 | `PatternOpenerButton` (IconButton 48×48, coins 12, fond `OssmSurfaceRaised`, bord `OssmOutline`, icône TouchApp 22 dp `OssmPrimaryLight`) | cd `ctl_patterns_open` « Ouvrir les patterns » | ouvre `PatternPickerOverlay` (`patternPickerOpen = true`) | toujours visible et actif (même déconnecté) |
| 2 | Titre | `controlHeaderTitle(activePattern)` = nom du pattern actif ou chaîne vide (`titleMedium`, gras, 1 ligne, ellipsis) | - | toujours |
| 3 | `ControlStatusPill` | voir 1.1 | - | paysage ET statut non nul |
| 4 | `LiveLimitButton` HAUT (`LiveLimitSide.UPPER`) | cd selon état : `ctl_limit_upper_add` « Ajouter une limite haute » / `ctl_limit_upper_moving` « Limite haute : déplacez la ligne, puis touchez pour la verrouiller » / `ctl_limit_upper_locked` « Limite haute verrouillée : touchez pour la retirer » | `limits.toggle(UPPER)` : absent → en déplacement → verrouillé → absent | `liveControlActive` ; activé si `connected && streamingReady` |
| 5 | `LiveLimitButton` BAS | idem `ctl_limit_lower_*` | `limits.toggle(LOWER)` | idem |
| 6 | `LiveRecordButton` | cd `ctl_rec_idle` « Enregistrer un mouvement » / `ctl_rec_armed` « Pret » / `ctl_rec_recording` « REC » / `ctl_rec_recorded` « Rejouer le mouvement » / `ctl_rec_playing` « Arreter la boucle » | `onLiveRecordToggle` (= `toggleLiveRecording`) | `liveControlActive` ; activé si `connected && streamingReady` ; 48×48, point rouge 14 dp (18 dp en REC), bord rouge si état ≠ IDLE |
| 7 | `VoiceInfoButton` | (dans `VoiceControlPanel.kt`, testTag `voice_info`) | dialogue d'info Voix | seulement si pattern Voix |
| 8 | `SettingsGearButton` | cd `ctl_control_settings` « Réglages du contrôle » (48×48, icône Settings) | ouvre `ControlSettingsDialog` | dès qu'un pattern est actif (`activePattern != null`) |
| 9 | Bouton Enregistrer (profil) | cd `ctl_save` « Enregistrer » (icône Save, 48×48) | ouvre `SavePresetDialog` | `activePattern != null && connected && showSave` (showSave = pattern non null et non Live) |

Limites Live : `LiveLimitsState` est un `remember` local au composable de contenu (non persisté, perdu à la sortie du composable). Défauts : ligne haute à 20 % de la hauteur, basse à 80 % ; écart minimal 6 % ; une nouvelle ligne remplace l'édition d'une autre (l'autre reste en place). Icône : `LiveLimitIcon` (canvas 24 dp, flèche + barre). État affiché UNIQUEMENT par la couleur de l'icône (`OssmAccent` en déplacement, `OssmPrimaryLight` verrouillée, `OnSurface 80 %` absente) et l'épaisseur de bordure (2 dp vs 1 dp) en plus du contentDescription : voir points faibles.

### 1.3 Bandeau de session distante (`RemoteSessionBanner`, testTag `remote_session_banner`)
Affiché si `remoteSession.active`, sous l'en-tête (toutes modes, y compris Live). Fond `OssmSurfaceRaised`, coins 10, bord 1 dp accent 60 % (`OssmWarning` si `motionBlockedByZeroMaximum`, sinon `OssmPrimaryLight`), padding 10×6.
Lignes (12 sp, interligne 16) :
1. `ui2_remote_banner_limits` « Session distante : maximums %1$d %% / %2$d–%3$d %% » + ` · ` + `ui2_remote_banner_sensation_range` « Sensation %1$s (%2$s–%3$s) » (intensité `±D`, bornes signées).
2. Si `speedMax <= 0` : `ui2_remote_blocked_zero` « Maximum à 0 % : le site ne peut rien faire bouger, montez le curseur » (`OssmWarning`).
3. En direct : `ui2_remote_live_prefix` « Envoyé par le site : … » (éléments `ui2_remote_live_speed` / `_depth` / `_sensation` / `_pattern` / `_position`), ou `ui2_remote_live_waiting` « Le site n'a encore rien envoyé ».
Information d'état transmise aussi par la couleur du liseré (avertissement/normal) mais aussi par le texte : OK.

### 1.4 Corps de la carte : sélection de contenu (`when`, dans `key(remoteSession.active)`)
Ordre de priorité exact :
1. `rangeWizard != null` : assistant de plage (voir 1.5.1).
2. `activePattern == null` : texte `ctl_select_pattern_to_start` « Sélectionnez un pattern pour commencer. » (13 sp, 70 %).
3. `mode == LAUNCH_ONLY` : `ctl_launch_only_hint` « Pattern lancé — aucun paramètre à ajuster. ».
4. `isVoicePattern()` : `VoiceControlPanel(...)`.
5. `mode == STREAMING` : Live (1.5.2).
6. `mode == PROGRESSIVE` : Progressif (1.5.3).
7. `mode == NATURAL` : `NaturalControlPanel` avec `depthEditor` injecté (1.5.4).
8. `mode == AUTO_RANDOM` : mix Auto Random (1.5.5).
9. `else` (STROKE_ENGINE : simpleStroke, teasingPounding, halfNHalf, etc.) : Vitesse + Profondeur (+ Sensation) (1.5.6).

Gabarit : `BoxWithConstraints` ; métriques des curseurs (`LocalControlSliderMetrics`) : si `!spreadControls || !bodyBounded` → Regular ; sinon `compactLandscape || bodyViewport < 400 dp` → Inline ; `>= 560 dp` → Large ; sinon Regular. `spreadControls` = corps défilant ET (STROKE_ENGINE ou PROGRESSIVE) ; alors `Arrangement.SpaceEvenly` et `fillMaxSize` (pas de défilement). `scrollBody = !liveControlActive && rangeWizard == null`.

### 1.5 Contenu par mode

#### 1.5.1 Assistant de plage (inatteignable aujourd'hui, voir 0.3)
- Texte étape : `ctl_wizard_step1` « Étape 1/2 — Place la machine au FOND désiré avec le slider, puis appuie sur « Définir le fond ». » / `ctl_wizard_step2` (retrait) ; 13 sp.
- Si `!streamingReady` : `ctl_wizard_preparing` « Préparation… Machine : %1$s » (+ `ctl_knob_to_zero` si préflight), `OssmWarning` 90 %, 11 sp.
- `StreamPositionSlider` (activé `slidersEnabled && streamingReady`, `fillAvailable=false`, hauteur fixe 240 dp / 180 dp compact). Relâché ET annulation = `onStreamTouchCancel` (STOP seulement, JAMAIS le retour lent).
- Bouton `ctl_wizard_set_depth` « Définir le fond » / `ctl_wizard_set_retract` « Définir le retrait » → `onRangeWizardCapture`, activé `slidersEnabled && streamingReady`, fond `OssmConnected`, min 48 dp. Bouton `ctl_cancel` « Annuler » → `onRangeWizardCancel`, activé `connected`, fond `OssmWarning`.
- Étape 2 avec `capturedMax` : `ctl_wizard_depth_set` « Fond défini : %1$d %% » (`OssmConnected`, 12 sp).
- Aucun `Pas de set:depthMin/depthMax` : la plage min/max devient `stroke = max - min`, `depth = max` (Store).

#### 1.5.2 Live (mode STREAMING non Voix) : `LiveAnchorPad`
- Message de préparation si `!streamingReady` : `ctl_live_preparing` « Préparation du mode Live — la machine confirme la pleine course… » + `ctl_machine_state` « Machine : %1$s » + `stroke=/depth=/speed=` si connus + `ctl_knob_to_zero_mode` si préflight ; `OssmWarning` 90 %, 11 sp.
- Pad : `weight(1f).fillMaxWidth()` ; `fillAvailable=true` → `fillMaxHeight`, hauteur mini 420 dp (180 dp compact), largeur mini 280 dp. Coins 20 dp, dégradé vertical (`OssmPrimary` 16 %, `OssmAccent` 10 %, `OssmSurfaceRaised`; ×0,4 si désactivé), bordure `OssmOutline` 1 dp (40 % si désactivé), padding 8×6 (6×4 compact).
- Entête interne : légende `live_radial_legend` « Zéro en bas · montez = plus profond » (12 sp gras `OssmPrimaryLight`, 2 lignes max) + pourcentage courant `"${draft}%"` (24 sp extra-gras, 20 sp compact ; 35 % d'opacité si désactivé).
- Canvas (toute la surface est tactile). Dessin pendant le geste : demi-plan bas teinté (`OssmOutline` 22 %), ligne d'ancre `OnSurface` 55 %, texte `live_anchor_below` « reste à %1$d » (11 sp), 4 demi-cercles de portée à 25/50/75 % + 100 % en pointillé `OssmAccent` 55 %, graduations numériques (11 sp, 55 %), trait ancre→doigt (`OssmAccent` 75 %), marqueur d'ancre (point 8 dp + anneau accent 14 dp), pouce du doigt (rayon 36 dp, 28 dp compact ; accent si au-dessus de l'ancre, `OssmOutline` sinon). Piste de pouce `liveSliderTrackThickness` 28/20 dp.
- Lignes de limites (si posées) : zone interdite `OssmOutline` 28 %, trait 2,5 dp (3,5 dp pointillé et `OssmAccent` si en déplacement, sinon `OssmPrimaryLight`). Pendant le déplacement d'une ligne, le doigt glisse la ligne et ne pilote PAS la machine (« la machine reste intacte »). Verrouillé : la position du doigt est bornée entre les deux lignes (`clampY`), le zéro = ligne basse.
- Minimum de portée d'ancre : `LIVE_ANCHOR_MIN_REACH_DP = 48` (ancre au bord haut).
- Premier contact : émet la valeur sous le doigt (`onChange(atDown)`), la machine y monte lentement via la session.
- Accessibilité TalkBack (si activé) : `contentDescription = live_slider_description` « Position Live du chariot », `stateDescription = live_state_description` « %1$s, position %2$d pour cent » avec `live_range_global` « Live 0–100 », `ProgressBarRangeInfo(draft, 0..100)`, `setProgress` (envoie la position puis `onTouchEnd`, c.-à-d. retour lent), actions personnalisées `live_radial_increase` « Avancer de 10 pour cent puis retour lent » et `live_radial_decrease` « Reculer de 10 pour cent puis retour lent » (pas `LIVE_ANCHOR_ACCESSIBILITY_STEP_PERCENT = 10`).
- Le pad se désactive (`enabled=false`) → appelle `onTouchCancel` s'il y avait un geste ; à la destruction aussi (`DisposableEffect`).
- Dessin incomplet pour daltoniens : la distinction « au-dessus/au-dessous de l'ancre » repose sur couleur accent vs `OssmOutline` pour le pouce ET le demi-plan teinté (forme présente) : acceptable, mais les graduations sont à 55 % d'opacité.
- `LiveSliderState.kt` / `StreamPositionSlider` (piste verticale) sont l'ancienne mécanique (précision 10–90 puis ±40…±5 toutes les 2 s) utilisée par le wizard seulement.

#### 1.5.3 Progressif (PROGRESSIVE)
1. `ProgressiveSpeedBar` : libellé codé « VITESSE (auto) » ; valeur affichée `"{courante}% · max {max}%"` ; remplissage = vitesse courante (lecture seule, `OssmPrimary`), barre rouge `OssmError` = maximum déplaçable (saisie dans un rayon de saisie sinon saut sous le doigt). Appelle `onProgressiveMaxCommit` à CHAQUE changement (pas de relâché distinct). `maxValue` = `remoteSession.speedMax` si session. Activé `slidersEnabled`. AUCUN setProgress ni boutons −/+ (non accessible TalkBack au-delà de description + état).
2. `DepthRangeEditor` (profondeur, voir 1.6). Marqueurs : position observée + valeurs aléatoires courantes.
3. `CenteredSensationSlider` (voir 1.6) sans `headerAccessory` (pas d'aléatoire).
4. Case `ctl_chaos_title` « Mode aléatoire au max » / sous-texte `ctl_chaos_hint` « Au plafond, coups variés et imprévisibles » (14 sp / 11 sp à 60 %) : `Checkbox` 56×56 dp, couleur cochée `OssmAccent`, décochée `OnSurface` 50 %, `onChaosToggle`, activé `connected` (PAS `slidersEnabled`). Attention : seule la case (pas le texte) est cliquable ici (le libellé n'est pas dans la zone `toggleable`) ; contraire des autres cases.

#### 1.5.4 Naturel (NATURAL)
`NaturalControlPanel` (hors périmètre détaillé) reçoit `depthEditor = { DepthRangeEditor(... randomMin/Max = naturalDepthMin/MaxCurrent ...) }`, `uiState`, `remoteSession`, `connected`, `slidersEnabled`, `naturalActions`. testTags du panneau : `natural_panel`, `natural_start`, `natural_stop`, `natural_ai_section`, `natural_ai_address`, `natural_ai_reasoning_relay`, `natural_ai_model_state`, `natural_ai_load`, `natural_ai_retry`, `natural_ai_use`, `natural_ai_stop_use`, `natural_ai_status`, `natural_ai_fallback`, `natural_ai_reserve`, `natural_ai_waiting`.

#### 1.5.5 Auto Random (AUTO_RANDOM)
Tout dans une colonne qui défile (pas `spreadControls`).
- Intro `ctl_auto_intro` « Choisis les modes à mélanger, fixe tes limites, puis démarre le mix aléatoire. » (12 sp, 70 %).
- En-tête `ctl_auto_modes_header` « MODES À MÉLANGER » (11 sp gras `OssmPrimaryLight`, espacement lettres 1).
- Une ligne par pattern de `AutoRandomMixablePatterns` : `AutoRandomPatternRow` = nom du pattern (14 sp) + `Checkbox` 56×56 (bord `OssmGlassBorder`, ligne arrondie `shapes.medium`) → `onAutoToggleSelected(key)`. ATTENTION : la ligne entière n'est PAS cliquable, seulement la case ; la case n'est pas désactivable (toujours active, même déconnecté).
- En-tête `ctl_auto_speed_header` « VITESSE MAX ».
- `ControlSlider` « Plafond du mix » (`ctl_auto_speed_ceiling`, `OssmPrimary`) : valeur `remoteSession.speedMax` si session sinon `autoMaxSpeed` → `onAutoMaxSpeedChange` (live ET commit identiques).
- `ControlSlider` « Intensité max » (`ctl_auto_intensity_cap`, `OssmAccent`) → `onAutoIntensityCapChange`.
- `ControlSlider` « Aléatoire » (`ctl_auto_randomness`, `OssmWarning`) → `onAutoRandomnessChange`. (NB : le mot « Aléatoire » désigne ici une quantité, ≠ des cases Aléatoire.)
- `DepthRangeEditor` (marqueurs aléatoires = `randomDepth*Current`).
- Si `autoRunning` : `GlassCard` teinte `OssmConnected` : « Intensité %1$d%% » (`ctl_auto_intensity_value`, vert gras) + `ctl_auto_running_hint` « Le mix monte progressivement en intensité sans dépasser tes limites. ». Sinon `ControlSlider` « Intensité de départ » (`ctl_auto_initial_intensity`, `OssmConnected`) → `onAutoInitialIntensityChange`.
- Boutons : « Demarrer » (`ctl_start`, vert `OssmConnected`, `onAutoRequestStart`, activé `slidersEnabled && !autoRunning && autoSelectedKeys.isNotEmpty()`) et « Arreter » (`ctl_stop_mix`, jaune `OssmWarning`, `onAutoStop`, activé `connected && autoRunning`) ; min 48 dp, texte 16 sp interligne.
- Dialogue de confirmation `AutoRandomStartDialog` quand `uiState.pendingAutoStart` : titre `ctl_auto_dialog_title` « Vérifie tes limites » ; corps `ctl_auto_dialog_count`, `ctl_auto_dialog_limits` (vitesse max + profondeur min–max), avertissement `ctl_auto_dialog_warning` (`OssmWarning`, 12 sp) ; boutons `ctl_start` (→ `onAutoConfirmStart`) / `ctl_cancel` (→ `onAutoCancelStart`, texte `OssmPrimaryLight`).

#### 1.5.6 Stroke Engine (else) : Vitesse, Profondeur, Sensation
`isRandomCapable` = `key in {teasingPounding, simpleStroke}`.
1. **Vitesse** (`ControlSlider`, libellé `ctl_speed` « Vitesse », `OssmPrimary`) : valeur `speedDraft`, `onValueChange` → brouillon + `markLocalEdit` + `dispatchSpeedLive` ; `onValueCommit` → `dispatchSpeedCommit`. Marqueur `currentMarker = randomSpeedCurrent`. Valeur affichée `"${(randomSpeedCurrent ?: speedDraft)*100}%"` en rouge si aléatoire. Activé `slidersEnabled`. Accessoire d'en-tête (si random-capable) : `RandomToggle` cd `ctl_random_speed` « Vitesse aléatoire », visible « Aléatoire », `checked = randSpeed`, `onRandomToggle(SPEED, ·)`.
2. **Profondeur** (`DepthRangeEditor`, libellé `ctl_depth` « PROFONDEUR ») : voir 1.6. Accessoire : `RandomToggle` cd `ctl_random_depth` « Profondeur aléatoire », `checked = randDepthMin || randDepthMax`, `onDepthRandomToggle`.
3. **Sensation** (`CenteredSensationSlider`) si `shouldShowSensationSlider` (STROKE_ENGINE et `key != simpleStroke`) : accessoires `RandomToggle` `ctl_random_sensation` « Sensation aléatoire » (`randSensation`, `RandomTarget.SENSATION`) ; si `teasingPounding`, `RandomToggle` `ctl_max_sensation` « Sensation maximale » (visible « Max », `maxSensation`, `RandomTarget.SENSATION_MAX`). Plage aléatoire `randomSensationMin..Max` seulement si `isRandomCapable && sensationTurnActive`.
   Le dialogue `AbruptChangeDialog` s'ouvre sur `uiState.pendingManualChange` (vitesse ou profondeur).

### 1.6 Composants de curseur (tous modes à curseurs)

**`ControlSlider`** (`components/ControlSlider.kt`) : `SliderFrame` (en-tête : libellé MAJUSCULES `OssmPrimaryLight` gras + accessoire + valeur à droite) + rangée `[−][piste][+]`.
- Piste `PrecisionScalarSlider` : Canvas hauteur `metrics.sliderHeight`, piste arrondie (`trackHeight`), remplissage couleur de l'activité, pouce = barre verticale arrondie (`thumbWidth × thumbHeight`), marqueur courant (trait 3 dp, `OssmError` 95 %), tick central bipolaire. Geste : zone de saisie (`grabRadius`) → prise relative ; hors zone → saut sous le doigt (avec détente éventuelle) ; le glissé est relatif ; commit au relâché (`changedToUpIgnoreConsumed`) ; annulation (désactivation, clé, perte) = pas de commit et retour à la valeur parent.
- `RampButton` +/− : cercle `buttonSize`, fond accent 22 % (10 % si désactivé), glyphe `−`/`+`. Sémantique : cd `control_slider_decrease` « Diminuer %1$s » / `control_slider_increase` « Augmenter %1$s », rôle Button, `onClick` accessibilité = un pas (1 % de la plage) + commit.
- Sémantique de la piste : `contentDescription = label`, `stateDescription = precision_state_description` « %1$s, valeur %2$d pour cent » (avec `precision_range_global` « Plage 0–100 » ou `precision_range_level` « Précision ±%1$d · %2$d–%3$d »), `ProgressBarRangeInfo(0..100)`, `setProgress` (applique ET commit), `disabled()` si inactif.
- Étiquette de plage (« Plage 0–100 / Précision ±N · a–b ») visible seulement si la précision est active ou si un palier est atteint (11 sp ; couleurs : 55 % opacité global / `OssmPrimaryLight` ±30,±20 / couleur d'activité ±10,±5).
- Test androidTest : la cible sémantique d'un `ControlSlider` (nœud avec `ProgressBarRangeInfo`) doit mesurer au moins 56 dp de haut (`sliderHeight` Regular = 56).

**Métriques (`ControlSliderMetrics.kt`)** :

| | Regular | Large | Inline |
|---|---|---|---|
| sliderHeight | 56 | 88 | 52 |
| trackHeight | 10 | 16 | 12 |
| trackInset | 10 | 12 | 10 |
| thumb L × H | 4 × 44 | 6 × 70 | 5 × 42 |
| buttonSize | 56 | 72 | **48** |
| label sp | 13 | 16 | 12 |
| value sp | 28 | 36 | 20 |
| symbol sp | 30 | 38 | 26 |
| grabRadius | 28 | 40 | 30 |
| toggleMinSize | 48 | 48 | **36** |
| inlineHeader / légendes d'échelle | non / oui | non / oui | oui (label 118 dp, valeur 84 dp) / non |

**`DepthRangeEditor`** (profondeur, double barre) : `SliderFrame` libellé `ctl_depth`, valeur `"${min}–${max}%"` (affiche les bornes ALÉATOIRES courantes si présentes, en rouge `OssmError`; gris 40 % si désactivé). `RangeBarControl` (−, piste à 2 barres, +) couleur `OssmAccent`. Marqueurs rouges : position observée (`ObservedPositionRange.markerFraction`, `OssmError` 90 %) + bornes aléatoires courantes (`OssmError`). Les boutons −/+ déplacent la barre touchée en dernier (haute par défaut), pas de 1 %, un tap = un commit (PAS de maintien progressif ici, différent de `ControlSlider`). Halo discret 22 % sur la barre sélectionnée. Sémantique de la piste : `contentDescription = label`, `stateDescription = "a% – b%"` ; AUCUN `setProgress` (pas pilotable au glissé via TalkBack, seulement via −/+). Barres distinctes : si les deux barres sont superposées (< 0,001), on choisit selon le côté du doigt.
 - `ObservedPositionRange` : n'affiche le marqueur que si la course observée dépasse 5 mm ; réinitialisé à chaque nouvelle connexion (`remember(connectionSessionAddress)`) ; alimenté par `machineState.positionMm`. (Test `ObservedPositionRangeTest`.)

**`CenteredSensationSlider`** :
 - Mode normal : `ControlSlider` libellé `ctl_sensation` « Sensation » (ou, EN SESSION : `ctl_sensation_intensity_max` « Sensation : intensité max permise au site »), bipolaire, détente 0,5, couleur d'activité `OssmAccent`. Valeur affichée : `signedSensationLabel` (« +40 », « 0 », « −20 »), ou `±D` en session (`sensationIntensityLabel`), ou la valeur aléatoire courante. Couleur de la valeur : verte/rouge selon le signe (hors session). Marqueur : valeur aléatoire courante, ou en session la sensation utilisée par le site (`remoteSession.remoteSensation`).
 - Mode aléatoire (`randomRange != null`) : `RangeBarControl` bipolaire (bornes de la plage aléatoire, `onSensationRandomRangeChange` live+commit), marqueur blanc `OssmOnSurface` = valeur envoyée, valeur affichée = courante ou « a / b ».
 - Légende d'échelle (si `showScaleLegends`, donc pas en Inline) : « −100 » · `ctl_sensation_neutral` « 0 = neutre » · « +100 » (11 sp, `OssmOnSurfaceMuted`) + légende de sens `ctl_sensation_legend_out_in` « ← retrait rapide · avancée rapide → » (11 sp) seulement pour `teasingPounding` et `halfNHalf` (`sensationLegendRes`). Les valeurs « −100 » / « +100 » sont en dur (non traduites, non localisables).

### 1.7 Panneau Voix (extrait utile au contrat)
`VoiceControlPanel(uiState, connected, slidersEnabled, actions = voiceActions, voiceAi, modelRoot)`. testTags : `voice_panel`, `voice_asr_engine`, `voice_listen`, `voice_short_word_stop`, `voice_transcript`, `voice_latency`, `voice_interpret_source`, `voice_transcript_ignored`, `voice_state`, `voice_motif`, `voice_depth_range`, `voice_info`, `voice_info_dialog`, `voice_model_state`. Actions : `onStopNow`, `onMaxSpeedChange`, `onDepthRangeChange`, `onShortWordStopChange`, `onListenChange`, `hasMicrophonePermission`, `onMicrophonePermissionResult`. Voix partage l'entrée STREAMING mais N'EST PAS du Live (pas de pad, écran non nu).

### 1.8 Sélecteur de patterns (`PatternPickerOverlay`)
- Ouvert par le bouton 1.2#1 ; fermé par : bouton ✕ (`ctl_patterns_collapse` « Fermer », 48×48), choix d'un pattern, ou touche Retour (`BackHandler(enabled = patternPickerOpen)` enregistré après le gestionnaire global, prioritaire).
- Surcouche alignée haut-centre ; paysage : début 112 dp, haut 8 ; portrait : haut 100 dp, côtés 12, bas 8 ; coins 20, fond `OssmBackground`, contenu dans `GlassCard`.
- Titre `ctl_patterns` « PATTERNS  ▾ » (`titleMedium` gras) + sous-titre `ctl_patterns_hint` « Choisis un mode; cette fenetre se referme apres la selection. » (`bodyMedium`, `OssmOnSurfaceMuted`).
- Vide : `ctl_no_patterns` « Aucun pattern détecté pour ce firmware. ».
- Grille 2 colonnes, hauteur de ligne = hauteur disponible répartie, bornée 52–120 dp, défile si trop de patterns ; une cellule seule → espaceur. Chaque `PatternButton` : étiquette = nom ; couleur = `PatternColors[index % 3]` (primaire, accent, warning) ; actif = fond plein de la couleur + texte `OssmBackground`, inactif = fond 12 % + bord 1 dp 50 % + texte de la couleur ; taille de police 16 sp si ligne ≥ 72 dp sinon 13 sp ; `onClick` → `onPattern(key)` puis fermeture ; `onLongClick` → `ReorderPatternsDialog` (ouvre par-dessus). État actif signalé UNIQUEMENT par le remplissage (aucun `selected`/stateDescription ; voir faiblesses).
- Éléments : `PatternButton` hauteur FIXÉE à 52 dp en interne mais le parent impose `.height(rowHeight)` (le dernier modificateur gagne selon l'ordre : ici `modifier` est appliqué en premier, donc `rowHeight` l'emporte puis `.height(52)` ne s'applique pas ; à vérifier visuellement lors du redesign). Rôle `Button`.

### 1.9 Dialogue de réorganisation (`ReorderPatternsDialog`, AlertDialog)
Titre `ctl_reorder_title` « Réorganiser les patterns » (17 sp) ; indication `ctl_reorder_hint` « Appui long puis glisse pour déplacer. » (12 sp, 60 %) ; une ligne par pattern : glyphe « ☰ » (codé en dur, 14 sp) + nom (15 sp), appui long + glisser (`detectDragGesturesAfterLongPress`), échange quand le déplacement dépasse la moitié de la hauteur de ligne ; ligne en glisse = fond `OssmPrimary` 35 %, alpha 0,85 ; fond du dialogue 75 % pendant le glisser. Boutons : `ctl_save` « Enregistrer » → `onPatternOrderSave(keys)`, `ctl_cancel` « Annuler » (texte 70 %). Réorganisation sans alternative accessible (aucune action TalkBack « monter/descendre »).

### 1.10 Dialogue des réglages du contrôle (`ControlSettingsDialog`, `Dialog` plein écran personnalisé)
Surface coins 24, `OssmSurface`, bord `OssmOutline`, largeur max 720 dp, hauteur max 90 %. Titre `ctl_control_settings` (`titleLarge` gras) + ✕ (48×48, cd `ctl_close_settings` « Fermer les réglages »). Corps défilant. Bouton final `ctl_understood` « Compris » (`OssmPrimary`/`OssmOnPrimary`, aligné à droite, taille par défaut M3 = 40 dp de haut).
Rangées (chaque rangée `SettingsToggleRow` : ligne ≥ 56 dp, libellé + interrupteur dans une seule zone `toggleable(role=Switch)` ; bouton info « appui long » 48×48 à côté) :
| Rangée | Libellé | Info (appui long) | Callback | Visible |
|---|---|---|---|---|
| Précision | `ctl_precision_option` « Précision » | `ctl_precision_option_hint` | `onPrecisionEnabledChange(patternKey, bool)` | toujours |
| Accélération Live | `ctl_live_acceleration_value` « Accélération Live : %1$d%% » + `PrecisionScalarSlider` 10–100 % (mappé 0–100) min 56 dp | `ctl_live_acceleration_hint` | `onLiveAccelerationChange(Int 10..100)` au relâché | pattern STREAMING |
| Inverser Live | `ctl_live_invert` « Inverser le sens du Live » | `ctl_live_invert_hint` | `onLiveInvertToggle` | pattern STREAMING |
| Intro protections | `ctl_guards_intro` | - | - | toujours |
| Garde vitesse | `ctl_speed` « Vitesse » | `ctl_speed_guard_hint` | `onSpeedGuardEnabledChange` | toujours |
| Garde profondeur | `ctl_depth_guard` « Profondeur » | `ctl_depth_guard_hint` | `onDepthGuardEnabledChange` | toujours |
| Limites de profondeur | `ctl_depth_limits_value` « Limites de profondeur : %1$d%%–%2$d%% » + `RangeSlider` M3 (0..1) | `ctl_depth_limits_hint` | `onDepthLimitsChange(min, max)` au `onValueChangeFinished` | toujours |
| Texte d'aide STOP | `ctl_stop_info_title` « STOP » / `ctl_stop_info_hint` « Coupe immédiatement le mouvement en ramenant la vitesse à zéro. » | - | - | toujours |
| Texte d'aide Pause | `ctl_pause_info_title` « Pause/Play (jaune/vert) » / `ctl_pause_info_hint` | - | - | toujours |
Info « appui long » (`PressHoldInfo`) : cercle 48 dp, icône Info 20 dp ; geste = tenir pour afficher une infobulle flottante (13 sp, `OssmSurfaceRaised`, bord `OssmPrimaryLight`) ; disparaît au relâché ou au-delà du `touchSlop` (`shouldKeepHoldInfoVisible`) ; TalkBack : rôle Button, cd `ctl_hold_for_info` « Maintenir pour afficher l'explication », `stateDescription = explication`, double-tap = basculer l'affichage. Le slider de limites utilise le `RangeSlider` M3 par défaut (pas la barre maison) : style incohérent, hauteur M3 standard, sans libellé accessible propre (le titre n'est pas associé).
La plage de limites est une valeur locale (`depthLimits`) rafraîchie par `LaunchedEffect(depthLimitMin, depthLimitMax)`.

### 1.11 Autres dialogues
- **`SavePresetDialog`** (AlertDialog, `OssmSurface`) : titre `ctl_preset_save_title` « Sauvegarder le profil » ; champ `OutlinedTextField` (libellé `ctl_preset_name_label` « Nom du profil », une ligne, valeur initiale `ctl_preset_default_name` « Mon Profil ») ; bouton `ctl_preset_save` « Sauvegarder » (crée `Preset(name ou ctl_preset_fallback_name « Profil », patternKey, patternName, speed, depthMin, depthMax)` → `onSavePreset`), `ctl_cancel`. Affiché seulement si `showSaveDialog && activePattern != null && showSave`. Note : le profil sauvegarde `uiState.speed/depthMin/depthMax`, PAS la sensation.
- **`AbruptChangeDialog`** : titre `ctl_abrupt_title` « Changement brusque détecté » ; corps `ctl_abrupt_body` (avec `ctl_abrupt_speed` « la vitesse » / `ctl_abrupt_depth` « la profondeur » et seuil %) ; deux interrupteurs (`GuardCheckboxRow`, 56 dp min) `ctl_abrupt_skip_session` « Ne pas me le rappeler pour cette session » et `ctl_abrupt_never` « Ne plus jamais me le redemander » ; boutons `ctl_abrupt_confirm` « Oui, je suis sûr » (fond `OssmWarning`, → `onConfirmPendingChange(skipSession, never)`) et `ctl_cancel` (texte `OssmError`, ré-aligne `speedDraft`/`depthRangeDraft` puis `onDismissPendingChange`). Fermer par dismiss = même effet que Annuler.
- **`RandomRangePresetDialog`** : inatteignable (0.3).

### 1.12 Verrou de rotation, Pause/Lecture, STOP
- **`RotationLockButton`** : `toggleable(role = Switch)`, cd `ctl_rotation_lock` « Bloquer la rotation de l'écran » / `ctl_rotation_unlock` (pas de `stateDescription` séparé : l'état est dans le libellé). Modifie `activity.requestedOrientation` (LOCKED / UNSPECIFIED), NE TOUCHE PAS au réglage système. Taille 44×56 (portrait) ou 56×44 (paysage) : **44 dp sur un côté < 48 dp**. Verrouillé : fond `OssmPrimary` 28 %, bord `OssmPrimary`, icône ScreenLockRotation `OssmPrimaryLight` ; sinon fond `OssmSurfaceRaised`, bord `OssmOutline`, icône `OssmOnSurfaceMuted`. Caché en Live nu.
- **`PausePlayButton`** : cercle (taille 56 −4 = 52 dp de zone `clickable`, **< 56 mais ≥ 48**), fond `OssmWarning` (en marche, icône Pause) / `OssmConnected` (en pause, icône Play), icône `OssmBackground` 30 dp ; désactivé : fond `OssmSurfaceRaised`, icône `OssmOnSurfaceMuted`. cd `ctl_pause` « Pause » / `ctl_resume` « Reprendre ». Actif seulement si `connected && mode != STREAMING && (isRunning || isPaused)`. Visible si `!isLive`. État principalement signalé par couleur jaune/vert ET par l'icône (forme) : OK.
- **`StopBar`** (`components/StopBar.kt`) : `Button` M3, hauteur mini 56 dp (`StopBarHeight`), coins 14, fond `OssmStop` `#BA2444`, bord 2 dp `OssmError`, texte « STOP » 22 sp extra-gras, espacement 3 sp (vertical : `S\nT\nO\nP`, espacement 0, padding 0), couleur de contenu `OssmOnSurface`. cd `control_stop_bar_cd` « STOP, arrêt immédiat » (le texte visuel est masqué à TalkBack via `clearAndSetSemantics`). testTag `control_stop` posé par `CompactActionRail`. Premier dans l'ordre TalkBack (premier dans la `Row`). Callback `onStop` → `controlVm.stop()`. Jamais désactivé (pas de `enabled`).
- `StopButton` (rond 96 dp, « STOP » 18 sp) et `HomeButton` (rond 72 dp, « HOME » 13 sp blanc, dégradé primaire→accent) : non utilisés aujourd'hui.

### 1.13 Brouillons et synchronisation (comportement critique)
`speedDraft`, `depthRangeDraft`, `sensationDraft` sont écrits SYNCHRONEMENT par le doigt ; `uiState` arrive une frame plus tard. Un `LaunchedEffect` (clés : speed, depthMin/Max, sensation, activePatternKey, remote.active/speedMax/depthMin/Max/sensationMin/Max) recopie l'état externe vers les brouillons seulement après 250 ms sans édition locale (`ControlDraftEditClock`), toujours depuis le DERNIER état (`rememberUpdatedState`), et immédiatement au changement `remote.active`. Tout redesign doit garder : (a) écriture synchrone du brouillon au geste, (b) `markLocalEdit` sur chaque tick, (c) ré-synchronisation à calme. Tests : `ControlDraftEditClockTest`.

---

## 2. `ScanScreen` (onglet Connexion)

Appelant : `MainActivity` l.472. Paramètres : `connectionState`, `devices: List<BleDevice>`, `onScan`, `onConnect(address)`, `onStop`, `remoteCamera`, `onShareMyCamera`, `onStartRemoteSession`, `onRegenerateRemoteLink`.
Callbacks câblés : `onScan` = `if (!isBluetoothEnabled()) onEnableBluetooth() else bleVm.startScan()` ; `onConnect` = `bleVm.connect(address)` ; `onStop` = `bleVm.stopScan` ; `onStartRemoteSession` = `remoteSessionVm::start` ; `onRegenerateRemoteLink` = `remoteSessionVm::regenerate` ; `onShareMyCamera` = ouvre `CameraShareScreen` plein écran avec l'URL (si `Available`).

Comportements automatiques (à préserver) :
- Au premier affichage : lance `onScan()` si ni connecté, ni en connexion, ni en scan.
- **Connexion automatique** : `scanAutoConnectAddress` = adresse du SEUL appareil `isOssm` de la liste, uniquement tant que l'état est `Scanning` (jamais sur résultat périmé hors scan). Tests : `ScanScreenAutoConnectTest`.

Éléments :
| # | Élément | Texte | Action | État |
|---|---|---|---|---|
| 1 | En-tête : logo `ic_launcher_foreground` 48 dp (cd null), titre « OSSM Remote » (`headlineMedium`, **codé en dur**), « v{VERSION_NAME} » (12 sp muted), `BleStatusIndicator` | - | - | toujours |
| 2 | Bouton scan (min 56 dp, coins 14) | `scan_devices` « Scanner les appareils BLE » (icône Bluetooth) ou, pendant le scan, `scan_stop` « Arrêter le scan » (spinner 20 dp, fond `OssmError`) | `onScan` / `onStop` | désactivé si `isConnecting || isConnected` ; fond `OssmPrimary` ou `OssmError` pendant le scan |
| 3 | `RemoteCameraCard` (testTag `remote_camera_card`) | titre `ui2_camera_section_title` « Caméra pour la session distante » (heading) | voir ci-dessous | TOUJOURS visible (2.3.16), au-dessus de la liste |
| 4 | Liste d'appareils (`AnimatedVisibility`, `GlassCard` padding 12) | `scan_found_count` « %1$d appareil(s) trouvé(s) » ; `LazyColumn` clé = adresse | clic ligne = `onConnect(address)` | visible si liste non vide ; lignes désactivées pendant `isConnecting` |
| 5 | Carte « Connecté » (teinte `OssmConnected`) | `scan_connected` « Connecté » + nom | - | `isConnected` |
| 6 | Carte « Connexion » | `scan_connecting` « Connexion en cours… » + spinner `OssmWarning` | - | `isConnecting` |
| 7 | Carte erreur (teinte `OssmError`) | message de `BleConnectionState.Error` + icône Error | - | état Error |
| 8 | Carte état vide | SEARCHING (`ui2_scan_searching_title` « Recherche de l'OSSM… » + `ui2_scan_searching_body`) ou NONE_FOUND (`ui2_scan_none_title` « Aucun appareil trouvé » + `ui2_scan_none_body`) + conseils `ui2_scan_tips_title` « Rien n'apparaît ? » avec `ui2_scan_tip_power`, `ui2_scan_tip_close`, `ui2_scan_tip_bluetooth` | - | liste vide ET (scan en cours → SEARCHING ; Disconnected ou Error → NONE_FOUND) ; défile |

`DeviceRow` : ligne cliquable min 64 dp, coins 12, fond `OssmSurfaceRaised`, icône 28 dp (BluetoothConnected `OssmConnected` si OSSM, BluetoothSearching `OssmPrimaryLight` sinon, cd null), nom (gras si OSSM, 70 % sinon), adresse (12 sp), RSSI « −NN dBm » 12 sp coloré (`> −60` vert `OssmConnected`, `> −75` jaune `OssmWarning`, sinon rouge `OssmError`), badge « OSSM » (codé en dur, 12 sp gras vert). Aucun rôle Button, aucun cd global ni état « sélectionné/connexion » sur la ligne.

`RemoteCameraCard` selon `RemoteCameraSection` (`remoteCameraSection(state)` : `!active` → NoSession ; `shareUrl == null` → Starting ; `cameraUrl == null` → Unavailable ; sinon Available) :
- NoSession : `ui2_camera_no_session` + bouton `ui2_camera_start_session` « Démarrer la session » (min 48, `OssmPrimary`) → `onStartRemoteSession`.
- Starting : `ui2_camera_session_starting` « Session distante en cours de démarrage… ».
- Unavailable : `ui2_camera_regenerate_needed` (`OssmWarning`) + `ui2_camera_regenerate` « Régénérer le lien » (min 48, `OssmWarning`) → `onRegenerateRemoteLink`.
- Available : `ui2_camera_section_body` + `ui2_camera_share_mine` « Partager ma caméra » (min 52, `OssmPrimary`) → `onShareMyCamera` ; `ui2_camera_invite` « Inviter un autre appareil comme caméra » (OutlinedButton min 48) → `ACTION_SEND` texte (chooser `ui2_camera_invite_chooser`) ; `ui2_camera_copy` « Copier le lien caméra » (OutlinedButton min 48) → presse-papiers marqué sensible (`IS_SENSITIVE`) + texte `ui2_camera_copied` (`OssmConnected` 12 sp, `liveRegion = Polite`). `Available.toString()` masque le lien (secret). `cameraShareStillAllowed` ferme le partage ouvert si la session ou le lien change (test `RemoteCameraSectionTest`).

Constats : aucun testTag sur les boutons ; le seul tag est `remote_camera_card`. Texte les plus petits : 12 sp (version, RSSI, adresse, « copié »).

---

## 3. `DiagnosticsScreen` (onglet Diagnostic)

Appelant : `MainActivity` l.570 (`logs`, `connectionState`, `lastCommand`, `onClear = diagnosticsVm::clearLogs`).
- Titre `diag_title` « DIAGNOSTICS » (`headlineMedium`, gras).
- Bouton copier (`IconButton` M3 par défaut 48 dp, icône ContentCopy `OssmPrimary`, cd `diag_copy` « Copier le journal ») : copie tout le journal « `heure [tag] message` » par ligne dans le presse-papiers (`LocalClipboardManager`, API dépréciée) ; AUCUN retour visuel ni message de confirmation.
- Bouton effacer (IconButton 48 dp, Delete `OssmError`, cd `diag_clear` « Effacer les logs ») → `onClear` sans confirmation (destructif).
- Carte d'état : `diag_ble_state` « État BLE » (11 sp gras) + `BleStatusIndicator` ; `diag_last_cmd` « Dernière commande » (11 sp gras) + dernière commande (mono 12 sp, « — » si vide) ; si connecté : `diag_mac` « Adresse MAC : » (11 sp) + adresse (mono 12 sp). `Divider` (M3 déprécié).
- Compteur `diag_entries` « %1$d entrées » (11 sp).
- Liste `LazyColumn` (clé = id), défilement auto vers le bas à chaque nouvelle entrée (`animateScrollToItem(size-1)`), fond `OssmSurface` coins 16, padding 12. `LogRow` : « heure  [tag] » (mono 12 sp `OssmOnSurfaceMuted`) + message (mono 13 sp) coloré par niveau : DEBUG `OssmOnSurfaceMuted`, INFO `OssmAccent`, WARNING `OssmWarning`, ERROR `OssmError`. Le niveau n'est signalé QUE par la couleur (pas de préfixe texte).
- Pas de testTag. Défilement auto arrache le défilement manuel de l'utilisateur à chaque nouvelle ligne.

---

## 4. Composants (récapitulatif et contrats)

| Composant | Rôle / contrat | Tests |
|---|---|---|
| `ControlSlider` + `PrecisionScalarSlider` + `RampButton` | curseur scalaire ; voir 1.6 | `PrecisionSliderStateTest`, androidTest 56 dp |
| `ControlSliderMetrics` / `SliderFrame` / `BipolarStyle` / `signedValueColor` | géométrie partagée, en-tête, style bipolaire | indirect |
| `RangeBarControl` / `RangeBarSlider` / `RangeBarMarker` | double barre ; l'annulation (STOP/désactivation/recomposition) ne commit jamais ; commit uniquement si relâché ET déplacé | - |
| `PrecisionSliderState` | mécanique relative + paliers de précision (1,5 s, 8 px de seuil d'immobilité) | `PrecisionSliderStateTest` |
| `LiveAnchorPad` / `LiveAnchorState` | pad radial Live ; géométrie pure et testée | `LiveAnchorStateTest` |
| `LiveLimits` (`LiveLimitsState`, `LiveLimitIcon`) | lignes de limites haute/basse du pad | aucun test dédié |
| `LiveSliderState` / `StreamPositionSlider` | ancienne piste verticale de Live (wizard) ; `commitLiveAccessibilityPosition` (envoie puis TOUJOURS `onTouchEnd`), `finishLiveTouchGesture` | `LiveSliderStateTest`, `ControlScreenBehaviorTest` |
| `ProgressiveSpeedBar` | barre vitesse Progressif | aucun |
| `PatternButton` | bouton de grille (rôle Button, clic + appui long), 52 dp interne | indirect |
| `StopBar` | STOP actuel | indirect (tag `control_stop`) |
| `StopButton`, `HomeButton` | non utilisés | - |
| `BleStatusIndicator` | pastille 10 dp + texte 14 sp coloré de `state.label()` ; pulsation (×1,4, 700 ms) en Scan/Connexion ; couleur : Connected `OssmConnected`, Scanning/Connecting `OssmWarning`, Error `OssmError`, EmergencyStop `OssmStop`, sinon gris `#808080`. Textes FR codés en dur dans `model/BleConnectionState.label()` : « Déconnecté », « Scan... », « Connexion à X », « Connecté : X », « Erreur : … », « ARRÊT D'URGENCE » (pas de ressources, pas d'EN) | - |
| `QrCodeImage` | QR local (ZXing, correction M, UTF-8), TOUJOURS noir sur blanc avec zone de silence 20 dp, rôle Image ; utilisé par `LocalOtaSection` | - |
| `GlassCard` | `Column` coins 20 dp, fond `tint` 6 % sur `OssmSurface`, bord `OssmGlassBorder` 1 dp, padding 16 par défaut | - |

---

## 5. Thème (`ui/theme/`)

**Couleurs (`Color.kt`)** : thème sombre unique (`darkColorScheme`), pas de thème clair, pas de couleurs dynamiques.
| Jeton | Hex | Usage observé |
|---|---|---|
| `OssmBackground` | `#0C1219` | fond d'écran ; texte sur boutons clairs |
| `OssmSurface` | `#16212B` | cartes, dialogues |
| `OssmSurfaceRaised` (= `OssmSecondary`) | `#202C38` | boutons-icônes, bandeaux, lignes |
| `OssmGlass` | = Surface | teinte de carte |
| `OssmGlassBorder` | `#364858` | bordures de carte, séparateurs |
| `OssmOutline` | `#647889` | bordures de contrôle |
| `OssmPrimary` | `#B69AFF` | violet : action principale, vitesse |
| `OssmPrimaryLight` | `#CCBBF4` | libellés, icônes |
| `OssmOnPrimary` | `#160E29` | texte sur primaire |
| `OssmAccent` | `#65DDEB` | cyan : profondeur, sensation, pad |
| `OssmStop` | `#BA2444` | STOP |
| `OssmStopDark` | `#701C30` | (défini, non vu dans le périmètre) |
| `OssmConnected` | `#71DFAC` | succès, lecture, USB |
| `OssmWarning` | `#FFCF70` | avertissement, pause |
| `OssmError` | `#FF9AAF` | erreur, marqueurs, valeurs aléatoires |
| `OssmOnSurface` | `#EFF4F8` | texte principal |
| `OssmOnSurfaceMuted` | `#AFBDC9` | texte secondaire |
| `PatternColors` | Primary, Accent, Warning | rotation des boutons de pattern |
| Signé (dans `ControlSliderMetrics`) | positif `#34C759`, négatif `#FF453A`, tick `#3D8BFF` | sensation (hors jetons de thème) |

**Typographie (`Type.kt`)** : `FontFamily.SansSerif` partout ; headlineLarge 28/34 gras, headlineMedium 24/30 semi-gras, titleLarge 18/24 semi-gras, bodyLarge 16/24, bodyMedium 14/20, labelSmall 12/16 (+0,2 de lettre). titleMedium, bodySmall, labelMedium, etc. : valeurs M3 par défaut (utilisés : `titleMedium`). Beaucoup de tailles codées en dur `fontSize = N.sp` au lieu de styles typographiques.
`OssmRemoteTheme` : `MaterialTheme(colorScheme, typography)`, pas de formes personnalisées (utilise `shapes.medium` M3 par défaut).

---

## 6. testTag, contentDescription et tests dépendants de l'UI

**testTag dans le périmètre** : `control_main_surface` (scaffold), `control_card`, `control_stop`, `control_status_line`, `control_status_pill`, `remote_session_banner`, `remote_camera_card`. Hors périmètre mais dans le panneau du contrôle : tags `natural_*` et `voice_*` (listés en 1.5.4 et 1.7). Aucun testTag sur : bouton scan, lignes d'appareils, boutons de pattern, sliders, pause, verrou de rotation, réglages, dialogues.

**contentDescription / étiquettes sémantiques** : voir les sections 1.2 (boutons d'en-tête), 1.6 (curseurs, +/−), 1.12 (STOP, pause, rotation), 1.10 (info appui long), 1.5.2 (pad Live). Les `RandomToggle` : `contentDescription = label` complet, texte visible court masqué (`clearAndSetSemantics{}`) ; rôle `Checkbox`.

**Tests qui dépendent de l'UI** :
- `androidTest/.../CompactControlLayoutTest.kt` (instrumenté) : `control_main_surface` sans `ScrollBy` en 360×640 et 640×360 ; contenu et rail visibles ; cible `ControlSlider` ≥ 56 dp. Utilise `CompactControlScaffold` (signature `modifier, showActionRail, bare, actionRail(landscape), content(landscape)` à conserver ou à adapter AVEC le test).
- `test/.../ControlScreenBehaviorTest.kt` (JVM) : dépend des fonctions internes `shouldShowSensationSlider`, `shouldShowMaxSensationToggle`, `controlActionVisibility` (Live : STOP visible, pause et sauvegarde cachées), `shouldShowLiveInvertSetting`, `shouldShowLiveAccelerationSetting`, `shouldShowLiveRecordButton`, `shouldKeepHoldInfoVisible`, `isPrecisionEnabled`, `liveControlMinimumHeight` (≥ 420 / 180 compact), `liveControlMinimumWidth` (≥ 280), `liveSliderTrackThickness` (28 / 20 dp exact), `liveSliderThumbRadius` (≥ 28 / 22), `liveEndpointLabels()` (top = `ctl_live_deep`, bottom = `ctl_live_home`), `controlHeaderTitle`.
- `SensationScaleTest` : `signedSensationLabel`, `remoteSensationBannerArgs`, `sessionSensationDraft`, `sensationLegendRes`.
- `ControlDraftEditClockTest` : `ControlDraftEditClock`, `CONTROL_DRAFT_QUIET_MS`.
- `ObservedPositionRangeTest`, `LiveAnchorStateTest`, `LiveSliderStateTest`, `PrecisionSliderStateTest` : états purs.
- `ScanScreenAutoConnectTest` (`uniqueOssmAddress`, `scanAutoConnectAddress`), `RemoteCameraSectionTest` (`remoteCameraSection`, `cameraShareStillAllowed`).
- `NavigationBehaviorTest` (navigation, hors périmètre) ; `StringsParityTest` (toute nouvelle chaîne FR doit avoir son EN) ; `ControlViewModelTest` (appelle `startRangeWizard`).
- Contrainte : les fonctions `internal` ci-dessus doivent rester accessibles avec la même signature, ou les tests doivent être mis à jour avec.

---

## 7. Tailles et zones tactiles actuelles (cible 48 dp)

| Élément | Taille | Verdict |
|---|---|---|
| Boutons d'en-tête (pattern, réglages, enregistrer, limites haute/basse, REC) | 48×48 | OK (limite) |
| STOP | ≥ 56 de haut, largeur pleine (portrait) / 56 de large (paysage) | OK |
| Pause/Lecture | zone cliquable 52 dp | OK (> 48) |
| Verrou de rotation | **44×56 / 56×44** | **côté de 44 < 48** |
| Bouton info appui long | 48 | OK, mais geste « maintenir » uniquement |
| Boutons +/− (Regular/Large/Inline) | 56 / 72 / **48** | Inline à la limite |
| Hauteur de la piste de curseur | 56 / 88 / 52 | OK |
| Pouce de curseur visible | 4×44 / 6×70 / 5×42 dp | fin visuellement, zone de saisie 28–40 dp de rayon : OK |
| Cases `RandomToggle` | Regular 48 ; **Inline 36** | **36 < 48 en paysage / cartes courtes** (case + libellé forment une même cible `toggleable` dont la hauteur mini est 36) |
| Case Chaos (Progressif) | 56×56 mais seule la case est cliquable | libellé non cliquable |
| Cases Auto Random | 56×56, seule la case cliquable | libellé non cliquable |
| Lignes de réglages | ≥ 56 | OK |
| Bouton « Compris » / `Button` M3 par défaut | 40 dp de haut | **< 48** |
| Boutons Annuler/Enregistrer d'`AlertDialog` (`TextButton`, `Button`) | 40 dp de haut (M3) | **< 48** |
| Champs `OutlinedTextField` du dialogue | 56 | OK |
| `RangeSlider` M3 des limites de profondeur | pouces 20 dp, piste 4 dp | pouce petit (< 48 de cible) |
| Bouton scan | ≥ 56 | OK |
| Boutons caméra | ≥ 48 / 52 | OK |
| Lignes d'appareils | ≥ 64 | OK |
| Cellules de pattern | 52–120 de haut | OK |
| Boutons Diagnostics (copier/effacer) | 48 | OK |

---

## 8. Points faibles UX / accessibilité

### 8.1 Zones tactiles < 48 dp
- Verrou de rotation : 44 dp sur l'axe court.
- Cases `RandomToggle` : 36 dp en métriques Inline (paysage et cartes < 400 dp de corps), cible `toggleable` ≥ 36 seulement.
- `Button` de confirmation « Compris » et boutons d'`AlertDialog` : hauteur M3 40 dp.
- `RangeSlider` M3 des limites de profondeur (réglages) : pouce 20 dp, aucune cible dédiée.
- Verrou de rotation et Pause : séparés d'à peine 10 dp (portrait) / 8 dp (paysage) du STOP ; risque de mis-tap près du STOP, surtout en paysage (colonne de 56 dp de large, trois cibles empilées).

### 8.2 Texte < 12 sp (ou limite)
- 11 sp : en-têtes de section Auto Random (« MODES À MÉLANGER », « VITESSE MAX »), sous-texte « Au plafond, coups variés… », légende d'échelle sensation (« −100 », « 0 = neutre », « +100 », « ← retrait rapide · avancée rapide → »), étiquette de plage de précision (« Plage 0–100 »), graduations du pad Live et « reste à N » (dessinés dans le Canvas, non mis à l'échelle par la taille de police système), messages de préparation `OssmWarning` (Live, wizard), libellés Diagnostics (« État BLE », « Dernière commande », « Adresse MAC », « N entrées »).
- 12 sp : légende du pad, version, RSSI, adresse BLE, conseils, bannière Remote (3 lignes de 12 sp), hint de préset, hint du dialogue.
- Le texte du canvas (anneaux, « reste à N ») est un `TextStyle(fontSize = 11.sp)` : suit le facteur de police mais sans plancher.

### 8.3 Contraste (calculé sur les jetons ; ratios WCAG approchés)
- OK en général : `OssmOnSurface` sur fond 14–17:1 ; `OssmOnSurfaceMuted` sur fond 9,8:1, sur surface surélevée 7,4:1 ; `OssmPrimary`/`OssmPrimaryLight` 8–9:1 ; `OssmError` 9,4:1 ; `OssmWarning` 12,9:1 ; blanc sur `OssmStop` 6,1:1 (STOP OK).
- Faibles :
  - Texte désactivé : `OnSurface` à 40 % sur surface ≈ 3,5:1 (valeur de curseur désactivée) ; pourcentage du pad désactivé (35 %) ≈ 3,0:1 : sous 4,5:1 (acceptable pour un état désactivé, mais l'information reste illisible).
  - Bordures de composants (non-texte, seuil 3:1) : `OssmGlassBorder` sur surface ≈ 1,7:1 (bordures de cartes, séparateurs, bordure des lignes Auto Random, décoration des presets : quasi invisibles) ; `OssmOutline` ≈ 3,6:1 (OK).
  - STOP rouge `#BA2444` sur fond ≈ 3,1:1 et sur surface ≈ 2,7:1 : c'est le bord `OssmError` 2 dp qui assure la visibilité (non-texte).
  - Piste de curseur (accent à 28 % sur surface) ≈ 2,0:1 ; anneaux du pad (14 % d'opacité) ≈ 1,5:1 : décoratifs mais la piste est la cible principale.
  - Case décochée `OnSurface` 50 % ≈ 4,7:1 (OK).
  - Rouge de signe négatif `#FF453A` sur surface ≈ 4,8:1 (OK limite).
  - Gris « inactif » du statut BLE `#808080` sur fond ≈ 4,8:1 (OK).

### 8.4 Information portée uniquement par la couleur
- Boutons de limites Live : état « absent / en déplacement / verrouillé » reconnaissable seulement par la couleur de l'icône et l'épaisseur de bordure (l'icône est identique ; seule la `contentDescription` TalkBack porte l'état en clair).
- Bouton REC : état IDLE/ARMED/RECORDING/RECORDED/PLAYING = taille du point (14 → 18 dp) et couleur de bordure ; aucune forme distincte (lecture/arrêt) ni texte visible.
- Pattern actif dans le sélecteur : plein vs teinte 12 % ; aucun `selected`/`stateDescription` ni icône « coché ».
- `RangeBarSlider` : barre sélectionnée = halo 22 % discret (que la barre haute ou basse soit déplacée par −/+ n'est pas annoncé).
- Valeurs aléatoires affichées en rouge (`OssmError`) : le rouge dit « valeur pilotée par l'aléatoire » ; sans couleur il n'y a pas d'indice (le marqueur est un trait rouge fin).
- Sensation : signe + / − aussi dans le libellé (OK) ; mais le remplissage vert/rouge est la seule indication visuelle sur la piste (le signe figure dans le nombre).
- Diagnostics : niveau de log = couleur seule.
- Connexion BLE : texte + couleur (OK). Statut machine : icône + texte + couleur (OK).
- Précision active : seul le libellé de plage change de couleur.
- Statut RSSI : couleur seule pour la qualité (la valeur dBm est affichée).

### 8.5 Autres constats UX/accessibilité
- **STOP absent en Live nu** (voir 0.2.2) : écran de sécurité principal sans bouton STOP ; la fonction commentaire de `controlActionVisibility` contredit ce comportement.
- Textes codés en dur non traduisibles : « OSSM Remote », « OSSM » (badge), « HOME », « STOP » (texte visuel du bouton), « ☰ », « −100 / +100 », « VITESSE (auto) » (ProgressiveSpeedBar), tous les textes de `BleConnectionState.label()` et `MachineState.displayLabel` (« Homing... », « Préparation... », « Inactif », « Live (streaming) »). Un redesign doit les migrer vers des ressources FR/EN (parité testée).
- Libellés bruts : « Preset » / « Preset actif » (anglicisme), « Pret », « Arreter », « Demarrer » (accents manquants dans `strings.xml`), `ctl_patterns_hint` sans accents (« fenetre », « apres »).
- Doublons de libellé : le mot « Aléatoire » désigne à la fois la case Aléatoire (vitesse/profondeur/sensation) et le curseur « Aléatoire » (Auto Random) ; `ctl_save` « Enregistrer » (icône) vs `ctl_preset_save` « Sauvegarder » (bouton de dialogue).
- Les curseurs de profondeur (`RangeBarSlider`) et de vitesse Progressif (`ProgressiveSpeedBar`) n'exposent pas `setProgress`/`ProgressBarRangeInfo` ; seuls les boutons −/+ de `RangeBarControl` sont activables à l'accessibilité (une seule barre à la fois, pas de bouton −/+ pour `ProgressiveSpeedBar`).
- Ligne cliquable des appareils BLE sans rôle `Button` ni libellé fusionné ; ordre TalkBack et annonce non définis.
- Carte caméra toujours affichée même sans session (occupe de la place avant la liste).
- Copie du journal Diagnostics sans retour ; effacement sans confirmation.
- Lignes Auto Random et case Chaos : libellé non cliquable (zone de toucher réduite à la case).
- Dialogue de réglages : les infobulles ne s'obtiennent que par appui long (découvrabilité faible ; alternative TalkBack présente).
- En paysage, trois sliders sur une ligne chacun (Inline) : libellé tronqué à 118 dp (ellipsis) et accessoire (cases Aléatoire/Max) empilé sous le libellé dans une colonne de 118 dp.
- Orientation : Live forcé en portrait ; l'état de verrou de rotation de l'utilisateur est restauré à la sortie (`previous` mémorisé). À préserver.
- Dépréciations probables : `Divider` (M3), `LocalClipboardManager` ; non bloquantes.
- Les animations sont rares (pastille BLE, couleur des boutons de pattern 200 ms, ressort du HomeButton inutilisé) ; aucune gestion de `reduce motion`.

---

## 9. Liste de contrôle de non-régression (à valider après redesign)

1. Un seul appelant `ControlScreen` avec les mêmes callbacks ; mêmes tags (`control_main_surface`, `control_card`, `control_stop`, `control_status_line`, `control_status_pill`, `remote_session_banner`, `remote_camera_card`, `natural_*`, `voice_*`).
2. STOP `control_stop` présent et premier dans l'ordre TalkBack partout sauf Live nu (et décider explicitement pour Live nu).
3. Pad Live : ancre au premier contact, demi-disque supérieur radial, zéro en bas ou sur la limite basse, relâché → `endLiveTouch`, annulation → `stopLiveTouch`, désactivation/dispose → annulation ; clé d'époque respectée ; lignes de limites haute/basse avec états absent/déplacement/verrouillé.
4. `streamingReady` et `slidersEnabled` comme portes (pad, wizard, limites, REC, tous curseurs) ; messages de préparation conservés.
5. Session distante : `key(remoteSession.active)`, bandeau (maximums, avertissement vitesse 0 %, valeurs reçues), curseurs d'enveloppe (vitesse max, profondeur min–max, sensation = intensité `±D`), brouillons et calme de 250 ms.
6. Sensation bipolaire (détente 0, vert/rouge, tick bleu, `−`), case Max seulement `teasingPounding`, cases Aléatoire seulement `teasingPounding`/`simpleStroke`, plage aléatoire de sensation à double barre, marqueurs et valeur rouge pour l'aléatoire.
7. Cases/boutons : Progressif (barre max + profondeur + sensation + case Chaos), Auto Random (sélection, 3 curseurs, profondeur, intensité de départ, Démarrer/Arrêter + dialogue de confirmation), Naturel, Voix.
8. Réglages : précision par pattern, accélération Live 10–100, inversion Live, gardes vitesse/profondeur, limites de profondeur, aides STOP/Pause ; dialogue de changement brusque (2 interrupteurs, confirmer/annuler avec ré-alignement des brouillons).
9. Sélecteur de patterns (grille, long appui = réorganiser, Retour ferme), réorganisation glisser-déposer, sauvegarde de profil (hors Live, connecté).
10. Rotation : verrou en-app uniquement, Live portrait forcé puis restauration.
11. Chaînes FR et EN en parité ; nouvelles chaînes UTF-8 sans BOM ; pas de mention d'IA/noms dans le code.
12. Tests : tous ceux listés en section 6 passent (fonctions `internal` conservées ou tests adaptés) ; `CompactControlLayoutTest` (sans défilement de page en 360×640 et 640×360, cible slider ≥ 56 dp).
13. Scan : scan auto au premier affichage, connexion automatique à l'unique OSSM pendant le scan, carte caméra toujours visible, états vides.
14. Diagnostics : copier/effacer, défilement auto, couleurs par niveau.

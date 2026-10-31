# Inventaire B : modes Naturel / Voix, Remote, caméra, Réglages (profils, mises à jour, USB, stats)

Contrat de non-régression pour le redesign. Lecture seule, rien n'a été modifié.
Racine : `app/src/main/java/com/ossm/remote/` (abrégé `…/`). Toutes les clés existent en FR (`res/values/`) et en EN (`res/values-en/`, même nom de clé) ; `StringsParityTest` échoue si l'une manque. Le texte FR est cité ci-dessous.

## 0. Vue d'ensemble et câblage

| Zone | Fichier | Branché depuis |
|---|---|---|
| Panneau Naturel | `ui/screens/NaturalControlPanel.kt` | `ControlScreen.kt` l.734 (`activePattern.mode == PatternControlMode.NATURAL`) |
| Panneau Voix + bouton « i » | `ui/screens/VoiceControlPanel.kt` | `ControlScreen.kt` l.617 (`isVoicePattern()`), bouton « i » l.447-449 dans l'en-tête de la carte |
| Bandeau session distante | `ControlScreen.kt` `RemoteSessionBanner` l.2986 | l.471, si `remoteSession.active` |
| Dialogue « Sauvegarder le profil » + bouton disquette | `ControlScreen.kt` `SavePresetDialog` l.2842, bouton l.451 | `onSavePreset = profilesVm::savePreset` (MainActivity l.523) |
| Statut USB-C | `ControlScreen.kt` `ControlStatusLine` l.1226 / `ControlStatusPill` l.1331, `controlStatusKind` l.1208 | `usbControlActive = adresse == "USB-C"` (l.230) ; texte `ctl_usb_active` ; toast `ctl_usb_recovering` dans MainActivity l.607 |
| Écran Réglages (onglet) | `ui/screens/ProfilesScreen.kt` | MainActivity l.579 ; LazyColumn : titre, profils, session Internet, overlay, mise à jour, firmware, statistiques |
| Mise à jour locale OTA | `ui/screens/LocalOtaSection.kt` | dans `UpdaterCard` (ProfilesScreen l.291) |
| Mise à jour depuis le PC | `ui/screens/PcUpdateSection.kt` | dans `UpdaterCard` (l.292) |
| Flash firmware USB | `ui/screens/FirmwareFlashSection.kt` (`FirmwareFlashCard`) | ProfilesScreen l.173 |
| Statistiques | `ui/screens/StatisticsSection.kt` | ProfilesScreen l.174 |
| Partage caméra (plein écran) | `ui/screens/CameraShareScreen.kt` | MainActivity l.598 si `fullScreenRoute == Screen.CameraShare` et `cameraShareUrl != null` ; déclenché depuis la carte caméra de `ScanScreen.kt` (`RemoteCameraCard`, tag `remote_camera_card`, hors de mon périmètre mais indispensable à connaître) |

Palette (Color.kt) : fond `OssmBackground` #0C1219, surface #16212B, surface relevée #202C38, bordure verre #364858, contour #647889, primaire #B69AFF, primaire clair #CCBBF4, accent #65DDEB, STOP #BA2444, connecté (vert) #71DFAC, avertissement (jaune) #FFCF70, erreur (rose) #FF9AAF, texte #EFF4F8, texte atténué #AFBDC9.

---

## 1. Panneau NATUREL (`NaturalControlPanel.kt`)

Racine : `Column` `testTag("natural_panel")`. Reçoit `uiState: ControlUiState`, `remoteSession`, `connected`, `slidersEnabled` (= connecté et machine ni en homing ni en préflight), `NaturalControlActions`, et un slot `depthEditor` (le `DepthRangeEditor` fourni par ControlScreen, avec marqueurs `naturalDepthMin/MaxCurrent`).

### 1.1 Éléments, dans l'ordre d'affichage

| # | Élément | Clé / texte FR | Callback → ViewModel (MainActivity l.545-555) | Visible / activé |
|---|---|---|---|---|
| 1 | Texte d'intro 12 sp, alpha .7 | `natural_intro` « Gestes coherents generes sur le telephone … Rien ne bouge avant Demarrer. » | – | toujours |
| 2 | Texte d'avertissement jaune 12 sp | `natural_remote_hint` « Session distante : le site demarre Naturel avec sa vitesse, bornee par ton enveloppe. » | – | seulement si `remoteSession.active` |
| 3 | Curseur « Vitesse max » (`ControlSlider`, couleur primaire, `currentMarker = naturalSpeedCurrent`) | `natural_max_speed` « Vitesse max » | `onMaxSpeedChange` → `controlVm.setNaturalMaxSpeed` (appelé pour drag ET commit) | activé si `slidersEnabled` ; valeur affichée = `remoteSession.speedMax` si session active, sinon `naturalMaxSpeed` ; `gestureCancellationKey = liveGestureEpoch` (annule le geste au STOP) |
| 4 | Curseur « Energie » (accent cyan) | `natural_energy` « Energie » | `onEnergyChange` → `setNaturalEnergy` | `slidersEnabled` |
| 5 | Curseur « Variete » (jaune) | `natural_variety` « Variete » | `onVarietyChange` → `setNaturalVariety` | `slidersEnabled` |
| 6 | Éditeur de plage de profondeur (slot) | libellés propres à `DepthRangeEditor` (autre inventaire) | `onDepthLive`/`onDepthRangeCommit` | `slidersEnabled` |
| 7 | Ligne d'état de mouvement (3 variantes exclusives) | voir 1.2 | – | selon `naturalRunning`, `naturalAiWaitingFirstPlan`, `naturalStartPending` |
| 8 | Bouton « Demarrer Naturel » (vert) `testTag("natural_start")`, min 48 dp, poids 1 | `natural_start` « Demarrer Naturel » | `onStart` → `startNatural` | activé si `slidersEnabled && !remoteSession.active && !naturalRunning && !naturalStartPending && naturalMaxSpeed > 0` |
| 9 | Bouton « Arreter » (jaune/warning, PAS rouge) `testTag("natural_stop")`, min 48 dp, poids 1 | `natural_stop` « Arreter » | `onStop` → `stopNatural` | activé si `connected` seulement (reste actif même si machine occupée) |
| 10 | Section IA (voir 1.3) | | | toujours rendue sous un espace de 16 dp |

### 1.2 Ligne d'état (zone 7), exclusive, dans cet ordre de priorité
- `naturalRunning && naturalAiWaitingFirstPlan` : `natural_ai_waiting_first_plan` « En attente du premier plan IA (%1$d s) - vitesse 0 », jaune 12 sp, `testTag("natural_ai_waiting")`.
- `naturalRunning` : `natural_running` « En cours : %1$s » (vert, gras 13 sp) avec le motif traduit (`natural_motif_approach_hold_release` « approche, maintien, relache », `natural_motif_tease_pause` « taquinerie et pause », `natural_motif_wave` « vague », `natural_motif_ladder` « echelle », `natural_motif_rest` « repos », ou « … » si inconnu) ; puis `natural_values` « Vitesse %1$d %% - plage %2$d-%3$d %% » (12 sp, alpha .75). Valeurs = valeurs courantes générées, sinon `depthMin/Max`.
- `naturalStartPending` : `natural_preparing` « Entree du mode en cours (vitesse 0)... » jaune 12 sp.

### 1.3 Section « Planification IA » (`NaturalAiSection`, `testTag("natural_ai_section")`)

Contrainte maîtresse : **deux étapes explicites** « Charger Qwen3 » (pré-chargement sur le PC, jamais annulé par STOP) puis « Utiliser l'IA » ; choisir « Utiliser l'IA » pendant le chargement connecte automatiquement à la fin. **Le mouvement n'attend jamais l'IA** (générateur local en repli).

| Élément | Clé / texte FR | Callback | Visible / activé |
|---|---|---|---|
| Titre 13 sp gras primaire clair | `natural_ai_toggle` « Planification IA (Ollama sur le PC) » | – | toujours |
| Aide 11 sp alpha .6 | `natural_ai_hint` « Facultatif. L'IA ne propose que la suite des gestes ; le mouvement reste local et n'attend jamais l'IA. » | – | toujours |
| Champ adresse `OutlinedTextField` `testTag("natural_ai_address")`, `KeyboardType.Uri`, `ImeAction.Done`, max 200 car., `isError` si invalide | `natural_ai_address` « Adresse du PC (ex. 100.x.y.z:11434) » ; erreur `natural_ai_address_invalid` « Adresse locale ou Tailscale requise (aucun serveur public). » | `onAiAddressChange` → `setNaturalAiAddress` sur Done, sur le bouton OK, et automatiquement au clic sur Charger/Réessayer si brouillon modifié et valide | brouillon local `addressDraft` (resynchronisé seulement si vide) ; validation `NaturalAiEndpoint.parse` |
| Bouton « OK » (`android.R.string.ok`), min 48 dp | texte système (pas de clé app) | `onAiAddressChange(addressDraft)` | visible seulement si `draftChanged` (brouillon trim ≠ valeur enregistrée) ; activé si non invalide |
| Interrupteur « Passer par le relais de raisonnement (PC) » : ligne `toggleable(role = Switch)` min 48 dp, `Switch` `testTag("natural_ai_reasoning_relay")` (`onCheckedChange = null`, la ligne porte l'action) | `natural_ai_reasoning_relay` | `onAiReasoningRelayChange` → `setNaturalAiReasoningRelay` | toujours ; état = `naturalAiReasoningRelay` |
| Aide du relais 11 sp | `natural_ai_reasoning_relay_hint` « Port 11435 sur la même adresse, raisonnement activé (plus lent, plans plus longs). Sur le PC : Start-OssmReasoningRelay.cmd, puis Voir-Raisonnement-Qwen3.cmd pour lire le raisonnement. » | – | toujours |
| État du modèle `testTag("natural_ai_model_state")`, 12 sp | `natural_ai_model_idle` « Modèle non chargé » / `natural_ai_model_unloaded` « Modèle déchargé du PC (inactif) - recharge-le » (jaune) / `natural_ai_model_loading` « Chargement du modèle… (%1$d s) » (jaune) / `natural_ai_model_loaded` « Modèle chargé » (vert) / `natural_ai_unavailable` « Indisponible : %1$s » (rose) | – | selon `naturalAiModelState` (IDLE, LOADING, LOADED, FAILED) |
| Info modèle présent 11 sp | `natural_ai_model_present` « Modèle %1$s présent sur le PC » (`OllamaNaturalPlannerConfig.DEFAULT_MODEL`) | – | si LOADING et `naturalAiModelInstalled == true` |
| Bouton « Charger Qwen3 » (primaire) `testTag("natural_ai_load")` | `natural_ai_load_button` | `loadOrRetry` → (adresse si modifiée) + `onAiLoadModel` → `loadNaturalAiModel` | visible si état IDLE |
| Bouton « Réessayer » (jaune) `testTag("natural_ai_retry")` | `natural_ai_retry` | même `loadOrRetry` | visible si FAILED, ou `planRejected` (statut planificateur `AI_UNAVAILABLE` alors que le modèle n'est pas FAILED) |
| Bouton « Utiliser l'IA » (vert) `testTag("natural_ai_use")` | `natural_ai_use` | `onAiEnabledChange(true)` → `setNaturalAiEnabled` | visible si `!naturalAiEnabled` et modèle LOADED ou LOADING |
| Bouton contour « Ne plus utiliser l'IA » `testTag("natural_ai_stop_use")` | `natural_ai_stop_use` | `onAiEnabledChange(false)` | visible si `naturalAiEnabled` |
| Statut du planificateur `testTag("natural_ai_status")`, 12 sp | `natural_status_local` « Désactivé - générateur local » / `natural_status_waiting` « IA choisie - connexion dès que le modèle est chargé (local en attendant) » / `natural_status_ready` « IA prête » (vert) / `natural_status_connected` « IA connectée (plan reçu) » (vert) / `natural_status_unavailable` « IA indisponible - local » (rose) + « - cause » | – | toujours |
| Repli IA `testTag("natural_ai_fallback")`, rose, gras 12 sp | `natural_ai_fallback` « IA défaillante → générateur local (%1$s) » + `natural_ai_fallback_reserve` / cause / `natural_ai_fallback_unloaded` | – | si `naturalAiFallback != null && naturalRunning` |
| Réserve IA `testTag("natural_ai_reserve")`, 12 sp | `natural_ai_reserve` « Avance IA : %1$d s de gestes en réserve » ou `natural_ai_reserve_latency` « … - dernière latence %2$d s » | – | si `naturalAiEnabled && naturalRunning && reserve != null` |

Causes d'échec (`naturalAiProblemText`) : `natural_ai_cause_invalid_address` « Adresse invalide » ; `…_unreachable` « PC injoignable (réseau/pare-feu) » (+ détail sur 2e ligne) ; `…_timeout` « Délai dépassé pendant le chargement (%1$d s) » ; `…_model_missing` « Modèle %1$s absent sur le PC » ; `…_invalid_json` « Réponse refusée (JSON invalide) » ; `…_http` « Erreur Ollama HTTP %1$d ».

### 1.4 Double STOP (Naturel)
Deux commandes d'arrêt coexistent : le bouton `natural_stop` du panneau (`stopNatural`) ET la barre STOP globale (`StopBar`, `testTag("control_stop")`, `onStop = controlVm.stop()` ; `showStop` toujours vrai, jamais masqué sauf pad Live). À conserver les deux. Le STOP BLE est envoyé comme `set:speed:0`. STOP incrémente `liveGestureEpoch` (annule les gestes de curseurs en cours).

### 1.5 Tailles, styles
Boutons min 48 dp ; curseurs via `ControlSlider` (métriques Regular, hauteur `metrics.sliderHeight`). Textes secondaires 11-12 sp. Boutons démarrer vert `OssmConnected`, arrêter jaune `OssmWarning`, charger primaire, réessayer jaune.

### 1.6 Tests liés
`ControlSessionStoreNaturalTest`, `NaturalAiEndpointTest`, `NaturalAiModelTest`, `NaturalAiPacingTest`, `NaturalGesturesTest`, `NaturalPlanJsonParserTest`, `NaturalPlannerTest`, `NaturalSequenceGeneratorTest` (logique, pas d'UI). Aucun test Compose du panneau ; les testTags `natural_*` sont déclarés mais aucun test ne les référence aujourd'hui (à garder pour de futurs tests / `uiautomator`). Fonctions internes testables : `naturalStatusLabelRes`, `naturalMotifLabelRes`, `naturalAiProblemText`, `naturalAiFallbackText`.

---

## 2. Panneau VOIX (`VoiceControlPanel.kt`) + bulle « i »

Racine `Column` `testTag("voice_panel")`. **Pas de bouton STOP dans le panneau** (décision 2.3.28) : la barre STOP globale reste l'unique bouton ; le mot parlé « stop » reste actif (`voiceStopNow`). `VoiceControlActions.onStopNow` n'est plus utilisé visuellement (callback conservé).

### 2.1 Éléments, dans l'ordre

| Élément | Clé / texte FR | Callback → VM | Visible / activé |
|---|---|---|---|
| État de l'IA vocale `testTag("voice_model_state")` 13 sp gras | `voice_ai_idle` « IA vocale : non chargée » (jaune) / `voice_ai_loading` « Chargement de l'IA vocale… » (jaune) / `voice_ai_ready` « IA vocale : prête (sur le téléphone, %1$s) » (vert, `%1$s` = backend LLM) / `voice_ai_missing` « IA vocale : modèle manquant » (rose) / `voice_ai_unreadable` « IA vocale : modèle illisible (droits du fichier) » (rose) / `voice_ai_error` « IA vocale : erreur — %1$s » (rose) | – | toujours |
| Chemins attendus 10 sp | `voice_ai_expected_path` « Chemin attendu : %1$s » | – | un par chemin si ModelMissing/Unreadable ; si Error et `modelRoot != null`, affiche `modelRoot` |
| Moteur de transcription `testTag("voice_asr_engine")` 12 sp | `voice_asr_whisper` « Transcription : Whisper small (français) » (vert) / `voice_asr_parakeet` « Transcription : Parakeet (langue non forcée) » (jaune) | – | si `voiceAi.asrEngine != null` |
| Bouton « Écouter » / « Pause micro » (OutlinedButton pleine largeur, min 48 dp, gras) `testTag("voice_listen")` | `voice_listen` « Écouter » ↔ `voice_listen_pause` « Pause micro » | si en écoute → `onListenChange(false)` ; sinon si `hasMicrophonePermission()` → `onListenChange(true)` ; sinon lance la demande `RECORD_AUDIO` (résultat → `onMicrophonePermissionResult`) → `voiceAiVm.setListening / hasMicrophonePermission / onMicrophonePermissionResult` | activé seulement si `VoiceAiStatus.Ready` |
| Libellé d'écoute 11 sp vert | `voice_listening` « Micro ouvert — parlez (rien n'est enregistré) » | – | si `voiceAi.listening` |
| Interrupteur « STOP rapide par mot court » : ligne `toggleable(Switch)` min 56 dp, `testTag("voice_short_word_stop")` sur la ligne, `Switch` sans callback | `voice_short_word_stop_on` « STOP rapide par mot court : activé » (vert) / `voice_short_word_stop_off` « … : désactivé » (jaune) | `onShortWordStopChange` → `setVoiceShortWordStop` (persisté, activé par défaut) | toujours (non lié à `ready`) |
| Micro refusé 11 sp rose | `voice_mic_denied` « Micro refusé : autorisez le micro pour OSSM dans les réglages Android. » | – | si `voiceAi.microphoneDenied` |
| Dernière transcription `testTag("voice_transcript")` 12 sp | `voice_last_transcript` « Entendu : « %1$s » » | – | si non vide |
| Latences `testTag("voice_latency")` 11 sp | `voice_latency` « Latences : parole → texte %1$s ms · texte → étapes %2$s ms » | – | si l'une des deux latences connue |
| Source d'interprétation `testTag("voice_interpret_source")` 11 sp | `voice_interpret_source` « Interprétation : %1$s · %2$s ms » avec `voice_source_rules` « règles » / `voice_source_gemma` « Gemma » | – | si `interpretSource != null` |
| Transcription ignorée `testTag("voice_transcript_ignored")` 11 sp jaune | `voice_transcript_ignored` « Transcription ignorée : %1$s (aucun mouvement) » ; raisons : `voice_ignored_empty` « texte vide », `_punctuation` « ponctuation seule », `_hallucination` « hallucination probable », `_repetition` « mot répété en boucle », `_too_long` « texte trop long », `_low_energy` « voix trop faible » | – | si `lastIgnoredReason != null` |
| Live pas prêt 11 sp jaune | `ctl_live_preparing` « Préparation du mode Live — la machine confirme la pleine course… » | – | si `!uiState.streamingReady` |
| Curseur « Vitesse max (voix) » (primaire) | `voice_max_speed` | `onMaxSpeedChange` → `setVoiceMaxSpeed` (drag + commit) | `slidersEnabled` ; `gestureCancellationKey = liveGestureEpoch` |
| Plage de profondeur Voix `testTag("voice_depth_range")` (`DepthRangeEditor`) | libellés du composant partagé | commit seulement → `onDepthRangeChange(min, max)` → `setVoiceDepthRange` ; brouillon local clampé via `VoiceDepthRange.effective(min, max, depthLimitMin, depthLimitMax)` | `slidersEnabled` ; pas d'appel pendant le drag (draft seul) |
| Titre « SÉQUENCE PLANIFIÉE » 11 sp gras, interlettrage 1 sp | `voice_plan_title` | – | toujours |
| État d'interprétation `testTag("voice_state")` 12 sp | `voice_state_planned` « Consigne comprise » (vert) / `voice_state_stopped` « STOP » (texte normal) / `voice_state_rejected` « Séquence refusée (hors bornes) — rien ne bouge » / `voice_state_not_understood` « Non compris — rien ne bouge » / `voice_state_not_ready` « Live pas prêt ou vitesse max à 0 — rien ne bouge » (ces trois en rose) | – | si `voiceInterpretation != NONE` |
| Motif continu `testTag("voice_motif")` 12 sp (vert gras si `voiceRunning`) | `voice_motif_line` « Va-et-vient : profondeur %1$d → %2$d %%, vitesse %3$d → %4$d %% en %5$d s, puis continu » + (si en cours) `voice_motif_running` « Rythme continu en cours (STOP ou nouvelle consigne pour changer) » 11 sp | – | si `voiceMotif != null` |
| Plan vide | `voice_plan_empty` « Aucune séquence. » | – | si pas de motif et `voicePlan` vide |
| Étapes du plan (une ligne par étape, étape courante verte et grasse) | `voice_step_stay` « Reste à %1$d %% », `voice_step_deep` « Fond … en … s » (cible 100), `voice_step_home` « Retour … » (cible 0), `voice_step_position` « Position … », suffixe `voice_step_hold` « maintien … s », préfixe « → » | – | sinon |

### 2.2 Bulle d'info « i » (`VoiceInfoButton`)
Placée dans l'en-tête de la carte de contrôle (ControlScreen l.447), juste avant la roue crantée, **uniquement en mode Voix**. Bouton rond 48 dp (même empreinte que l'engrenage), fond `OssmSurfaceRaised`, contour 1 dp `OssmOutline`, lettre « i » en serif italique gras 22 sp primaire clair ; `testTag("voice_info")` ; `contentDescription` = `voice_info_cd` « Informations sur le mode Voix » (la lettre est masquée aux lecteurs d'écran) ; `Role.Button`. Ouvre un `AlertDialog` (`testTag("voice_info_dialog")`, fond `OssmGlass`) : titre `voice_info_title` « Mode Voix », corps `voice_info_body` 14 sp (IA entièrement sur le téléphone, aucun réseau, rien d'enregistré ; exemples de consignes ; « stop » arrête tout ; rien ne bouge sans consigne comprise ; 0 % = minimum, 100 % = maximum de la plage Profondeur), bouton `voice_info_close` « Fermer ». **La longue explication ne doit plus être inline dans le panneau** (déplacée en 2.3.28).

### 2.3 Règles de sécurité à préserver
Rien ne bouge sans consigne comprise ; transcriptions gardées (vide, ponctuation, hallucination, répétition, trop long, faible énergie) = aucun mouvement ; STOP parlé + option « mot court » ; STOP global ; vitesse max à 0 ou Live non prêt = rien ne bouge. Pipeline 100 % téléphone (modèles dans `getExternalFilesDir("models")`).

### 2.4 Tests liés
`VoiceAsrLabelTest` (test JVM : `voiceAsrLabelRes`), `ControlSessionStoreVoiceTest`, `VoiceSequenceRunnerTest`, `OnDeviceVoiceInterpreterTest`, `VoiceDepthRangeTest`, `VoiceLlmJsonParserTest`, `VoiceModelFilesTest`, `VoiceMotifTest`, `VoiceRuleParserTest`, `VoiceSafetyStopsTest`, `VoiceStepsTest`, `VoiceStopKeywordsTest`. Aucun test d'UI Compose ne cible les tags `voice_*`.

---

## 3. Session distante (Remote)

### 3.1 Carte « SESSION INTERNET PRIVÉE » dans Réglages (`ProfilesScreen.kt` `RemoteSessionCard`)
VM : `RemoteSessionViewModel` (`start()`, `stop()`, `regenerate()` délèguent à `RemoteSessionService`). `GlassCard` padding 16 dp, aucun testTag.

| Élément | Clé / texte FR | Action | Visible / activé |
|---|---|---|---|
| Titre 14 sp gras | `remote_session_title` « SESSION INTERNET PRIVÉE » | – | toujours |
| Texte explicatif 12 sp | `remote_session_hint` « Le téléphone pilote l'OSSM en Bluetooth ; le site distant envoie seulement ses demandes par Internet. Le lien privé reste le même jusqu'à sa régénération. Le mouvement exige une machine connectée et calibrée, et un maximum de vitesse supérieur à 0 %. » | – | toujours |
| Message d'état 12 sp : vert si `state.connected`, sinon jaune | `state.message` (chaîne codée en dur dans `RemoteSessionClient`, non localisée) | – | toujours |
| Statut média 12 sp atténué | `state.mediaStatus` (chaîne en dur FR) | – | toujours |
| Champ lien en lecture seule `OutlinedTextField` (singleLine) | label `remote_session_link` « Lien privé de session » ; valeur = `shareUrl` en clair | – | si `state.shareUrl != null` |
| Bouton « Copier » (primaire, icône ContentCopy) | `ui2_remote_copy` « Copier » | `clipboardManager.setText(shareUrl)` + `copied = true` (aucune copie automatique : **le lien ne va dans le presse-papiers que sur action explicite**) | si `shareUrl != null` ; hauteur Material par défaut (40 dp) |
| Bouton « Partager le lien » (primaire, icône Share) | `remote_share` | `Intent.ACTION_SEND text/plain` + chooser (titre `remote_share`) | idem |
| Confirmation 12 sp vert | `ui2_remote_copied` « Lien copié dans le presse-papiers. » | – | si `copied` (réinitialisé quand le lien change) |
| Bouton principal pleine largeur (primaire) | `remote_session_start` « Démarrer la session » ↔ `remote_stop_session` « Arrêter la session » | `if (state.active) viewModel.stop() else viewModel.start()` | toujours |
| Bouton « Régénérer et révoquer le lien » (jaune) | `remote_session_regenerate` | `viewModel.regenerate()` | toujours ; **aucune confirmation** (le texte d'aide dessous prévient) |
| Aide 12 sp | `remote_session_regenerate_hint` « Cette action invalide le lien actuel avant d'en créer un nouveau. » | – | toujours |

`RemoteSessionState` expose aussi `readiness`, `lastRefusal`, `reconnecting`, `reconnectAttempt`, `cameraUrl` : **non affichés dans cette carte** (seuls `message`/`mediaStatus` le sont ; la reconnexion passe par `message`). Si le redesign veut les montrer, c'est un ajout, pas une perte.

### 3.2 Bandeau en session active (`ControlScreen.kt` `RemoteSessionBanner`, `testTag("remote_session_banner")`)
Visible si `remoteSession.active`, sous l'en-tête de la carte de contrôle. Fond `OssmSurfaceRaised`, bordure 1 dp (accent = primaire clair, ou jaune si `motionBlockedByZeroMaximum`), 12 sp, padding 10×6 dp.
- Ligne 1 : `ui2_remote_banner_limits` « Session distante : maximums %1$d %% / %2$d–%3$d %% » + « · » + `ui2_remote_banner_sensation_range` « Sensation %1$s (%2$s–%3$s) » (gras).
- Ligne 2 (si bloqué) : `ui2_remote_blocked_zero` « Maximum à 0 % : le site ne peut rien faire bouger, montez le curseur » (jaune).
- Ligne 3 : `ui2_remote_live_waiting` « Le site n'a encore rien envoyé » ou `ui2_remote_live_prefix` « Envoyé par le site : … » avec `ui2_remote_live_speed`, `_depth`, `_sensation`, `_pattern`, `_position` (« Live %1$d %% »).
Effets sur les panneaux : en session, tous les curseurs locaux montrent et règlent le **maximum d'enveloppe** (pas la valeur pilotée par le site) ; démarrer/arrêter la session recrée les contrôles (`key(remoteSession.active)`) pour annuler tout geste en cours sans le valider ; Naturel : démarrage local désactivé, texte d'avertissement.

### 3.3 Carte caméra (Connexion, `ScanScreen.kt`) et clé caméra séparée
`RemoteCameraCard` (`testTag("remote_camera_card")`) : titre `ui2_camera_section_title` ; états `NoSession` (`ui2_camera_no_session` + bouton `ui2_camera_start_session`), `Starting` (`ui2_camera_session_starting`), `Unavailable` (`ui2_camera_regenerate_needed` + bouton `ui2_camera_regenerate`), `Available` (`ui2_camera_section_body` + boutons `ui2_camera_share_mine` « Partager ma caméra » 52 dp min, `ui2_camera_invite` « Inviter un autre appareil comme caméra » [chooser `ui2_camera_invite_chooser`], `ui2_camera_copy` « Copier le lien caméra » + `ui2_camera_copied`). Le **lien caméra est distinct du lien de contrôle** (rôle `?role=camera`, secret en fragment `#k=`, ne donne jamais le contrôle) ; copie marquée sensible (`IS_SENSITIVE`) ; `RemoteCameraSection.Available.toString()` masque l'URL. `cameraUrl` est vérifié par `cameraUrlMatches` contre la session principale. (Détail de la carte à reprendre dans l'inventaire de ScanScreen.)

### 3.4 Tests liés
`RemoteCameraSectionTest` (ui/screens), `RemoteCameraSessionTest`, `CameraSharePolicyTest`, `CameraShareDiagnosticsTest`, `RemoteControlProtocolTest`, `RemoteSessionClient*Test` (Diagnostics, LifecycleRace, Readiness, Reconnect, Safety), `RemoteSessionLimitsPayloadTest`, `RemoteSessionSafetyAdapterTest`, `RemoteSessionSnapshotGuardTest`, `PhoneHost*Test`, `ControlSessionStoreRemote{Envelope,Maximums,SessionEnd}Test`.

---

## 4. Écran plein écran « Partage de ma caméra » (`CameraShareScreen.kt`)

Route plein écran (`Screen.CameraShare`), fond opaque `OssmBackground`, `systemBarsPadding`, padding 16 dp, `pointerInput(Unit) {}` pour avaler les touches (onglets inertes dessous). Aucun testTag.

| Élément | Clé / texte FR | Comportement | Visible / activé |
|---|---|---|---|
| Titre (`titleLarge` gras, `heading()`) | `ui2_camera_screen_title` « Partage de ma caméra » | – | toujours |
| Texte d'aide 13 sp atténué | `ui2_camera_screen_hint` « Votre aperçu s'affiche ci-dessous ; seule la personne qui contrôle la session le voit. Arrêtez le partage pour libérer la caméra et le micro. » | – | toujours |
| Bouton « Arrêter le partage » rouge `OssmStop`, icône VideocamOff, min 56 dp, coins 14 dp | `ui2_camera_stop` | `onStopSharing` → `cameraShareUrl = null` (ferme l'écran, détruit la WebView, libère caméra et micro) | toujours |
| Zone centrale (une seule variante) | lien invalide : `ui2_camera_invalid_link` « Lien caméra invalide pour ce serveur. Régénérez le lien de session. » (rose) ; permissions manquantes : `CameraPermissionCard` ; moteur WebView mort : `ui2_camera_renderer_gone` « Le moteur d'affichage du partage s'est arrêté : caméra et micro libérés. Arrêtez puis relancez le partage. » (rose) ; sinon WebView | priorité dans cet ordre : `!urlAllowed`, `!permissionsGranted`, `rendererGone`, WebView | |
| Carte permissions | titre `ui2_camera_permission_title` « Autorisez la caméra et le micro » (heading) ; corps `ui2_camera_permission_body` « Le partage a besoin de la caméra et du micro. Rien n'est envoyé avant votre accord. » ou, si refusé, `ui2_camera_permission_refused` (rose) | – | si `!permissionsGranted` |
| Bouton « Autoriser la caméra et le micro » (primaire, min 48 dp) | `ui2_camera_permission_grant` | relance `RequestMultiplePermissions(CAMERA, RECORD_AUDIO)` | idem |
| Bouton contour « Ouvrir les réglages de l'app » (min 48 dp, icône Settings) | `ui2_camera_permission_settings` | `ACTION_APPLICATION_DETAILS_SETTINGS` | seulement si `refused` |
| Panneau « Diagnostic caméra » (`GlassCard` padding 10) | titre `ui2_camera_diag_title` 12 sp gras ; dernier message 12 sp semi-gras (rose si niveau ≥ WARNING) en `liveRegion Polite` ; journal défilant max 120 dp, lignes « • … » 11 sp (rose erreur, jaune avertissement, atténué sinon) | – | toujours (liste vide possible) |

Contraintes à préserver (sécurité/vie privée) :
- Pas de bouton retour visuel ; le **BackHandler** appelle aussi `stop()`. Passer en arrière-plan (`ON_STOP`) arrête le partage si la WebView existe (`sharing`). `ON_RESUME` relit les permissions (retour des réglages).
- La WebView n'est chargée que si `CameraSharePolicy.isLoadableCameraUrl(cameraUrl, BuildConfig.REMOTE_BROKER_BASE_URL)` ET les deux permissions Android sont accordées. Lien complet (`?role=camera`, `#k=`) passé tel quel à `loadUrl`, jamais journalisé ; la console web n'est pas relayée dans logcat (seulement ERROR/WARNING nettoyés, plafonnés à 20 lignes).
- WebView verrouillée : JS + DOM storage, pas d'accès fichier/contenu, pas de fenêtres multiples, géolocalisation refusée, contenu mixte interdit, `keepScreenOn`, navigation hors origine du broker bloquée, erreurs SSL = annulation, sélecteur de fichier refusé, permissions web accordées seulement à l'origine du broker et pour les ressources réellement autorisées, `onRenderProcessGone` → écran d'erreur lisible (ne pas fermer silencieusement).
- Libération : `stopLoading`, `about:blank`, `onPause`, `removeAllViews`, `destroy` (la WebView morte n'est que détruite).
- Journal d'ouverture : forme du lien (jamais le lien), permissions Android, paquet/version WebView.
- Texte plein écran sur le thème sombre ; le sélecteur de caméra avant/arrière est dans la page web (pas dans l'app).

Tests : `CameraSharePolicyTest`, `CameraShareDiagnosticsTest`, `RemoteCameraSessionTest`.

---

## 5. Écran RÉGLAGES (`ProfilesScreen.kt`)

Structure : `LazyColumn` padding 20 dp, espacement 16 dp ; clés d'items : `title`, `empty` ou un par `preset.id`, `remote-session`, `overlay`, `updater`, `firmware`, `statistics`. Auto-défilement vers la fin (`totalItemsCount - 2`) quand un flash USB démarre (`flashPhase != 0`) pour garder la carte firmware et son journal visibles. Titre `prof_title` « PROFILS » (headlineMedium gras) alors que l'onglet est « Réglages » (le libellé de navigation est ailleurs).

### 5.1 Profils / préréglages
- État vide : icône BookmarkBorder 48 dp + `prof_empty` « Aucun profil sauvegardé\n\nUtilisez l'icône de sauvegarde dans l'onglet Contrôle pour garder vos réglages. » (14 sp atténué). Cette phrase désigne l'icône disquette de l'en-tête de Contrôle : si l'icône change, mettre le texte à jour.
- `PresetCard` (GlassCard) : nom 16 sp gras, `preset.patternName` 12 sp primaire clair, puces `StatChip` : `prof_speed` « Vitesse » (primaire), `prof_min` « Min » (cyan), `prof_max` « Max » (vert) avec « NN% » (12 sp, valeur colorée, étiquette primaire clair).
- Bouton ▶ (`IconButton` 48 dp, `contentDescription = prof_apply` « Appliquer », vert) → `onApply(preset)` = `controlVm.applyPreset(preset)` PUIS bascule vers l'onglet Contrôle (`pagerState.scrollToPage(controlPageIndex)`).
- Bouton corbeille (`IconButton`, `contentDescription = prof_delete` « Supprimer », rose) → ouvre `AlertDialog` de confirmation : titre `prof_delete_title` « Supprimer le profil ? », corps `prof_delete_msg` « « %1$s » sera supprimé définitivement. », bouton rouge `prof_delete` → `onDelete(preset)` = `profilesVm::deletePreset` ; annuler `ctl_cancel` « Annuler ».
- Les deux icônes sont empilées verticalement avec 4 dp d'écart (risque de mauvais appui sur Supprimer, mitigé par la confirmation).
- Sauvegarde (depuis Contrôle) : bouton disquette 48 dp (`contentDescription = ctl_save` « Enregistrer ») visible si `connected && actionVisibility.showSave` (= motif actif et pas Live). Dialogue : titre `ctl_preset_save_title` « Sauvegarder le profil », champ `ctl_preset_name_label` « Nom du profil » (défaut `ctl_preset_default_name` « Mon Profil », vide → `ctl_preset_fallback_name` « Profil »), bouton `ctl_preset_save` « Sauvegarder », `ctl_cancel`. Un profil contient : nom, clé et nom de motif, vitesse, profondeur min/max (rien d'autre). Room `ossm_db` à migration destructive.

### 5.2 Carte OVERLAY (dans Réglages)
Ligne `toggleable(Switch)` min 48 dp : icône lanceur 22 dp + `overlay_title` « OVERLAY » (14 sp gras) + `Switch` (couleurs accent). Callback `onOverlayToggle(context, overlayVm, enabled)` (fonction `internal`, **testée** par `MainActivityOverlayAutoStartTest`) : activer sans permission → ouvre `ACTION_MANAGE_OVERLAY_PERMISSION` ; avec permission → `OverlayService.manuallyClosed = false` + `setOverlayEnabled(true)` ; désactiver → `manuallyClosed = true` POSÉ AVANT `setOverlayEnabled(false)` et `stopService` (évite le redémarrage auto au `onStop`). Textes : `overlay_hint` « Bulle flottante pour piloter la machine même en arrière-plan. » (12 sp), `overlay_permission_hint` (jaune, si `!canDrawOverlays`). Curseur Material « Transparence » (`overlay_transparency`) 20-100 %, 80 paliers (`steps = 79`), valeur `NN%` à droite, `overlayVm.setTransparencyPercent`. Le curseur n'a pas de `contentDescription` propre (libellé dans une ligne séparée).

### 5.3 Carte MISE À JOUR (`UpdaterCard`) : 4 mécanismes empilés
Ordre exact : en-tête, mise à jour locale OTA, mise à jour depuis le PC, mise à jour OTA Internet (manifeste), états.

| Élément | Clé / texte FR | Callback | Condition |
|---|---|---|---|
| En-tête : icône SystemUpdate + titre | `update_title` « MISE À JOUR » | – | toujours |
| `TextButton` « Vérifier » | `update_check_now` | `onCheck` → `updaterVm.checkNow` | désactivé si `Checking` ou `Downloading` |
| Section OTA locale | voir 5.4 | | |
| Section PC | voir 5.5 | | |
| Sous-titre 12 sp gras | `update_ota_title` « MISE À JOUR OTA INTERNET » | – | toujours |
| Champ URL du manifeste | label `update_url_label` « URL du manifeste (secours) », placeholder `update_url_hint` « http://serveur:port/update.json » | état local `manualUrl` (clé `url`) | toujours |
| `TextButton` « Enregistrer l'URL » (aligné à droite) | `update_url_save` | `onUrlSave(manualUrl)` → `updaterVm.saveManualUrl` | toujours |
| États | `UpdaterStatus.Idle` rien ; `Checking` `update_checking` « Recherche d'une mise à jour… » ; `UpToDate` `update_current` (vert) ; `Available` `update_available` « Version %1$s disponible » (vert) + bouton `update_download` « Télécharger » → `onDownload(manifest)` ; `Downloading` `update_downloading` ; `ReadyToInstall` bouton `update_install` « Installer la mise à jour » → `onInstall(apk)` = `installApk(context, apk)` ; `Error` `update_error` (rose) | | |

### 5.4 Mise à jour locale OTA (`LocalOtaSection.kt`, VM `LocalOtaViewModel`)
Titre 12 sp gras `update_local_ota_title` « MISE À JOUR LOCALE OTA ». Boutons pleine largeur min 48 dp.

| Élément | Clé / texte FR | Callback | Condition |
|---|---|---|---|
| Bouton récepteur (icône SystemUpdate) : primaire ↔ jaune | `update_local_ota_receive` « Prêt à recevoir » ↔ `update_local_ota_stop` « Arrêter l'écoute » | `otaVm.toggleReceiver` | toujours ; couleur jaune quand `receiverActive` |
| **QR de jumelage** (`QrCodeImage`, 85 % de largeur, max 360 dp, `contentDescription = update_local_ota_qr_desc` « QR code de jumelage pour la mise à jour. Adresses : … ») + légende `update_local_ota_qr_hint` 12 sp centrée | – | – | si `receiverInfo != null` ; contenu = `info.qrUri` (ECDH/jeton) |
| Liste d'adresses | `update_local_ota_addresses` « Adresses de ce téléphone » ; une ligne 13 sp par adresse, ou `update_local_ota_no_address` « Aucune adresse Wi-Fi ou Tailscale détectée. » (jaune) | – | si `receiverInfo != null` |
| Aide générale 12 sp | `update_local_ota_hint` (explique QR, « Envoyer à une adresse », « Partager ma version », **même nombre à 2 chiffres à confirmer sur le téléphone qui reçoit**, confirmation d'installation Android) | – | toujours |
| Bouton « Scanner le QR » (icône QrCodeScanner) | `update_local_ota_scan` ; invite caméra `update_local_ota_scan_prompt` | lance `ScanContract` (zxing, QR seul, sans bip, orientation libre) → `otaVm.onQrScanned(contents)` | désactivé si `senderActive` |
| `TextButton` « Envoyer à une adresse » (min 48 dp) | `update_local_ota_manual` | bascule `manualOpen` | toujours |
| Champ adresse manuelle | label `update_local_ota_address_label` « Adresse (IP ou IP:port) » ; `KeyboardType.Uri`, `ImeAction.Send` (sans `keyboardActions` : la touche envoyer ne fait rien), trim + max 80 car. | – | si `manualOpen || otaState.suggestManual` (le VM ouvre le champ automatiquement après un échec) |
| Bouton « Se connecter et envoyer » | `update_local_ota_manual_send` | `otaVm.sendToAddress(address)` | même condition ; activé si `!senderActive && address non vide` |
| Bouton « Partager ma version » (icône Share) | `update_local_ota_send` | `otaVm.sendInstalledVersion` | désactivé si `senderActive` |
| **Dialogue émetteur « Nombre de vérification »** | titre `update_local_ota_pairing_title` ; nombre à 2 chiffres en 56 sp gras (`contentDescription = update_local_ota_number_desc` « Nombre de vérification : %1$s ») ; corps `update_local_ota_pairing_body` « Envoi de %1$s vers %2$s (actuellement %3$s). Vérifiez que l'autre téléphone affiche le même nombre et touchez Oui dessus. » ; `update_local_ota_waiting` « En attente de confirmation sur l'autre téléphone… » (`liveRegion Polite`) | bouton unique `update_local_ota_cancel` « Annuler » → `otaVm.cancelPairing` ; **`onDismissRequest = {}` : non annulable en touchant à côté** ; aucun bouton de confirmation | si `otaState.pairing != null` |
| **Dialogue récepteur « Demande de mise à jour »** | titre `update_local_ota_incoming_title` ; même nombre 56 sp ; corps `update_local_ota_incoming_body` « %1$s veut envoyer la version %2$s. Le nombre %3$s est-il bien celui affiché sur l'autre téléphone ? » ; version inconnue `update_local_ota_unknown_version` | « Oui » `update_local_ota_yes` → `answerIncoming(true)` ; « Non » `update_local_ota_no` ou toucher à côté/retour → `answerIncoming(false)` (le refus est la valeur sûre par défaut) | si `otaState.incoming != null` |
| Message d'état 12 sp, `liveRegion Polite` | `otaState.message` (chaîne issue du VM/transfert) ; vert = succès, rose = erreur, atténué = info | – | si non vide |

Les types `update_local_ota_kind_wifi`/`_kind_tailscale` servent aux libellés d'adresse côté modèle. `progressPercent` existe dans l'état mais n'est pas affiché ici. **À préserver : QR + ECDH, nombre de vérification à 2 chiffres identique des deux côtés, confirmation Oui/Non sur le récepteur uniquement, Android redemande toujours l'installation.**
Tests : `LocalOtaHandshakeTest`, `LocalOtaPairingTest`, `LocalOtaTransferTest`, `InstalledApkShareTest`, `ReceivedApkInstallerTest`.

### 5.5 Mise à jour depuis le PC (`PcUpdateSection.kt`, VM `PcUpdateViewModel`)
`Column` espacement 6 dp. Pas de testTag.

| Élément | Clé / texte FR | Callback | Condition |
|---|---|---|---|
| Titre 12 sp gras | `pc_update_title` « MISE À JOUR DEPUIS LE PC » | – | toujours |
| Aide 11 sp | `pc_update_hint` « Le PC publie le dernier APK d'un dossier (serveur à allumer sur le PC). L'appli le télécharge, le vérifie, puis Android demande la confirmation d'installation. » | – | toujours |
| **Champ « Adresse du serveur PC »** | label `pc_update_url_label`, placeholder `pc_update_url_hint` « http://100.x.y.z:8091/ » ; erreur `pc_update_url_invalid` « Adresse invalide. Exemple : http://100.65.88.40:8091/ » | `address` local resynchronisé quand l'adresse enregistrée change ; la saisie efface l'erreur | toujours |
| `TextButton` « Enregistrer l'adresse » (aligné à droite) | `pc_update_url_save` | `invalid = !viewModel.saveServerUrl(address)` (validation par le contrôleur) | toujours |
| Bouton « Installer la dernière version du PC » (pleine largeur, hauteur Material par défaut) | `pc_update_install` | `invalid = false` puis `viewModel.installLatestNow(address)` (utilise l'adresse du champ, même non enregistrée) | désactivé si `Checking` ou `Downloading` |
| Ligne de statut 12 sp | `pc_update_checking`, `pc_update_unreachable` (jaune), `pc_update_current` (vert), `pc_update_downloading` « …version %1$s… », `pc_update_rejected` (rose, signature/paquet), `pc_update_error` (rose), `pc_update_ready` « Version %1$s prête. Android va demander la confirmation d'installation. » (vert) + `TextButton` `pc_update_install_again` « Ouvrir l'installation » → `viewModel.openInstaller(apk)` | | selon `PcUpdateStatus` (Idle = rien) |
| **Interrupteur « Mode développement : rester à l'écoute du PC »** (Switch seul cliquable, pas toute la ligne ; sous-texte 11 sp) | `pc_update_listen`, `pc_update_listen_hint` « Interroge le PC toutes les 15 s tant que l'appli est en mémoire ; télécharge et propose l'installation dès qu'une version plus récente apparaît. » | `viewModel::setListening` | toujours |
| Libellé d'écoute 12 sp vert | `pc_update_listening` « À l'écoute du PC… » | – | si `listening` |

Notifications associées (hors écran) : `pc_update_channel`, `pc_update_notification_title/text`. Serveur côté PC : `tools/apk-pull-server` (port 8091). Tests : `PcCheckGateTest`, `PcUpdatePolicyTest`, `UpdateManifestParserTest`.

### 5.6 Flash firmware USB (`FirmwareFlashSection.kt`, `FirmwareFlashCard`, VM `FirmwareFlashViewModel`)
`GlassCard` padding 16. Écran maintenu allumé (`keepScreenOn`) tant que `busy` (Preparing, Erasing, Writing). Aucun testTag.

| Élément | Clé / texte FR | Callback | Condition |
|---|---|---|---|
| Titre + icône Usb | `fw_title` « FIRMWARE DE LA MACHINE » | – | toujours |
| Aide 12 sp | `fw_hint` « Branche l'OSSM au téléphone avec un câble USB (OTG), puis lance le flash. La machine sera arrêtée puis redémarrée. » | – | toujours |
| États | Preparing `fw_state_preparing` + barre indéterminée ; Erasing `fw_state_erasing` « … (jusqu'à 30 s) » + barre indéterminée ; Writing `fw_state_writing` « Écriture : %1$d %% » 13 sp semi-gras + barre déterminée ; Complete `fw_state_complete` (vert) + `TextButton` `fw_ok` « OK » → `reset` ; Failed message d'erreur (rose) + `fw_ok` → `reset` | | |
| Erreurs | `fw_err_no_cable` « Aucun OSSM détecté en USB. Vérifie le câble OTG. », `fw_err_permission`, `fw_err_open`, `fw_err_no_bootloader` (BOOT + EN/RESET), `fw_err_invalid`, `fw_err_rejected`, `fw_err_write` « … bloc %1$d … », `fw_err_generic` | | |
| Journal | titre `fw_logs_title` « Journal du flash » ; `TextButton` `fw_logs_copy` « Copier le journal » (copie tout dans le presse-papiers) ; zone défilante max 200 dp, monospace 10 sp, fond `OssmBackground`, défilement automatique en bas | | si journal non vide |
| Bouton « Flasher le firmware inclus » (min 48 dp) | `fw_flash_bundled` | `viewModel.prepareBundled` | masqué pendant `busy` |
| `TextButton` « Choisir un fichier .bin » (min 48 dp) | `fw_flash_file` | `OpenDocument` (`*/*`) → `viewModel.prepareFile(uri)` | masqué pendant `busy` |
| **Dialogue de confirmation** (obligatoire avant tout flash) | titre `fw_confirm_title` « Flasher le firmware ? » ; corps `fw_confirm_body` « %1$s (%2$d Ko)\nLa machine sera arrêtée puis redémarrée. Ne débranche pas le câble pendant l'opération. » ; boutons `fw_confirm_ok` « Flasher » → `viewModel.confirm`, `fw_cancel` → `viewModel.cancel` ; toucher à côté = annuler | | si `pending != null` |

Firmware livré : `app/src/main/assets/firmware/ossm-1.2.0.bin`. Contrôle USB-C : voir 6. Tests : `Esp32BootloaderProtocolTest`, `UsbControlProtocolTest`, `UsbFallbackPolicyTest`, `UsbLinkPolicyTest`, `UsbLiveAutoEntryTest`.

### 5.7 Statistiques (`StatisticsSection.kt`, VM `StatisticsViewModel`)
Titre `stat_title` « STATISTIQUES » (titleLarge gras, primaire, interlettrage 2 sp). Pas de testTag.
- Carte globale : `stat_total` TOTAL (headlineLarge, primaire), `stat_today` AUJOURD'HUI (cyan), `stat_last7` 7 JOURS (vert), `stat_last30` 30 JOURS (jaune). Durées via `formatDuration` (« 45s », « 12min », « 2h 15min », fonction publique).
- Carte sessions : `stat_sessions`, `stat_avg_duration`, `stat_max_duration`, `stat_avg_speed` (%), `stat_max_speed` (%). Cellules : étiquette `labelSmall`, valeur `headlineMedium` 1 ligne tronquée.
- Historique 30 jours : `stat_history` + `stat_cumulated` « %1$s cumulés », histogramme `Canvas` 88 dp (barres dégradé primaire→cyan), dates début/fin (`d MMM`). **Aucune sémantique accessible sur le graphe.**
- Patterns utilisés : `stat_patterns`, puces de couleur (`PatternColors`), nom, « durée · NN% », `stat_sessions_count` « %1$d sessions », barre de progression 6 dp ; vide : `stat_no_sessions` « Aucune session enregistrée ».
- Actions (3 boutons à largeur égale, teinte à 16 % + contenu teinté, icône 16 dp, texte 12 sp semi-gras 1 ligne, hauteur Material par défaut 40 dp) :
  - « Effacer » (rose, `stat_reset`) → ouvre un dialogue en **2 étapes** : (1) `stat_reset_title` « Effacer les statistiques ? » + `stat_reset_msg` « … irréversible. » + `stat_continue` « Continuer » ; (2) même titre + `stat_reset_final_msg` « Êtes-vous absolument certain ? … » + `stat_delete_permanently` « Supprimer définitivement » → `viewModel.resetAll()` ; annuler `ctl_cancel` à chaque étape.
  - « Exporter » (primaire, `stat_export` ↔ `stat_exporting` « Export… » + roue) → `viewModel.prepareExport()` ; quand `pendingExportJson` arrive, `CreateDocument("application/json")` nommé `ossm_sessions_AAAA-MM-JJ.json` ; l'écriture échoue **silencieusement** (try/catch vide).
  - « Importer » (cyan, `stat_import` ↔ `stat_importing`) → `OpenDocument` (`*/*`) → lit le JSON → `viewModel.importJson`. Erreur : `stat_import_error` « Import impossible : %1$s » (rose 12 sp).
Tests : `SessionAccumulatorTest`, `SessionStatsTrackerTest`, repositories sous `data/repository/`. Aucun test d'UI.

---

## 6. USB-C (contrôle) dans `ControlScreen`

- Condition : `connectionState` Connecté avec adresse `"USB-C"` (`usbControlActive`) ; priorité maximale de `controlStatusKind` (USB passe avant Déconnecté, Calibrage, Erreur).
- Affichage : portrait = ligne bannière sous la barre STOP (`ControlStatusLine`, `boxed`, `singleLine`, `testTag("control_status_line")`) ; paysage = pilule dans l'en-tête de carte (`ControlStatusPill`, max 240 dp, `testTag("control_status_pill")`). Texte `ctl_usb_active` « USB-C actif · Bluetooth OSSM désactivé », icône CheckCircle, teinte verte `OssmConnected` (fond 12 %, bordure 40 %). Masqué en Live nu (`liveBareScreen`).
- MainActivity l.607 : texte flottant `ctl_usb_recovering` « USB perdu, reconnexion… » (13 sp, fond `OssmSurface`) quand `usbRecovering`.
- Mode « auto-Live USB-C » (2.3.46) : logique dans `usb/` (`UsbLiveAutoEntryTest`), pas d'UI propre dans mon périmètre.
- Les autres états (`DISCONNECTED` `ui2_status_disconnected_title/hint`, `CALIBRATING` `ui2_status_calibrating`, `ERROR`) appartiennent à l'autre inventaire.

---

## 7. Points faibles UX / accessibilité

### Zones tactiles < 48 dp
- `RemoteSessionCard` : boutons Copier, Partager, Démarrer/Arrêter, Régénérer sans `heightIn(48)` (Material = 40 dp). Même cas pour `UpdaterCard` (« Télécharger », « Installer la mise à jour ») et `PcUpdateSection` (« Installer la dernière version du PC »).
- `TextButton` sans hauteur minimale : « Vérifier », « Enregistrer l'URL », « Enregistrer l'adresse », « Ouvrir l'installation », « OK » et « Copier le journal » du firmware, boutons des dialogues profil/statistiques/firmware (40 dp). Une partie des boutons OTA locale est bien à 48 dp (modèle à généraliser).
- Statistiques : 3 boutons d'action côte à côte à 40 dp, texte 12 sp, avec « Effacer » (destructif) collé à « Exporter ».
- `PcUpdateSection` : seul le `Switch` est cliquable, pas la ligne ni le libellé (Naturel, Voix, Overlay utilisent `toggleable` sur la ligne, bon modèle). Pas de `Role.Switch` sur la ligne.
- Profils : les icônes Appliquer et Supprimer (48 dp) sont séparées de 4 dp seulement.
- Curseur Overlay : 80 paliers sur ~300 dp, libellé séparé du curseur, pas de description.
- Dialogue d'appairage OTA non fermable par toucher extérieur : voulu, à conserver (mais un seul bouton).

### Texte < 12 sp
- 11 sp : `natural_ai_hint`, `natural_ai_reasoning_relay_hint`, `natural_ai_model_present`, et presque tout le bloc de diagnostic Voix (`voice_listening`, `voice_mic_denied`, `voice_latency`, `voice_interpret_source`, `voice_transcript_ignored`, `ctl_live_preparing`, `voice_motif_running`, `voice_plan_title`), `pc_update_hint` / `pc_update_listen_hint`, journal caméra, `labelSmall` des statistiques.
- 10 sp : chemins de modèle Voix (`voice_ai_expected_path`), journal firmware monospace.
- Beaucoup d'informations importantes (refus de micro, transcription ignorée, « rien ne bouge ») sont en 11 sp.

### Information portée par la seule couleur
- Statut du modèle IA / planificateur : le texte dit l'état, mais l'urgence (ok, attente, erreur) n'est portée que par vert/jaune/rose (pas d'icône).
- `voice_short_word_stop_on/off` : vert/jaune + libellé, OK ; l'interrupteur porte aussi l'état.
- Message de session distante (`state.message`) : vert si connecté, jaune sinon, sans icône.
- Message OTA locale : succès, erreur et info distingués uniquement par la couleur (pas de préfixe ni d'icône).
- Journal caméra : niveaux ERREUR / AVERTISSEMENT seulement en rose / jaune.
- Histogramme 30 jours et barres de patterns : barres sans description textuelle ni valeur par jour ; couleurs de pattern cycliques sans légende en dehors du nom.
- Bouton « Arrêter » du Naturel en jaune (pas rouge) alors que STOP global est rouge : sémantique de couleur incohérente (mais voulu pour le distinguer du STOP d'urgence ?). À arbitrer sans réduire la visibilité du STOP.
- « Démarrer Naturel » (vert) vs « Utiliser l'IA » (vert) vs « Charger Qwen3 » (primaire) : trois verbes proches, hiérarchie visuelle peu claire entre les 2 étapes IA.

### Contraste (à mesurer, non mesuré ici)
- Textes `OssmOnSurface` à alpha .5-.6 (aide IA, `stat_no_sessions` alpha .5) sur #16212B : ratio estimé ~5 à 6, limite à 11 sp.
- Texte de boutons à fond `OssmConnected`/`OssmWarning`/`OssmPrimary` : la couleur de contenu vient de `ButtonDefaults` (thème `onPrimary`) ; vérifier le contraste réel (le thème a `OssmOnPrimary` #160E29, sombre, donc probablement correct).
- Texte `OssmError` rose sur surface : ok ; `OssmWarning` sur fond bannière à 12 % : ok.

### Densité et structure
- L'onglet Réglages est un défilement unique très long : profils, session Internet, overlay, 4 systèmes de mise à jour dans une seule carte, firmware, statistiques complètes. La carte « MISE À JOUR » concentre ~17 contrôles (OTA locale, PC, OTA Internet manifeste) avec trois titres de 12 sp quasi identiques.
- Titre de page « PROFILS » ne reflète pas le contenu (onglet Réglages).
- Panneau Voix : 10 lignes de diagnostic avant les curseurs ; le panneau scrolle dans une carte au défilement imbriqué.
- Panneau Naturel : section IA (adresse, relais, état, 2-3 boutons, statut, repli, réserve) plus longue que les gestes eux-mêmes ; l'étape « Charger Qwen3 » puis « Utiliser l'IA » n'est pas numérotée.
- Chaînes Naturel (`strings_natural.xml` FR) sans accents (« Demarrer », « Energie », « Variete », « Arreter », « coherents generes ») alors que la partie IA du même fichier est accentuée : incohérence typographique (à corriger en UTF-8 sans BOM, sans changer les clés).
- `RemoteSessionCard` : message et statut média viennent de chaînes FR codées en dur dans `RemoteSessionClient` (non traduites en EN, hors `StringsParityTest`).
- Lien privé de session affiché en clair dans un champ ; pas de masquage ni d'œil.
- « Régénérer et révoquer le lien » sans dialogue de confirmation (seule une phrase d'aide), alors que Supprimer profil, Effacer stats et Flasher ont une confirmation.
- Accessibilité : écrans plein écran caméra bien balisés (`heading()`, `liveRegion`) ; Profils, Statistiques, Firmware, PC n'ont ni `testTag` ni `contentDescription` autres que les icônes Appliquer / Supprimer ; icônes décoratives avec `contentDescription = null` (correct).
- Dialogue Voix « i » : lettre en serif italique, 22 sp, descriptif lecteur d'écran fourni ; bonne pratique à conserver.

---

## 8. testTag / contentDescription existants (liste exhaustive du périmètre)

testTags : `natural_panel`, `natural_start`, `natural_stop`, `natural_ai_section`, `natural_ai_address`, `natural_ai_reasoning_relay`, `natural_ai_model_state`, `natural_ai_load`, `natural_ai_retry`, `natural_ai_use`, `natural_ai_stop_use`, `natural_ai_status`, `natural_ai_fallback`, `natural_ai_reserve`, `natural_ai_waiting`, `voice_panel`, `voice_model_state`, `voice_asr_engine`, `voice_listen`, `voice_short_word_stop`, `voice_transcript`, `voice_latency`, `voice_interpret_source`, `voice_transcript_ignored`, `voice_depth_range`, `voice_state`, `voice_motif`, `voice_info`, `voice_info_dialog`, `remote_session_banner`, `remote_camera_card` (ScanScreen), `control_card`, `control_status_line`, `control_status_pill`, `control_stop`, `control_main_surface`. Aucun dans ProfilesScreen, StatisticsSection, LocalOtaSection, PcUpdateSection, FirmwareFlashSection, CameraShareScreen.

contentDescription / sémantique : bouton « i » (`voice_info_cd`), icônes ▶ (`prof_apply`) et corbeille (`prof_delete`), disquette (`ctl_save`), QR OTA (`update_local_ota_qr_desc`), nombre de vérification 56 sp (`update_local_ota_number_desc`), titres `heading()` de la caméra, `liveRegion Polite` (message OTA, « en attente de confirmation », dernier message de diagnostic caméra), `Role.Switch` sur les lignes Naturel relais, Voix mot court et Overlay.

Tests qui dépendent de l'UI de ce périmètre :
- `app/src/test/.../ui/screens/VoiceAsrLabelTest.kt` : mappe `voiceAsrLabelRes` (clé `voice_asr_whisper`, `voice_asr_parakeet`).
- `app/src/test/.../MainActivityOverlayAutoStartTest.kt` : appelle `onOverlayToggle` (signature `internal` à conserver : `(Context, OverlaySettingsViewModel, Boolean)`).
- `app/src/test/.../ui/screens/RemoteCameraSectionTest.kt` : `remoteCameraSection(state)` (ScanScreen).
- `app/src/test/.../ui/screens/ControlScreenBehaviorTest.kt` : fonctions internes de ControlScreen (`controlActionVisibility`, etc.).
- `app/src/test/.../res/StringsParityTest.kt` : toute clé ajoutée ou renommée doit exister en FR et EN.
- `app/src/androidTest/.../ui/screens/CompactControlLayoutTest.kt` : tags `control_main_surface`, `test_stop`, `test_control` (cadre de la disposition compacte).
- Tests de logique (pas d'UI) à ne pas casser : listes en 2.4, 3.4, 5.4, 5.5, 5.6 ; fonctions `internal` référencées par les tests ou l'UI : `naturalStatusLabelRes`, `naturalMotifLabelRes`, `naturalAiProblemText`, `voiceAsrLabelRes`, `voiceInterpretationRes`, `voiceIgnoreReasonLabel`, `formatVoiceSeconds`, `formatDuration`, `onOverlayToggle`.

## 9. Checklist de non-régression (résumé exécutable)
1. Naturel : 3 curseurs + plage de profondeur, démarrer (désactivé en session distante / vitesse 0 / démarrage en cours), arrêter (activé dès connecté), état courant / attente premier plan / préparation.
2. IA Naturel en 2 étapes (Charger Qwen3, puis Utiliser l'IA ; Réessayer ; Ne plus utiliser l'IA), champ d'adresse avec validation locale / Tailscale et bouton OK, relais de raisonnement (port 11435), états modèle (inactif, déchargé, chargement avec secondes, chargé, échec avec cause), statut planificateur, repli, réserve.
3. Voix : état IA, moteur ASR, Écouter / Pause avec demande micro, STOP rapide par mot court, refus micro, transcription, latences, source, transcription ignorée, vitesse max, plage de profondeur (commit seulement), état, motif, étapes ; bouton « i » 48 dp en en-tête ; pas de STOP local, STOP global conservé.
4. STOP : barre globale toujours visible (hors pad Live), `natural_stop`, mot « stop » vocal, `set:speed:0`, annulation des gestes via `liveGestureEpoch`.
5. Session distante : démarrer / arrêter, régénérer, lien en lecture seule, copier (explicite), partager (chooser), message, statut média, bandeau en session, enveloppes, recréation des contrôles à l'entrée / sortie de session.
6. Caméra : lien séparé, WebView verrouillée, permissions Android avant chargement, arrêt au retour / arrière-plan, diagnostics sans fuite de lien.
7. Profils : liste, appliquer (puis retour sur Contrôle), supprimer avec confirmation, état vide, sauvegarde depuis Contrôle.
8. Overlay : interrupteur avec permission système, transparence, `manuallyClosed`.
9. Mises à jour : Vérifier, OTA locale (QR, scanner, adresse manuelle, partager, nombre à 2 chiffres, Oui / Non), PC (adresse, enregistrer, installer, mode développement 15 s), OTA Internet (URL manifeste, télécharger, installer).
10. Firmware USB : flash inclus / fichier, confirmation, progression, journal copiable, écran allumé, défilement auto de la page.
11. Statistiques : cartes, historique, patterns, effacer en 2 étapes, export JSON, import JSON.
12. USB-C : statut vert prioritaire, bandeau / pilule, texte de reconnexion.

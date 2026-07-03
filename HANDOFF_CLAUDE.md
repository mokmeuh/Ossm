# Handoff Claude → ChatGPT/Codex — état au 2026-06-27

Document destiné à l'autre agent (Codex/ChatGPT) pour reprendre le contexte rapidement.
Écrit par Claude. **On travaille tous les deux sur le même repo — voir §Conflit.**

## ⚠️ Conflit inter-agents (IMPORTANT)
Claude et Codex éditent les MÊMES fichiers en parallèle, ce qui cause des écrasements :
- `app_name` a été changé en « Codex V… » puis re-corrigé en « OSSM V… » (convention utilisateur, voir §Conventions)
- `set:stroke/depth` du streaming a oscillé (90/95 ↔ 100/100)
- mode `LAUNCH_ONLY` ajouté à l'enum par Codex
- guerre de `versionCode` (Codex 36, Claude a bumpé à 50)
**Reco : un seul agent édite à la fois, ou se coordonner par ce fichier avant de toucher `BleManager.kt` / `ControlViewModel.kt` / `ControlScreen.kt`.**

## Version actuelle
- **BASELINE DE RÉFÉRENCE : v1.20.5 (commit git `0785248`)** — « celle qui fonctionne le mieux » selon l'utilisateur. En cas de régression, revenir à ce commit.
- **v1.21.2 (versionCode 76, commit `8ee0922`) : COMPILÉE + TESTS OK, PAS ENCORE INSTALLÉE** (téléphone débranché). À installer au retour : `adb install -r app\build\outputs\apk\debug\app-debug.apk`. Ajoute par rapport à 1.21.1 : logo dans l'écran Scanner, README (rebuild + architecture + faits protocole), i18n FR/EN des chaînes statiques de l'écran Contrôle (~20 clés ctl_* dans strings.xml ; reste à migrer : textes avec interpolation, dialogs, écrans Profils/Diagnostics/Scan).
- Installée sur l'appareil : v1.21.0 (bulle patterns + enregistreur, sans l'icône).

### v1.21.x (2026-07-02 soir) — nouveautés à TESTER
- **Boîte Patterns repliable** : toucher « PATTERNS ▾ » → la boîte devient une bulle flottante (62dp) déplaçable partout, qui REFUSE de recouvrir le bouton STOP (esquive auto). Toucher la bulle → la boîte revient. Demandé pour libérer l'écran en mode Live.
- **Enregistreur de mouvement Live** (`LiveRecState` IDLE→ARMED→RECORDING→PLAYING, un seul bouton) : « Enregistrer » → armer ; toucher le pad → REC (échantillonnage 60 ms dans le ticker) ; lever le doigt → boucle immédiate (silence initial coupé, jonction douce 300 ms) ; bouton devient « Arrêter la boucle » ; toucher le pad pendant la boucle = reprise manuelle. Boucle annulée par Stop/pause/changement de pattern.
- **Anti-désync gestes rapides (1.20.5)** : rappels de position ±1 alterné toutes les 350 ms (le firmware plante sur 2 positions identiques consécutives) + 2 rappels en fin de geste.
- **Icône de lanceur** : vraie icône adaptive (drawable/ic_launcher_foreground.xml vector du logo branding, fond #141422) — remplace le placeholder violet. Dans la 1.21.1.
- Live jugé « beaucoup plus fluide » par l'utilisateur en 1.20.5 ; saccades résiduelles = firmware plafonné en vitesse (speed:80).

### ⚡ REPRISE IMMÉDIATE (2026-07-02 soir) — état exact et tâches restantes
**Live (streaming) — état :** fonctionne « légèrement » selon l'utilisateur. Corrigé en 1.20.4 :
- DIRECTION définitive : sur CE firmware `stream:100` = home/début, `stream:0` = fond (test utilisateur sans ambiguïté : doigt 0 % → moteur au bout). L'app INVERSE (`pos = 100 - slider`, clamp [2,98]). NE PLUS Y TOUCHER sans nouveau test filmé.
- Envoi : ticker 60 ms, durée 90 ms (chevauchement anti-saccades), cible = position brute du doigt (vitesse machine = vitesse du doigt). Pas de file firmware (les commandes en avance s'exécutent séquentiellement → une file rejoue l'historique en retard, dangereux).
- Plafond `set:speed:80` en streaming (était 100 — jugé dangereux après incident).

**⚠️ BUGS OUVERTS à traiter en priorité :**
1. **4-5 allers-retours violents à l'activation du Live** (rapport utilisateur, DANGEREUX). Suspect n°1 : re-homing du firmware à l'entrée en streaming (va-et-vient de calibration) ; suspect n°2 : commandes stream résiduelles en file. Vidéo utilisateur en cours de téléchargement : `video_full.mp4` à la racine du repo (~325 Mo, Quick Share). L'analyser (ffmpeg dispo dans la sandbox Cowork) + croiser avec Diagnostics.
2. **Suivi encore saccadé** (avant 1.20.4 — retester avec le chevauchement 60/90 ; si insuffisant, envisager la caractéristique latence …1030 = "true" + buffer, prévue exactement pour ça, à tester prudemment).
3. **Cognement au fond en Simple Stroke** (0-78+ ; 0-66 OK). Diagnostic prêt : au moment du cognement, lire la barre rouge / `position` (state JSON). Si ≈ depth commandé → calibration machine surestime la course (→ proposer limite machine MESURABLE, PAS codée en dur — l'utilisateur l'a explicitement refusée). L'utilisateur doit d'abord REDÉMARRER l'OSSM (re-mesure de course au boot ; le « Home » de l'app ne re-home pas).
4. Vérifier le SENS du funscript depuis la re-inversion.
5. Re-tester SavePresetDialog / AbruptChangeDialog (restaurés depuis une vieille version git après une corruption de fichier).

**Tests unitaires** : passent (`rebuild_install.bat` → `build_result.log`). Version installée vérifiable : `adb shell dumpsys package com.ossm.remote.debug | grep versionName` (adb : voir §Build).

**Reste du prompt initial non couvert :** i18n à étendre (seul l'écran Funscript utilise strings.xml FR/EN), intégration de l'icône launcher depuis `branding/`, git commit (dernier commit antérieur à tout ce travail — EN FAIRE UN), doc d'architecture pour le port iOS.

### 2026-07-02 (après-midi) — v1.19.2→1.20.0 : MTU, direction streaming, ménage, UI
- **FIX RACINE MTU (v1.19.5)** : Android n'a pas de file GATT → requestMtu lancé en même temps que le CCCD était perdu → MTU restait 23 → état JSON tronqué/illisible → app aveugle (Live « Préparation » infini, vérifications impossibles). Séquençage strict : requestMtu → onMtuChanged → CCCD → onDescriptorWrite → lecture patterns + config bouton (`postSetupDone`).
- **DIRECTION STREAMING (v1.19.8)** : CONFIRMÉ SUR APPAREIL : `stream:0` = arrière/home, `stream:100` = fond (= doc officielle). L'ancienne inversion (handoffs précédents ERRONÉS là-dessus) envoyait la machine à l'envers et faisait dépasser la butée arrière. Mapping direct, clamp [2,98] (2 % de marge aux DEUX butées).
- **speedBLE / bouton physique (v1.19.4)** : `set:speed` écrit `speedBLE`, plafonné par le bouton par défaut ; caractéristique …1010 mise à "false" (réécrite à chaque entrée streaming car l'écriture faite à la connexion peut être perdue).
- **Ménage anti-aveugle (v1.19.7)** : `navigateToMode(target)` (menu→mode piloté par l'état réel) remplace tous les délais aveugles (ActivatePattern, homing — plus de faux états fabriqués) ; rampe Progressif attend l'état strokeEngine réel ; `applyStrokeEngineVerified` (params vérifiés + réessais, remplace le delay 1200 ms).
- **Garde asymétrique (v1.19.2)** : confirmation seulement si l'intensité AUGMENTE (plus vite / plus profond / course allongée).
- **Fusion patterns (v1.19.6)** : la liste machine (lisible depuis le fix MTU) ne remplace plus les modes applicatifs (Auto Random/Progressif/Live), elle rafraîchit juste les noms des patterns stroke-engine.
- **UI v1.20.0** : barre ROUGE = position réelle (`positionMm` du state JSON, échelle = max |pos| observé) sur le slider profondeur ; − min à gauche / + max à droite (52dp) ; titre « CONTRÔLE — <mode> » centré ; patterns en grille 2 lignes défilante + bouton « + » ouvrant la réorganisation par appui long (ordre persisté : UserHabitsRepository/pattern_order) ; boutons « Régler la plage au toucher » retirés (assistant conservé en code, sans point d'entrée) ; scroll de page désactivé quand un pad tactile est affiché ; pad consomme ses gestes.
- **AUTO_HOLD/BUILDUP** : constantes AutoMix recréées (buildup 12 min, hold 2.5-20 s) après une perte de fichier — vérifier le ressenti.
- **LEÇON OUTILLAGE** : ne JAMAIS éditer un même fichier en alternant outils Cowork (Write/Edit) et bash/python (lag de sync du mount → troncatures : BleManager ×2, ControlViewModel, ControlScreen, MainActivity, MachineState — tout reconstruit et compile, mais SavePresetDialog/AbruptChangeDialog restaurés depuis la version git ANCIENNE : re-tester ces deux dialogs).
- **En suspens** : cognement au fond en Simple Stroke à retester après redémarrage machine (dérive du zéro suspectée) ; sens du funscript à vérifier depuis la correction de direction ; i18n à étendre au-delà de l'écran Funscript ; envisager un commit git (dernier commit très ancien).

### 2026-07-02 — v1.19.x : Live débloqué, assistant de plage, UX une page
- **v1.18 fix « Préparation » bloquée** : le setup pleine course est envoyé **depuis le menu AVANT `go:streaming`** (les set: semblaient rejetés une fois dans le mode), revérifié via l'état NOTIFY (`stroke>=99 && depth>=99`, PAS de gate sur speed — possiblement rééchelonné par le bouton physique), 3 essais dans le mode en secours. Si non prêt, l'UI affiche l'état machine brut (état/stroke/depth/speed + indice preflight → baisser le bouton vitesse physique).
- **Assistant de plage au toucher** (`RangeWizardState`, `startRangeWizard/captureRangePoint/cancelRangeWizard`) : streaming officiel → l'utilisateur amène la machine au FOND puis au RETRAIT via le pad → `depthMax=fond`, `depthMin=retrait` → retour auto au pattern d'origine (activatePattern renvoie les paramètres). Plage minimale 5 %. AUCUNE commande non documentée (le "fancy mode"/setupDepth de la lib StrokeEngine n'est PAS exposé par le firmware OSSM — firmwares distincts, ne pas confondre).
- **Contrôle immédiat à la connexion** : `autoEnterModeOnConnect()` — à la connexion, si la machine est au menu et le pattern actif est STROKE_ENGINE, entre automatiquement dans le mode (vitesse forcée à 0, jamais de mouvement auto). Corrige le rituel « Stop + re-cliquer le pattern ».
- **UX** : patterns en UNE rangée défilante horizontale (plus de grille 3×N), interligne 10dp, boutons ± des sliders agrandis (52dp/26sp), boutons ± ajoutés sur Min/Max de la plage de profondeur (pas de 1 %).
- **Build** : `rebuild_install.bat` (tests → installDebug) journalise dans `build_result.log`. Le PC a l'outil Windows-MCP PowerShell → lancer le .bat et lire le log, pas de captures d'écran. adb : `C:\Users\mikae\Documents\New project\OSSM-Web-Control\build-tools\android-sdk\platform-tools\adb.exe`.
- **Problème machine en suspens** : cognement au fond en Simple Stroke dès depth≈78 % (0-66 OK) → suspicion dérive du zéro (moteur désactivé à chaque go:menu, course re-mesurée seulement au redémarrage ; le bouton Home de l'app ne re-home PAS réellement). À retester après redémarrage machine ; si persiste → ajouter butée logicielle réglable.
- Branding : `branding/` (logo principal, monochrome, icône app SVG + README).

- précédent installé : 1.17.3 / 57

### 2026-07-01 (session Cowork #2) — v1.18.0 : FIX mode Live (slider 0-100 ≠ home→fond)
Bug rapporté : « le zéro à cent [du slider live] ne représente pas le zéro-cent du home ».
Cause racine : `triggerStreamingEntry` envoyait `go:menu`/`go:streaming`/`set:*` avec des **délais aveugles** (600/400ms). Si un `set:stroke:100`/`set:depth:100` tombait pendant la transition, le firmware le rejetait (`fail:`) et gardait la bande stroke/depth du mode précédent → `depthOffset ≠ 0` dans `streaming_logic.h` → le slider 0-100 couvrait une bande compressée/décalée au lieu de home→fond. Doc de référence : docs.researchanddesire.com (operating-modes + BLE) — l'état NOTIFY renvoie `state/speed/stroke/depth/sensation` ≤ 1000ms.
- **`BleManager`** : entrée streaming pilotée par l'ÉTAT RÉEL. `awaitMachineState(timeout, predicate)` ; séquence : go:menu → attendre état `menu` → go:streaming → attendre état `streaming` (hors preflight/homing) → setup speed=60/sens=50/stroke=100/depth=100 → **vérification** via l'état notifié (`stroke==100 && depth==100 && speed>0`) avec 3 essais. Nouveau flux **`streamingReady: StateFlow<Boolean>`** : vrai seulement après vérification ; remis à faux si l'état quitte `streaming`, à la déconnexion, au forceReset. `sendCommand(Stream)` **ignore tout stream: tant que !streamingReady** (jamais de position sur une bande non fiable). Entrée dédupliquée (`launchStreamingEntry`, un seul job).
- **`ControlViewModel`** : `streamingReady` exposé dans `ControlUiState` ; quand il passe à vrai, `streamTarget/streamSent` resynchronisés à 0 (le firmware se replace au home à l'entrée streaming, doc operating-modes). `setStreamActive(true)` restaure `set:speed:60` si l'état machine indique speed=0 (un Stop précédent rendait tous les stream: silencieusement ignorés — 2e bug du live).
- **`FunscriptViewModel`** : `waitForStreamingReady` attend maintenant `bleManager.streamingReady` (bande vérifiée) ; à la reprise après pause, restaure `set:speed:60` (même bug speed=0).
- **UI** : `LiveStreamPad` activé seulement si `streamingReady` ; remise à 0 % du pad quand le mode (re)devient prêt ; texte « Préparation du mode Live… » pendant la confirmation. `MachineState.displayLabel` affiche « Live (streaming) ».
- **Conservé** : inversion slider↔firmware (`stream = 100 - pos`, slider haut = fond), clamp `pos ≥ 3` (~97 % marge anti-butée), garde division-par-zéro (`pos == lastStreamPos`), ticker cadence 35ms/115ms.
- **À FAIRE** : lancer `rebuild_install.bat` (tests + installDebug), tester sur appareil : (1) entrer en Live → attendre « prêt » → vérifier slider 0 = home et 100 ≈ fond ; (2) Stop puis re-toucher le pad → doit répondre ; (3) funscript pause/reprise.

### 2026-07-01 (session Cowork) — Funscript v2 + base i18n (NON encore buildé/installé)
Chantier funscript demandé : mapping profondeur/plage, robustesse, UI, i18n. Édité côté code, **pas encore compilé** (sandbox sans Android SDK — build à faire sur la machine Windows).
- **Mapping profondeur/plage** : `FunscriptUiState` gagne `depthMin`/`depthMax` (0-100) + `speedFactor` (0.5×–2.0×) + `durationMs`. `FunscriptViewModel.mapPosition()` remappe la position brute 0-100 du script dans `[depthMin, depthMax]` AVANT `OssmCommand.Stream`. Le streaming reste stroke=100/depth=100 (mapping linéaire firmware) — on ne touche PAS à `triggerStreamingEntry`, on borne juste la position envoyée. Défaut 0-100 = comportement identique à avant.
- **Vitesse de lecture** : `speedFactor` compresse/dilate le temps (`scaledAt(atMs)=atMs/speed`) pour l'ordonnancement ET la durée de chaque `stream:pos:time`.
- **Robustesse `loadFunscript`** : annule toute lecture en cours, filtre les actions invalides (`atMs>=0`, `pos∈0..100`), trie, gère fichier vide / sans action valide (message d'erreur au lieu de crash), conserve la plage/vitesse déjà réglées. Picker passe de `application/json` à `*/*` (les `.funscript` sont souvent en `application/octet-stream`).
- **i18n (base FR/EN)** : chaînes de `FunscriptScreen` centralisées dans `res/values/strings.xml` + nouveau `res/values-en/strings.xml`. L'écran utilise `stringResource(...)`. C'est le point de départ i18n — les autres écrans restent à migrer.
- **UI** : ajout carte « Plage de profondeur » (RangeSlider) + carte « Vitesse de lecture » (Slider), gros contrôles.
- Fichiers touchés : `viewmodel/FunscriptViewModel.kt`, `ui/screens/FunscriptScreen.kt`, `MainActivity.kt` (2 callbacks : `onDepthRangeChange`, `onSpeedChange`), `res/values/strings.xml`, `res/values-en/strings.xml` (nouveau).
- **À FAIRE** : bump versionName/versionCode, `gradlew.bat assembleDebug`, install, **tester sur appareil** (surtout le mapping de plage : vérifier que depthMax<100 empêche bien d'aller au fond). Ne pas oublier : le funscript envoie du streaming absolu, donc la plage borne la position — c'est plus sûr, pas moins.

### 2026-07-01 — v1.17.1→1.17.3 (correctifs)
- **1.17.1** : `FLAG_KEEP_SCREEN_ON` dans `MainActivity.onCreate` — l'écran ne s'éteint pas tant que l'app est au 1er plan.
- **1.17.2 — FIX profondeur qui cogne au fond** : le firmware est ORIGINAL/non modifié, le bug était côté app. Dans StrokeEngine la course oscille entre `depth - stroke` et `depth`, donc `depth` = le FOND. Le chemin commit (`BleManager.UpdateStrokeEngine`) envoyait `set:stroke` AVANT `set:depth` avec 40ms d'écart : régler 0→78 posait stroke=78 alors que depth valait encore 100 → `[22,100]` → coup au fond pendant ~40ms, puis depth=78 corrigeait. Fix : envoyer `set:depth` AVANT `set:stroke` (baisser le fond d'abord ne peut jamais dépasser). Le chemin live faisait déjà depth-first.
- **1.17.3 — Auto Random : retrait de Simple Penetration du mix**. `AutoRandomMixablePatterns` = uniquement les 7 patterns stroke-engine (avant : Simple Penetration + 7). Deux problèmes réglés d'un coup : (1) "Simple" (Simple Penetration) et "Simple Stroke" apparaissaient tous deux → confusion; (2) Simple Penetration est un MODE firmware distinct → chaque pioche forçait `go:menu→go:simplePenetration` (~1.3s gelé) à l'entrée ET à la sortie, ce qui figeait le mix et tuait l'aléatoire. Défaut `autoSelectedKeys` = simpleStroke+teasingPounding+roboStroke (plus de simplePenetration). Tous les switches sont maintenant des `set:pattern:id` (~120ms).

### 2026-07-01 — v1.17.0 : intelligence, BLE reconnect, sécurité, UI
- **UserHabitsRepository** (nouveau, DataStore `user_habits`) : mémorise le pattern et la plage de profondeur choisis lors de la PREMIÈRE action manuelle de chaque jour calendaire (max 1 capture/jour/type, historique glissant de 8 jours). Au lancement de l'app, `ControlViewModel.init` applique la moyenne/majorité comme valeur par défaut (si ≥3 échantillons). Capture branchée sur `activatePattern()` et `requestDepthRangeChange()`.
- **BLE reconnexion fantôme corrigée** (`BleManager.kt`) : ping `readRemoteRssi()` toutes les 6s pendant que `Connected`, 2 pings sans réponse (~20s) → reset forcé vers `Disconnected`. `connect()` fait maintenant systématiquement `disconnect()+close()` sur l'ancien gatt avant d'en recréer un (évitait un piège BLE Android classique où le stack natif garde la connexion "vivante").
- **Guard vitesse/profondeur — vrai bug corrigé** : le slider temps réel (`setSpeedLive`/`setDepthLive`) appliquait déjà l'état ET la commande BLE PENDANT le drag, donc au relâchement la machine était déjà à la valeur cible avant même que le popup de garde apparaisse, et "Annuler" ne pouvait rien annuler. Fix : la vérification de seuil se fait maintenant DANS `setSpeedLive`/`setDepthLive` elles-mêmes — si le delta dépasse le seuil, l'état ET le BLE sont gelés (pas de mise à jour) et le popup apparaît; `Annuler` ne fait donc plus rien à annuler (rien n'a été envoyé), `Confirmer` applique la valeur cible.
- **UI carré SECURITE** : les 2 gardes (Vitesse=V, Profondeur=P) sont maintenant un petit carré compact sur la ligne STOP/Pause (plus de carte séparée en bas), avec un bouton (i) qui ouvre un dialogue expliquant STOP/Pause-Play/gardes.
- **Bouton Pause/Play redessiné** : rond, ~76dp (plus petit que STOP=96dp), jaune (pause disponible) → vert (play, en pause) au clic, icône Material Pause/PlayArrow.
- **Sensation retirée du mode "Simple Stroke"** (`pattern.key == "simpleStroke"`) — seul le stroke engine "avancé" (Teasing/Robo/etc.) garde ce slider.
- **Patterns en grille 3/rangée** au lieu du scroll horizontal (`PatternButton` inchangé, juste `Modifier.weight(1f)` dans des `Row` chunked par 3).
- Funscript auto-généré depuis vidéo : discuté avec l'utilisateur, PAS implémenté — voir réponse dans la conversation pour l'analyse de faisabilité (détection de mouvement/audio en Python, pas de solution "magique" fiable, recommandation d'un pipeline semi-assisté).
- App : OSSM Remote Android (Kotlin/Compose/Hilt/Room/DataStore)
- Machine de test : OSSM ESP32, firmware **officiel 1.0.30-2-g49e9d36-dirty** (le `-dirty` est normal, c'est le build CI officiel — NON modifié par l'utilisateur, vérifié en flashant le binaire du CDN officiel qui est byte-identique).

## Apprentissages firmware clés (source : clone /tmp/ossm-fw = KinkyMakers/OSSM-hardware)
- Protocole BLE **texte** : `set:speed|stroke|depth|sensation|pattern:<0-100>`, `go:strokeEngine|simplePenetration|streaming|menu`, `stream:<pos>:<timeMs>`.
- UUID service `522b443a-4f53-534d-0001-420badbabe69`, command `...1000...`, state `...2000...` (JSON), speed-knob-config `...1010...`.
- **Streaming** (`stream:pos:time`) : position ABSOLUE. `streaming_logic.h` : `target = -(1-pos/100)*maxStroke - depthOffset`, `maxStroke=min(stroke,depth)/100*M`, `depthOffset=(M-maxStroke)*depth/100`. M = `measuredStrokeSteps` (calibration homing). **stroke=100/depth=100 → mapping linéaire home(0)→fond(-M).**
- Convention position : 0 = home (rétracté), négatif = avancée/profond. L'app **inverse** (`stream = 100 - sliderPos`) car slider haut = profond.
- Le firmware **tronque** tout mouvement trop grand pour le temps donné (d'où saccades si on envoie de gros pas).
- `_recalcTimeOfStroke` : durée d'une course ∝ stroke/speed (utilisé pour le timing du mode Progressif).
- **Preflight** : le bouton vitesse PHYSIQUE doit être à ~0 pour entrer dans un mode. `streaming.preflight` n'a PAS de sortie menu par BLE (seulement long-press physique) → si bloqué, intervention physique requise.
- **USE_SPEED_KNOB_AS_LIMIT** (défaut true) : vitesse réelle = bouton_physique × vitesse_BLE. L'app écrit « false » sur `...1010...` à la connexion pour rendre le BLE indépendant (à fiabiliser, séquencement Android BLE).
- **emergencyStop** (déclenché par `go:menu`) fait `stepper->disableOutputs()` MAIS garde `isHomed=true`. ⇒ entrer en streaming via `go:menu→go:streaming` NE re-home PAS, mais désactive le moteur brièvement (risque de dérive de position si charge).

## Le FIX Live décisif (v1.13.1 — « la version qui marche » selon l'utilisateur)
Le mode Live (streaming) slammait dans le négatif. Cause : à l'entrée Live je (1) forçais `go:strokeEngine` à la connexion et (2) affichais un faux état « homing » et un long délai (1500ms) moteur désactivé. **Corrections :**
- Supprimé l'auto-init `go:strokeEngine` à la connexion (la machine garde son homing physique).
- Entrée Live = `go:menu` → delay(600) → `go:streaming` → setup (PAS de re-homing, délai court).
- Renommé `triggerStreamingHomingCycle` → `triggerStreamingEntry`.
- Bande streaming actuelle : **stroke=100/depth=100** (mapping linéaire 0-100 = home→fond), marge anti-slam via clamp `pos≥3` dans `sendCommand` (~97% max).

## Fonctionnalités en place
- **Patterns** : `Progressif` (rampe auto), `Live` (streaming relatif), 7 Stroke Engine (ids 0-6). (Simple Penet
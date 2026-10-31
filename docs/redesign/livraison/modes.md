# Livraison : modes Naturel et Voix

Worktree `C:\Users\mikki\Documents\Ossm-redesign`, branche `ossm/redesign`. Aucune compilation ni aucun test lancé (consigne : le coordinateur compile). Code relu ligne à ligne, y compris par un contrôle automatique des imports et des icônes.

## Fichiers modifiés

- `app/src/main/java/com/ossm/remote/ui/screens/NaturalControlPanel.kt` (réécrit, mêmes signatures publiques et internes)
- `app/src/main/java/com/ossm/remote/ui/screens/VoiceControlPanel.kt` (réécrit, mêmes signatures publiques et internes)
- `app/src/main/res/values/strings_redesign_modes.xml` et `app/src/main/res/values-en/strings_redesign_modes.xml` (fichiers déjà présents pour ce groupe : j'ai ajouté 4 clés, FR et EN, et utilisé celles qui existaient)

Aucun autre fichier touché. `ControlScreen.kt` n'a pas besoin de changement : `NaturalControlPanel(...)`, `VoiceControlPanel(...)` et `VoiceInfoButton()` gardent exactement leurs paramètres, `NaturalControlActions` et `VoiceControlActions` sont inchangés, ainsi que `naturalStatusLabelRes`, `naturalMotifLabelRes`, `naturalAiProblemText`, `naturalAiFallbackText`, `voiceAsrLabelRes`, `voiceInterpretationRes`, `voiceIgnoreReasonLabel` et `formatVoiceSeconds`.

Clés ajoutées (FR + EN) : `modes_natural_speed_label`, `modes_natural_range_label`, `modes_percent`, `modes_percent_range`. Clés déjà présentes, désormais utilisées : `modes_natural_step1`, `modes_natural_step2`, `modes_natural_step2_locked`, `modes_natural_step2_auto`, `modes_state_todo|running|done|failed|waiting|active`, `modes_voice_heard_title`, `modes_voice_mic_paused`, `modes_voice_step_current`. Les chaînes FR codées en dur : il n'y en avait aucune dans ces deux fichiers (le « i » et les valeurs passaient déjà par `stringResource`, sauf la lettre « i » qui reste un glyphe).

## Changements visuels

Commun : plus aucun hex, aucune taille de police ni aucun dp de style en dur (tout vient de `OssmDimens`, des styles `OssmCaption`, `OssmSectionTitle`, `OssmValue`, `OssmDisplayValue` et de `MaterialTheme.typography`). Tout le texte est à 12 sp minimum (aide en `OssmCaption` 12 sp, lignes d'état 13 sp). Les messages d'état passent par `OssmStatusLine` (icône + texte). Les boutons passent par `OssmButton` (48 dp mini, 56 dp pour Démarrer/Arrêter/Écouter). L'interrupteur du relais et celui du mot court passent par `OssmSwitchRow` (ligne entière cliquable, rôle Switch).

Naturel
- Curseurs : vitesse max en turquoise (`OssmPrimary`) ; énergie et variété en lilas (`OssmSensation`) car elles règlent le ressenti du geste (le bleu `OssmDepth` reste réservé à la profondeur, l'ambre à la prudence).
- Ligne d'état du mouvement : « attente du premier plan IA » et « préparation » en `OssmStatusLine` ambre ; « en cours » devient une tuile (bord vert, flèche, motif traduit, vitesse courante en `OssmDisplayValue`, plage de profondeur en `OssmValue`). La phrase d'origine `natural_values` est conservée comme description lue par TalkBack.
- Boutons : « Démarrer Naturel » en turquoise (action principale) avec flèche ; « Arrêter » (`natural_stop`) reste jaune, texte sombre, icône stop, 56 dp. Mêmes conditions d'activation qu'avant ; le STOP rouge global n'est pas touché.
- Section IA : séparée par un filet, titre en majuscules, puis le champ d'adresse (police à chiffres fixes, fond relevé), puis deux blocs numérotés reliés par un trait vertical : « Étape 1 sur 2 · Modèle sur le PC » (pastille 1) et « Étape 2 sur 2 · Planification par l'IA » (pastille 2). Chaque bloc a une puce d'état icône + mot : étape 1 = À faire / En cours / Terminée / Échec ; étape 2 = À faire / En attente / Activée / Échec. L'étape 2 indique « Disponible une fois le modèle chargé (étape 1) » tant que « Utiliser l'IA » n'est pas proposé, et « Vous pouvez la choisir dès maintenant : la connexion se fera à la fin du chargement » pendant le chargement.
- « Charger Qwen3 » et « Réessayer » sont dans le bloc de l'étape 1 (turquoise, pleine largeur) ; « Utiliser l'IA » et « Ne plus utiliser l'IA » dans le bloc de l'étape 2, avec l'interrupteur du relais, le statut du planificateur, le repli et la réserve.

Voix
- Ordre : état de l'IA vocale, moteur de transcription, zone d'écoute, avertissements (micro refusé, Live pas prêt), curseur vitesse, plage de profondeur, « Dernière écoute » (diagnostic), « Séquence planifiée ». Les curseurs passent donc avant le bloc de diagnostic.
- Zone d'écoute lisible : icône micro + phrase (« Micro ouvert — parlez… » en vert, ou « Micro en pause »), annoncée par TalkBack (`liveRegion`), bord vert quand le micro est ouvert, bouton « Écouter » (turquoise) ou « Pause micro » (secondaire), puis l'interrupteur « STOP rapide par mot court » (56 dp) dans la même zone.
- Diagnostic regroupé dans un bloc « Dernière écoute » : phrase entendue en 16 sp, latences, source d'interprétation, transcription ignorée (ambre avec icône). Le bloc n'apparaît que s'il y a quelque chose à montrer.
- Plan : état de l'interprétation avec icône, motif continu, étapes ; l'étape courante est encadrée en vert avec une flèche et le mot « Étape en cours ».
- Bouton « i » : toujours rond, 48 dp, même description (`voice_info_cd`), même rôle et mêmes tags ; la lettre utilise le style `titleLarge` (20 sp) en serif italique ; la bulle garde son titre, son texte (désormais défilant, lisible à 130 %) et un bouton Fermer de 48 dp.

## Tableau : élément du contrat -> emplacement dans le nouveau code

Lignes de `NaturalControlPanel.kt` (N) et `VoiceControlPanel.kt` (V).

| Élément du contrat | Où |
|---|---|
| N1 texte d'intro | N l.179 |
| N2 avertissement session distante (si `remoteSession.active`) | N l.185 |
| N3 curseur Vitesse max (valeur Remote si session, repère courant, annulation `liveGestureEpoch`) | N l.192 |
| N4 curseur Énergie / N5 Variété | N l.202 / l.211 |
| N6 éditeur de profondeur (slot) | N l.220 |
| N7 ligne d'état (3 variantes exclusives, `natural_ai_waiting`) | N `NaturalMotionStatus` l.257, tuile « en cours » l.274 |
| N8 Démarrer Naturel (`natural_start`, mêmes 5 conditions) | N l.228 |
| N9 Arrêter (`natural_stop`, actif si `connected`) | N l.241, composable l.344 |
| N10 section IA (`natural_ai_section`) | N l.488, tag l.505 |
| Titre et aide de l'IA | N l.510-520 |
| Champ adresse (`natural_ai_address`, Uri, Done, 200 car., erreur, auto-application au clic Charger) | N l.525 ; `loadOrRetry` l.499 |
| Bouton OK (si brouillon modifié, désactivé si invalide) | N l.549 |
| Interrupteur relais de raisonnement (`natural_ai_reasoning_relay`, ligne cliquable) | N l.673 |
| Aide du relais | N l.677 (paramètre `supporting`) |
| État du modèle (`natural_ai_model_state`, 4 états + déchargé) | N l.561-598 |
| Info « modèle présent » (LOADING et installé) | N l.600 |
| Charger Qwen3 (`natural_ai_load`, IDLE) | N l.609 |
| Réessayer (`natural_ai_retry`, FAILED ou plan refusé) | N l.615 |
| Utiliser l'IA (`natural_ai_use`) | N l.680 |
| Ne plus utiliser l'IA (`natural_ai_stop_use`) | N l.690 |
| Statut du planificateur (`natural_ai_status`, cause si indisponible) | N l.637, l.649 |
| Repli IA (`natural_ai_fallback`, seulement en cours) | N l.698 |
| Réserve IA (`natural_ai_reserve`, avec ou sans latence) | N l.705 |
| Double STOP (STOP global inchangé + `natural_stop`) | N l.241 ; barre globale dans `ControlScreen`, non touchée |
| V état de l'IA vocale (`voice_model_state`, 6 états) + chemins attendus | V `VoiceAiStatusBlock` l.544 |
| V moteur de transcription (`voice_asr_engine`) | V l.159 |
| V Écouter / Pause micro (`voice_listen`, permission RECORD_AUDIO, actif si Ready) | V l.168-178 (logique du clic identique), bouton l.254 |
| V libellé d'écoute (`voice_listening`) | V l.248 (zone `voice_listening_state` l.238) |
| V STOP rapide par mot court (`voice_short_word_stop`) | V l.266 |
| V micro refusé | V l.180 |
| V dernière transcription, latences, source, transcription ignorée (4 tags) | V `VoiceHeardBlock` l.312 (l.340, 352, 369, 376) |
| V Live pas prêt (`ctl_live_preparing`) | V l.186 |
| V Vitesse max (voix) | V l.192 |
| V plage de profondeur (`voice_depth_range`, commit seul, `VoiceDepthRange.effective`) | V `VoiceDepthRangeControl` l.281 (logique non modifiée) |
| V titre de séquence, état (`voice_state`), motif (`voice_motif`), plan vide, étapes | V `VoicePlanBlock` l.384, `VoiceStepRow` l.441 |
| V bouton « i » 48 dp (`voice_info`, `voice_info_cd`, Role.Button) | V `VoiceInfoButton` l.484 |
| V bulle du « i » (`voice_info_dialog`, titre, corps, Fermer) | V l.510 |
| V pas de STOP local dans le panneau | V commentaire l.203-204 ; `onStopNow` reste dans `VoiceControlActions` |

Tags ajoutés (nouveaux, sans effet sur les existants) : `natural_running`, `natural_ai_step1`, `natural_ai_step2`, `voice_listening_state`.

## Chaînes Naturel sans accents (strings_natural.xml non modifié)

Dans `app/src/main/res/values/strings_natural.xml` : `natural_intro` (« coherents generes », « telephone », « Demarrer »), `natural_energy` (« Energie »), `natural_variety` (« Variete »), `natural_start` (« Demarrer Naturel »), `natural_stop` (« Arreter »), `natural_preparing` (« Entree »), `natural_remote_hint` (« demarre », « bornee »), `natural_motif_approach_hold_release` (« relache »), `natural_motif_ladder` (« echelle »). À corriger par le coordinateur en UTF-8 sans BOM, sans changer les clés.

## Choix à connaître

- Le « Réessayer » n'est plus jaune mais turquoise (action principale du bloc) ; le jaune reste pour `natural_stop`.
- Le « Démarrer Naturel » n'est plus vert mais turquoise : le vert est réservé à « connecté / en cours ».
- Le repli IA n'est plus en gras (`OssmStatusLine` n'a pas de style gras) ; il garde l'icône d'erreur et la couleur d'erreur.
- Le tag `natural_ai_reasoning_relay` est désormais sur la ligne cliquable (rôle Switch), plus sur l'interrupteur seul : un test qui clique le tag bascule bien le réglage.
- Les tags `*_status`, `voice_model_state`, `voice_state`, `voice_motif`, `natural_ai_waiting` sont posés sur la ligne d'état fusionnée (icône + texte) et non sur le seul texte.
- Les valeurs chiffrées incluses dans des phrases traduites (latences Voix, réserve IA, plan, motif) restent du texte de phrase en `OssmCaption` / 13 sp : elles ne sont pas en police à chiffres fixes.

## Ce que je n'ai pas pu vérifier

- Compilation, `StringsParityTest` et tests JVM : non lancés (pas de Gradle).
- Rendu réel (Moto G, 130 % de police) : non vu. Points à regarder : le libellé flottant du champ « Adresse du PC (ex. 100.x.y.z:11434) » du `OutlinedTextField` peut se tronquer à 130 % ; les deux boutons Démarrer/Arrêter côte à côte passent à deux lignes sur petit écran (voulu, pas de texte coupé) ; la tuile « en cours » en `OssmDisplayValue` à 130 %.
- Le comportement de `ControlSlider` (couleur du libellé, cible de 48 dp, état lu par TalkBack) n'est pas dans mon périmètre.
- Contraste mesuré à la main sur les jetons (texte sombre `OssmBackground` sur `OssmWarning`, `OssmOnSurfaceMuted` sur `OssmSurfaceRaised`) : supérieur à 4,5:1 d'après MASTER.md, non revérifié à l'écran.
- Aucun test Compose n'existe sur ces panneaux ; les tags du contrat sont tous conservés.

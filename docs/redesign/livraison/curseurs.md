# Livraison : curseurs et pad Live (habillage seulement)

Groupe : `ControlSlider`, `PrecisionScalarSlider`, `SliderFrame`, `RangeBarControl` / `RangeBarSlider`, `ProgressiveSpeedBar`, `LiveAnchorPad`, `StreamPositionSlider`. Aucun Gradle lancé, aucune opération Git. Rien dans `session/`, `ble/`, `viewmodel/`, ni dans `LiveAnchorState`, `LiveSliderState`, `LiveLimits`, `PrecisionSliderState`.

## 1. Fichiers modifiés

Dans `app/src/main/java/com/ossm/remote/ui/components/` :
- `ControlSliderMetrics.kt` : métriques, `SliderFrame`, `BipolarStyle`, deux aides de dessin partagées (`drawSliderTrack`, `drawSliderThumb`).
- `ControlSlider.kt` : `ControlSlider`, `PrecisionScalarSlider` (dessin seulement), boutons −/+ (`RampButton`).
- `RangeBarSlider.kt` : `RangeBarControl`, boutons −/+ (`RangeStepButton`), `RangeBarSlider` (dessin + sémantique).
- `ProgressiveSpeedBar.kt`.
- `StreamPositionSlider.kt` (assistant de plage, aujourd'hui inatteignable).
- `LiveAnchorPad.kt`.

Nouveaux fichiers : `app/src/main/res/values/strings_redesign_curseurs.xml` et `app/src/main/res/values-en/strings_redesign_curseurs.xml` (13 clés `sld_*`, UTF-8 sans BOM, parité FR/EN et mêmes marqueurs de format). Aucun fichier de chaînes existant n'est touché.

Les blocs de gestes (`pointerInput`, `awaitEachGesture`, `detectTapGestures`), les `rememberUpdatedState`, les `LaunchedEffect`, les calculs et les rappels sont restés identiques ligne à ligne. Les `testTag` : il n'y en avait aucun dans ces six fichiers, donc rien à préserver ni à ajouter. `liveControlMinimumHeight/Width`, `liveSliderTrackThickness` (28 / 20 dp), `liveSliderThumbRadius`, `liveEndpointLabels`, `commitLiveAccessibilityPosition`, `finishLiveTouchGesture`, `liveAnchorAccessibilityStep` et `LIVE_ANCHOR_ACCESSIBILITY_STEP_PERCENT` sont inchangés (tests existants).

## 2. Changements visuels

Curseurs à barre (`ControlSlider`, `RangeBarSlider`, `ProgressiveSpeedBar`) :
- Piste de 8 dp (`OssmDimens.SliderTrack`) : fond sombre `OssmSurfaceHigh` cerné d'un filet `OssmOutline` (contraste 4:1, l'ancienne piste teintée à 28 % faisait 2:1). Remplissage à la couleur du canal.
- Poignée ronde de 28 dp (`OssmDimens.SliderThumb`) : anneau clair `OssmOnSurface` de 4 dp, centre à la couleur de la valeur. Zone tactile = hauteur du curseur (56 / 88 / 52 dp, donc au moins 48 dp ; le test androidTest de 56 dp en Regular reste vrai). Marge de piste portée à 14 dp (rayon de la poignée) pour qu'elle ne déborde jamais sur les boutons −/+.
- Canaux : nom du canal en couleur du canal (`SliderFrame(channelColor)`), valeur à droite en `OssmValue` (mono, chiffres fixes : 16 sp en Regular et Inline, 20 sp en Large ; elle était à 28 / 20 / 36 sp). Vitesse `OssmPrimary` (défaut de `ControlSlider`), profondeur `OssmDepth`, sensation `OssmSensation` (portée par `BipolarStyle.channelColor`, donc automatique pour tout curseur signé).
- Boutons −/+ : ronds, fond `OssmSurfaceRaised`, filet `OssmOutline`, icônes Remove/Add à la couleur du canal (plus de glyphes texte). Écart de 8 dp avec la piste (4 dp en ligne unique).
- Plage à deux poignées : la poignée que les boutons −/+ déplacent est pleine, avec halo, dessinée dessus ; l'autre est creuse avec un petit point de couleur. La différence est de forme, plus seulement un halo à 22 %.
- Curseur signé : vert / rouge / repère bleu conservés, mais via des jetons (`OssmConnected`, `OssmError`, `OssmDepth`) au lieu des hex en dur. `SignedPositiveColor`, `SignedNegativeColor`, `CenterTickBlue` gardent leur nom.
- Limite imposée : nouveau paramètre optionnel `limited` (par défaut faux) sur `SliderFrame`, `ControlSlider` et `ProgressiveSpeedBar`. Il affiche cadenas + mot « limité » + valeur en `OssmWarning`, poignée ambre pour le maximum Progressif, et « limité » est lu par TalkBack.
- Étiquette de précision (« Plage 0–100 », « Précision ±N ») : 11 sp passé à 12 sp (`OssmCaption`), le gris « global » passe en `OssmTextFaint`.
- Barre Progressif : piste grise, zone 0 à maximum teintée (marge utilisable), remplissage plein = vitesse courante (lecture seule), poignée ronde = maximum déplaçable. L'ancienne barre rouge `OssmError` disparaît (le rouge reste réservé à l'aléatoire et à l'erreur). Libellé par défaut « Vitesse (auto) » maintenant traduisible.
- Tailles de zones : `toggleMinSize` de la métrique Inline passe de 36 à 48 dp (la case Aléatoire / Max fait donc 48 dp en paysage).

Pad Live (`LiveAnchorPad`) et assistant (`StreamPositionSlider`) :
- Fond uni `OssmSurfaceRaised` + filet `OssmOutline`, coins 16 dp (dégradé violet/cyan supprimé).
- Valeur courante en `OssmDisplayValue` (32 sp, 24 sp en compact) ; légende en `OssmCaption` 12 sp.
- Canal du pad = `OssmDepth` (bague de portée max, trait ancre-doigt, repère d'ancre, pouce).
- Texte du canvas (graduations, « reste à N ») : 11 sp passé à 12 sp, police à chiffres fixes, couleur `OssmOnSurfaceMuted` (7:1) au lieu de 55 % d'opacité ; arcs de portée et ligne d'ancre un peu plus visibles.
- Lignes de limites : `OssmWarning` (pointillée en déplacement, pleine verrouillée), zone interdite teintée ambre, mot « limite haute / limite basse » écrit dans la zone interdite et petit cadenas dessiné quand la ligne est verrouillée. L'état ne tient plus à la seule couleur.
- Assistant de plage : piste sombre cernée d'un filet, remplissage `OssmDepth` 45 %, barre pleine `OssmDepth` avec contour clair.

Accessibilité (TalkBack) :
- `RangeBarSlider` : `ProgressBarRangeInfo` (borne sélectionnée), `setProgress` qui règle la borne sélectionnée en appelant `onRangeChange` puis `onRangeCommit` (mêmes rappels que le glissé), `stateDescription` « a – b, borne haute sélectionnée », deux actions personnalisées « Régler la borne basse / haute » qui appellent `onSelectEnd` (comme un appui sur la barre).
- `ProgressiveSpeedBar` : `ProgressBarRangeInfo` (maximum), `setProgress` qui appelle `onMaxChange` (le même rappel que le glissé), `stateDescription` « Vitesse actuelle X pour cent, maximum Y pour cent » (+ « limité »), actions personnalisées ±1 %.
- `ControlSlider` : sémantique déjà complète conservée ; ajout optionnel de « limité » à l'état.

## 3. Tableau : élément du contrat → emplacement dans le nouveau code

| Élément du contrat (inventaire A) | Où c'est maintenant |
|---|---|
| `ControlSlider` : en-tête (nom, accessoire, valeur) + rangée `[−][piste][+]` | `ControlSlider` (ControlSlider.kt) + `SliderFrame` (ControlSliderMetrics.kt) |
| Prise relative dans le rayon de saisie, saut hors rayon, détente, commit au relâché, annulation sans commit | `PrecisionScalarSlider`, bloc `pointerInput` inchangé |
| Marqueur courant (trait 3 dp, `markerColor`) | `PrecisionScalarSlider`, bloc de dessin (`markerFraction`) |
| Repère central bipolaire + remplissage depuis le centre, vert / rouge | `PrecisionScalarSlider` (dessin) ; couleurs dans `BipolarStyle` |
| Étiquette de plage (précision active ou palier atteint) | `PrecisionScalarSlider`, `Text(rangeLabel)` |
| Sémantique : `contentDescription`, `stateDescription`, `ProgressBarRangeInfo`, `setProgress` (applique et commit), `disabled()` | `PrecisionScalarSlider`, modificateur `semantics` |
| +/− : appui = ±1 % synchrone, maintien progressif 1,5 + 0,6·t³ %/s, commit au relâché ; action d'accessibilité = un pas + commit | `RampButton` (ControlSlider.kt), gestes et `clearAndSetSemantics` inchangés |
| Métriques Regular / Large / Inline (hauteur de piste ≥ 56 en Regular, boutons 56 / 72 / 48, rayon de saisie 28 / 40 / 30) | `ControlSliderMetrics.kt` (`sliderHeight`, `buttonSize`, `grabRadius` inchangés) |
| En-tête en ligne unique (paysage), légendes d'échelle masquées | `SliderFrame` branche `inlineHeader`, `showScaleLegends` conservé |
| `DepthRangeEditor` : double barre, boutons −/+ sur la barre touchée en dernier (haute par défaut), 1 pas = 1 commit | `RangeBarControl.step()` et `RangeStepButton` (RangeBarSlider.kt), logique inchangée |
| Double barre : barres superposées → choix selon le côté du doigt, commit seulement si relâché ET déplacé | `RangeBarSlider`, bloc `pointerInput` inchangé |
| Marqueurs (position observée, valeurs aléatoires) | `RangeBarSlider`, `markers.forEach` |
| Plage de sensation aléatoire bipolaire (`BipolarStyle`, `snapValue`, `valueText`) | `RangeBarControl` / `RangeBarSlider`, paramètres inchangés |
| `ProgressiveSpeedBar` : remplissage = vitesse courante, barre du maximum déplaçable, prise ou saut, rappel à chaque changement | `ProgressiveSpeedBar`, bloc `pointerInput` inchangé ; dessin : piste, zone teintée, remplissage, poignée |
| Valeur affichée `{courante}% · max {max}%` | `ProgressiveSpeedBar` (`sld_progressive_value`) |
| Libellé par défaut « VITESSE (auto) » codé en dur | `ProgressiveSpeedBar`, paramètre `label` par défaut = `sld_speed_auto` (FR et EN) |
| Pad Live : première pression = ancre, demi-disque supérieur, zéro en bas ou sur la limite basse, relâché → `onTouchEnd`, annulation → `onTouchCancel` | `LiveAnchorCanvas`, bloc `pointerInput` inchangé |
| Légende `live_radial_legend` + pourcentage courant | `LiveAnchorPad`, première `Row` |
| Pad désactivé (`enabled=false`) annule le geste ; `DisposableEffect` à la destruction | `LiveAnchorPad`, `LaunchedEffect(enabled)` et `DisposableEffect` inchangés |
| Graduations 25 / 50 / 75 / 100 %, « reste à N », trait ancre-doigt, repère d'ancre, pouce (accent au-dessus de l'ancre, gris sinon) | `LiveAnchorCanvas`, lambda de dessin (partie `clipRect`) |
| Lignes de limites haute et basse (absente / déplacement / verrouillée), zone interdite | `LiveAnchorCanvas`, lambda de dessin (partie `limits?.let`) ; état toujours lu depuis `LiveLimitsState` |
| TalkBack du pad : description, état, `ProgressBarRangeInfo`, `setProgress`, actions ±10 % puis retour lent | `LiveAnchorCanvas`, modificateur `semantics` inchangé |
| Minimum de portée d'ancre `LIVE_ANCHOR_MIN_REACH_DP` | `LiveAnchorCanvas` (`minReachPx`), inchangé |
| Assistant de plage : piste verticale, relâché = `onTouchCancel` (jamais le retour lent), précision 10–90 puis ±40…±5 | `StreamPositionSlider` / `LivePositionTrack`, logique inchangée ; dessin et textes recolorés |
| Fonctions testées `liveControlMinimumHeight/Width`, `liveSliderTrackThickness`, `liveSliderThumbRadius`, `liveEndpointLabels` | StreamPositionSlider.kt, inchangées |
| `signedValueColor`, `BipolarStyle`, `SignedPositiveColor` / `SignedNegativeColor` / `CenterTickBlue` (API publique) | ControlSliderMetrics.kt, mêmes noms et signatures ; `BipolarStyle` reçoit seulement un champ de plus avec valeur par défaut |

## 4. Signatures

Aucune signature existante n'est cassée : uniquement des paramètres ajoutés avec valeur par défaut, en fin de liste ou juste avant le lambda final.
- `SliderFrame(..., channelColor = OssmOnSurfaceMuted, limited = false, controls)`
- `ControlSlider(..., limited = false)`
- `PrecisionScalarSlider(..., stateSuffix = null)`
- `ProgressiveSpeedBar(..., limited = false)` ; le paramètre `label` garde son type `String`, seule sa valeur par défaut change (ressource).
- `BipolarStyle(..., channelColor = OssmSensation)`
- `ControlSliderMetrics(..., controlGap = 8.dp)` ; le champ `symbolSize` est conservé mais n'est plus utilisé (les symboles sont des icônes).

## 5. À reprendre dans `ControlScreen.kt` (hors de mon périmètre, pour l'agent de cet écran)

Rien n'est obligatoire pour compiler, mais pour atteindre le rendu voulu :
1. `DepthRangeEditor` (appel `SliderFrame` vers la ligne 2720) : ajouter `channelColor = OssmDepth`. Sans cela le nom « PROFONDEUR » reste gris (défaut neutre). La double barre elle-même prend déjà la couleur passée dans `activeColor` (`OssmAccent`, alias de `OssmDepth`).
2. Mode aléatoire de la sensation (appel `SliderFrame` vers la ligne 1746) : ajouter `channelColor = OssmSensation`. Le curseur de sensation normal et la double barre signée sont déjà en lilas via `BipolarStyle`.
3. (Fait après relecture, voir controle.md point 18.) Session distante : passer `limited = remoteSession.active` aux curseurs qui affichent l'enveloppe (vitesse max, sensation, `ProgressiveSpeedBar`) et à `SliderFrame` de la profondeur, pour obtenir cadenas + « limité ».
4. Cases Aléatoire / Max (`RandomToggle`) : elles lisent `metrics.toggleMinSize`, maintenant 48 dp en Inline aussi ; la rangée d'un curseur en paysage mesure donc au moins environ 64 dp avec une case.

## 6. Ce que je n'ai pas pu vérifier (pas de compilation, pas d'appareil)

- Compilation : relecture ligne à ligne faite (imports, jetons `OssmDepth` / `OssmSensation` / `OssmTextFaint` / `OssmDisplayValue`, opérateurs `Dp * Float`, `TextUnit * Float`, `Dp / Int`), mais rien n'a été compilé.
- Largeur de piste sur écran étroit (360 dp, Regular) : avec l'écart de 8 dp et la marge de 14 dp, la zone utile de la piste perd environ 8 à 16 dp par rapport à avant ; à vérifier sur le Moto G, surtout en portrait court (métrique Inline, où la ligne unique est déjà serrée).
- Mode Inline avec deux cases dans l'en-tête (Aléatoire + Max de la sensation, colonne de 118 dp) : le débordement existait avant (cases de 36 dp) et subsiste ; à voir en paysage sur appareil.
- Valeur à 16 sp au lieu de 28 sp en Regular : choix du design system (`OssmValue`), plus discret qu'avant ; à confirmer à l'usage dans le noir.
- Sensibilité du pad Live : l'en-tête passe en `OssmDisplayValue` (32 sp, ligne de 38 sp), le canvas est donc plus bas d'environ 6 à 8 dp. Le geste normalise par la portée jusqu'au coin haut le plus éloigné, l'effet est de l'ordre du pour cent, mais le mapping Live est « confirmé sur l'appareil » : à refilmer si on veut être sûr.
- Rendu des textes dessinés dans le canvas à 130 % de taille de police (graduations, mot « limite ») : mesurés avec le `TextMeasurer`, donc mis à l'échelle ; jamais vu à l'écran.
- Comportement réel de TalkBack (actions personnalisées de la plage, `setProgress` sur la borne sélectionnée, pas de 5 % du geste standard) : écrit selon l'API Compose 1.7, non testé avec le lecteur d'écran.
- La couleur de la poignée « pleine / creuse » dans la plage pour les utilisateurs daltoniens : la différence est de forme (point central), à valider visuellement.

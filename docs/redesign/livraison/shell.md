# Livraison : coque (navigation, barre STOP, overlay)

Worktree `C:\Users\mikki\Documents\Ossm-redesign`, branche `ossm/redesign`. Rien n'a été compilé (pas de Gradle, consigne du coordinateur) : relecture ligne à ligne seulement.

## 1. Fichiers modifiés

| Fichier | Nature |
|---|---|
| `app/src/main/java/com/ossm/remote/MainActivity.kt` | Habillage seul : barre d'onglets, bandeau USB, dialogue Quitter, imports. Aucune logique (pager, BackHandler, permissions, USB, caméra plein écran inchangés ; ajout de `imePadding()` sur le Scaffold pour que le clavier ne recouvre ni le STOP ni la barre d'onglets). |
| `app/src/main/java/com/ossm/remote/ui/components/StopBar.kt` | Barre STOP redessinée. `StopBarHeight` passe de 56 à 64 dp (voir point 4). |
| `app/src/main/java/com/ossm/remote/ui/components/StopButton.kt` | STOP rond (non utilisé aujourd'hui), même langage visuel que la barre. |
| `app/src/main/java/com/ossm/remote/ui/components/BleStatusIndicator.kt` | Icône + mot par état (textes passés en ressources). |
| `app/src/main/java/com/ossm/remote/ui/components/HomeButton.kt` | Bouton HOME rond (non utilisé aujourd'hui), plein `OssmPrimary`, accessible. |
| `app/src/main/java/com/ossm/remote/ui/components/PatternButton.kt` | Puce de pattern : état actif lisible sans la couleur. |
| `app/src/main/java/com/ossm/remote/overlay/OverlayUi.kt` | Bulle et panneau de l'overlay. |
| `app/src/main/res/values/strings_redesign_shell.xml` (nouveau) | 13 chaînes FR, préfixe `shell_`. |
| `app/src/main/res/values-en/strings_redesign_shell.xml` (nouveau) | Mêmes 13 clés en EN (placeholders identiques). |

Non modifiés : `ui/navigation/NavGraph.kt` et `NavigationBehavior.kt` (ordre des 4 onglets scan, control, profiles, diagnostics intact), `OverlayService.kt`, tous les fichiers de logique. Signatures publiques inchangées (`StopBar`, `StopButton`, `HomeButton`, `PatternButton`, `BleStatusIndicator`, `OverlayRoot`, `OverlayViewState`, constantes `OVERLAY_*`, `OssmApp`). Seul changement de valeur exposé : `StopBarHeight` (56 -> 64 dp) et la valeur par défaut de `PatternButton.fontSize` (`TextUnit.Unspecified` = 13 sp du style, au lieu de `13.sp` explicite).

## 2. Changements visuels

**Barre STOP** : fond `OssmStop`, icône `StopCircle` + mot « STOP » en `OssmOnStop` (blanc, contraste 5,3), hauteur >= 64 dp, coins 12 dp, plus de liseré rose ni de couleur de texte `OnSurface`. Version debout (paysage) : lettres S, T, O, P empilées, icône ajoutée au-dessus seulement si la barre fait >= 200 dp de haut. Description TalkBack inchangée (`control_stop_bar_cd`), contenu visuel masqué à TalkBack, jamais désactivée.

**Barre d'onglets du bas** : barre pleine largeur sur `OssmSurface`, filet de 1 dp en haut, 4 cellules égales icône + libellé (plus de pilule flottante ni de `NavigationBar` M3). Portrait : icône 24 dp au-dessus du libellé 12 sp, cible 64 dp de haut. Paysage : une rangée fine, icône à côté du libellé 13 sp, cible 48 dp de haut. Écart entre cibles 8 dp. Onglet actif : cellule `OssmSurfaceHigh` + contour `OssmPrimary` de 2 dp + libellé en gras + icône et texte `OssmPrimary` (donc reconnaissable sans la couleur). Libellé sur une ligne avec points de suspension (plus de coupe sèche). Rôle `Tab` + état sélectionné conservés, `testTag` `nav_<route>` ajoutés. Bar toujours masquée en Live nu (condition `liveBare` conservée telle quelle).

**Bandeau « USB perdu, reconnexion »** : n'est plus une pastille posée en haut de l'écran (elle pouvait recouvrir la barre STOP, qui est en haut en portrait) mais une bande dans le flux, au-dessus de la barre d'onglets : icône d'avertissement + texte, annoncée par TalkBack à l'apparition. Elle reste visible aussi en Live nu (la barre d'onglets, elle, y reste masquée). Pendant la reconnexion, le contenu perd donc quelques dp en bas au lieu d'être recouvert.

**Dialogue Quitter** : textes en ressources FR/EN (`shell_quit_title`, `shell_quit_body`, `shell_quit_confirm`, et `ctl_cancel` existant), coins 24 dp, boutons `OssmButton` (Quitter en `Danger`, Annuler en `Text`, 48 dp minimum, tags `quit_confirm` / `quit_cancel`). Comportement identique : `onQuit` arrête la machine puis ferme, `onDismiss` referme.

**Caméra plein écran** : bloc inchangé (l'écran `CameraShareScreen` appartient à un autre groupe).

**Indicateur de connexion** : icône + mot, couleurs `OssmConnected` / `OssmWarning` / `OssmError` / `OssmOnSurfaceMuted` (plus de gris `#808080` ni de couleur `OssmStop` en texte). Icônes : Bluetooth connecté, USB (si l'adresse est `"USB-C"`, mot « USB-C connecté »), recherche, Bluetooth, erreur, STOP, désactivé. Pulsation d'opacité (scan, connexion seulement) au lieu de la mise à l'échelle ; animation de couleur 200 ms. Textes passés en ressources FR/EN : seul changement de libellé « Scan... » -> « Scan en cours… ». Le message d'erreur reste celui de l'état (non traduit, il vient de la couche BLE).

**Puce de pattern** : repos `OssmSurfaceRaised` + contour `OssmOutline` ; actif `OssmSurfaceHigh` + contour `OssmPrimary` de 2 dp + coche devant le nom + `selected` et état « Actif » pour TalkBack. Texte `OssmOnSurface` (fini le texte `OssmBackground` sur fond de couleur), nom sur 2 lignes possibles, hauteur mini 48 dp. Le paramètre `color` est conservé mais n'est plus utilisé (une couleur = un sens ; les 3 couleurs tournantes étaient décoratives).

**Overlay** :
- STOP du panneau : bouton rouge `OssmStop` de 80 x 64 dp (avant : rond de 44 dp), icône + mot « STOP » blancs, placé en premier dans l'en-tête (premier dans l'ordre de lecture TalkBack), rôle Button via `clickable`. Câblage inchangé : `onEmergencyStop` du service -> `controlSessionStore.emergencyStop()`.
- Réduire et Fermer : cibles 48 dp (avant 32 et 34 dp), séparées de 8 dp.
- Textes : titre 14 sp, statut 12 sp avec icône (avant 11 sp), libellés de curseur 14 sp (avant 10 sp), valeurs en `OssmValue`, message d'attente de confirmation en `OssmStatusLine` (icône + texte, avant 10 sp sur une ligne).
- Curseurs : profondeur min et max en `OssmDepth` (le max n'est plus vert, le vert est réservé à « connecté »), piste inactive `OssmSurfaceHigh`, description TalkBack = nom du curseur. Brouillons, resynchronisation `LaunchedEffect`, cadence 100 ms, blocage du saut au tap : inchangés.
- Bulle : plus d'ombre, contour `OssmOutline`, pastille d'état 14 dp lisible sans la couleur (ronde pleine = connecté, carrée pleine = erreur, anneau vide = hors ligne), gris en dur `0xFF9E9E9E` remplacé par `OssmOnSurfaceMuted`, état annoncé par TalkBack, action d'accessibilité « clic » = même appui court (ouvre le panneau). Geste appui / appui long 1 s / glisser strictement conservé.
- Panneau : coins 16 dp (jeton `CardRadius`), contour `OssmOutline`, marge 12 dp.

## 3. Contrat -> emplacement dans le nouveau code

| Élément du contrat | Où |
|---|---|
| Fenêtre overlay, flags, permission, notification, `ACTION_SHOW/STOP` | `OverlayService.kt`, non touché |
| `OverlayRoot(...)` (bulle ou panneau selon `expanded`) | `OverlayUi.kt` : `OverlayRoot` |
| Bulle : glisser/taper, appui long 1 s | `OverlayBubble` + `detectOverlayDragOrTap` (inchangé) |
| Point de statut de la bulle (erreur / connecté / gris) | `bubbleDotColor` + forme et anneau dans `OverlayBubble` |
| Panneau : bouton fermer | `OverlayPanel`, `IconButton` (`Icons.Default.Close`, `onOverlayClose`) |
| Panneau : titre / statut | `OverlayPanel` (titre) + `OverlayStatus`, `statusLabel`, `statusColor`, `statusIcon` |
| Panneau : réduire | `OverlayPanel`, `IconButton` (`KeyboardArrowDown`, `onCollapse`) |
| Panneau : STOP (`onEmergencyStop`, cd `overlay_emergency_stop`) | `OverlayStopButton` |
| Curseurs vitesse / prof. min / prof. max, brouillons, `LaunchedEffect` | `OverlayPanel` + `OverlaySlider` |
| `overlay_pending_guard` | `OverlayPanel`, `OssmStatusLine(tone = Warn)` |
| Barre du bas : 4 onglets, `pagerState.scrollToPage` | `MainActivity.kt` : bloc `bottomBar` (lambda `onSelect`) + `OssmBottomNav` / `OssmNavItem` |
| Variante paysage (rangée fine) | `OssmBottomNav(landscape = true)` -> `OssmNavItem` rangée |
| Barre masquée en Live nu (`liveBare`) | `MainActivity.kt` : condition `liveBare` du `bottomBar`, conservée mot pour mot |
| Ordre des onglets Scan, Control, Profiles, Diagnostics | `BottomNavScreens` (NavGraph.kt, non touché) |
| Bandeau `ctl_usb_recovering` | `UsbRecoveringBanner` (affiché dans le `bottomBar` quand `usbRecovering`) |
| Dialogue Quitter (`showQuitDialog`, `onQuit`) | `QuitDialog`, appelé en fin de `OssmApp` ; `BackHandler` inchangé |
| Caméra plein écran (`cameraShareUrl`, `Screen.CameraShare`) | `MainActivity.kt`, bloc inchangé |
| Barre STOP : cd `control_stop_bar_cd`, jamais désactivée, testTag `control_stop` (posé par l'appelant) | `StopBar` |
| STOP debout (paysage) | `StopBar(vertical = true)` -> `StopBarStackedContent` |
| `StopButton`, `HomeButton` (sans appelant) | `StopButton.kt`, `HomeButton.kt`, signatures conservées |
| Pattern : clic, appui long, `enabled`, rôle Button | `PatternButton` (`combinedClickable`) |
| Pattern actif | `PatternButton` : fond, contour, coche, `selected`, `stateDescription` |
| Indicateur BLE : états Connected, Scanning, Connecting, Error, EmergencyStop, Disconnected | `BleStatusIndicator` : `bleAppearance` (6 branches exhaustives) |
| Chaînes FR / EN en parité | `strings_redesign_shell.xml` (values et values-en) |

## 4. À signaler

1. **STOP absent en Live nu (inchangé, non traité).** Dans `ControlScreen.kt`, `showStopBar = showStop && !liveBareScreen` : en Live nu aucune barre STOP à l'écran, et la barre d'onglets est aussi masquée. Mon travail n'y touche pas, comme demandé. Sécurité restante : relâché (retour lent), annulation (`stopLiveTouch`), touche volume, STOP de l'overlay quand l'appli passe en arrière-plan.
2. **`StopBarHeight` passe de 56 à 64 dp** (exigence >= 64). `ControlScreen.kt` l'utilise pour la hauteur de la rangée portrait, la largeur du rail paysage (`StopBarHeight + 8.dp` = 72), la taille du bouton Pause et la dimension du verrou de rotation. Conséquence : +8 dp de hauteur en portrait, +8 dp de largeur en paysage. À vérifier par le coordinateur : `CompactControlLayoutTest` (360 x 640 et 640 x 360, pas de défilement de page).
3. **Bandeau USB déplacé** en bas, dans le flux (voir §2) : choix de sécurité, car le STOP est en haut de l'écran Contrôle en portrait ; si le groupe Contrôle déplace le STOP en bas, le bandeau le pousse sans le recouvrir.
4. **Transparence de l'overlay** : le réglage utilisateur (20 à 100 %) s'applique au fond, au contour et au contenu du panneau, mais plus au STOP, qui reste toujours à pleine opacité (corrigé après relecture).
5. `PatternButton.color` n'est plus utilisé : l'appelant (`ControlScreen.kt`) peut cesser de calculer `PatternColors[...]` (non touché).
6. `BleConnectionState.label()` (couche modèle, FR en dur) n'est plus utilisé par l'indicateur ; la fonction reste pour d'autres usages éventuels (journal).

## 5. Ce que je n'ai pas pu vérifier

- Aucune compilation ni test (Gradle interdit) : imports, noms de jetons et signatures relus à la main. Risques résiduels : propriété `selected` / `stateDescription` des sémantiques, `BoxWithConstraints` dans le contenu d'un `Button`, icônes `BluetoothConnected`, `BluetoothSearching`, `Usb`, `Pause` (importées d'après des usages existants dans le dépôt).
- Rendu réel : aucun aperçu ni capture. Largeur des libellés d'onglets à 130 % de taille de police sur 360 dp (estimé suffisant, avec points de suspension en filet de sécurité), hauteur du STOP debout en paysage sur petit écran à grande police (l'icône disparaît sous 200 dp, les lettres restent), hauteur du panneau overlay en paysage (+12 dp environ).
- TalkBack : ordre de lecture et annonces non testés sur appareil.
- Overlay : pas de test sur appareil de l'appui STOP via `clickable` (remplace `detectTapGestures`) dans la fenêtre `TYPE_APPLICATION_OVERLAY` non focalisable, ni du nouvel emplacement du STOP (à gauche de l'en-tête).
- Tests existants : `OverlayServiceLifecycleTest`, `MainActivityOverlayAutoStartTest`, `NavigationBehaviorTest`, `StringsParityTest` ne devraient pas être affectés (pas de changement de logique, de signature ni de clé divergente), mais n'ont pas été exécutés.

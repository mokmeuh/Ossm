# Livraison : Connexion, diagnostic, statistiques, partage caméra

Groupe traité : écran Connexion (`ScanScreen`, avec `RemoteCameraCard`), Diagnostic, Statistiques, Partage de ma caméra, QR code partagé. Aucune compilation n'a été faite (consigne : le coordinateur compile).

## 1. Fichiers modifiés ou créés

| Fichier | Nature |
|---|---|
| `app/src/main/java/com/ossm/remote/ui/screens/ScanScreen.kt` | réécrit (UI uniquement) |
| `app/src/main/java/com/ossm/remote/ui/screens/DiagnosticsScreen.kt` | réécrit (UI uniquement) |
| `app/src/main/java/com/ossm/remote/ui/screens/StatisticsSection.kt` | réécrit (UI uniquement) |
| `app/src/main/java/com/ossm/remote/ui/screens/CameraShareScreen.kt` | habillage et panneaux modifiés ; WebView, permissions, cycle de vie et journal inchangés |
| `app/src/main/java/com/ossm/remote/ui/components/QrCodeImage.kt` | jetons de forme, repli visible si l'encodage échoue |
| `app/src/main/res/values/strings_redesign_connexion.xml` | nouveau (27 clés `conn_*`) |
| `app/src/main/res/values-en/strings_redesign_connexion.xml` | nouveau (mêmes clés, mêmes marqueurs de format) |

Signatures publiques inchangées : `ScanScreen(...)`, `DiagnosticsScreen(...)`, `CameraShareScreen(...)`, `StatisticsSection(modifier)`, `QrCodeImage(content, contentDescription, modifier)`, `formatDuration(Long)`, `RemoteCameraSection`, et les fonctions `internal` utilisées par les tests (`remoteCameraSection`, `cameraShareStillAllowed`, `uniqueOssmAddress`, `scanAutoConnectAddress`). Aucun fichier de logique (`session/`, `ble/`, `usb/`, `remote/`, `viewmodel/`...) n'est touché.

Ajouts `internal` réutilisables par d'autres écrans : `ConnectionLinkBadge(state, modifier)` et `connectionLinkOf(state)` (ScanScreen.kt), `logLevelStyle(level)` (DiagnosticsScreen.kt, utilisé aussi par le journal caméra).

## 2. Changements visuels

**Connexion**
- Toute la page défile (une seule `LazyColumn`) : plus rien n'est repoussé hors écran sur Moto G ni à 130 %. Ordre : en-tête, bouton principal, détail de la liaison (connecté / connexion / erreur), carte caméra (toujours au-dessus de la liste), liste d'appareils, état vide.
- Indicateur de liaison en icône + mot, couleur `ok` / `warn` / `textMuted` / `error` : USB (adresse `USB-C`), Bluetooth, Connexion…, Recherche…, Hors ligne, Erreur, Arrêt d'urgence. Remplace `BleStatusIndicator` (pastille qui pulsait, textes FR codés en dur) sur cet écran et sur Diagnostic.
- Action principale : `OssmButton` primaire (turquoise, icône Bluetooth). Pendant le scan, bouton secondaire avec roue et « Arrêter le scan » (plus de grand aplat rose).
- Lignes d'appareils : 64 dp mini, rôle Button + libellé d'action TalkBack, filet turquoise pour les OSSM + pastille « OSSM » ; RSSI en `OssmValue` avec un mot (Signal fort / moyen / faible) en plus de la couleur ; ligne atténuée pendant une connexion.
- Titre « OSSM Remote » passé en ressource (titre 20 sp, `heading`).
- Carte caméra : titre en style section, boutons `OssmButton` (primaire / secondaire), messages d'état avec icône (`OssmStatusLine`), confirmation de copie annoncée (liveRegion).

**Diagnostic**
- Chaque ligne du journal affiche icône + mot de niveau (Debug, Info, Alerte, Erreur) en plus de la couleur ; message en monospace 14 sp. L'ancien bleu « Info » (couleur du canal profondeur) est remplacé par le texte neutre.
- Carte d'état : liaison en badge, dernière commande, adresse MAC en colonne (robuste à 130 %).
- Copier / effacer : boutons 48 dp avec fond relevé et contour, 8 dp d'écart. Nouveau retour visuel « Journal copié. » (2,5 s, liveRegion). État vide « Aucune entrée dans le journal. ».

**Statistiques**
- Cartes en `OssmCard` avec titres de section ; durées et compteurs en tableau libellé / valeur (`OssmValue`), TOTAL en `OssmDisplayValue`. Les libellés existants (majuscules) sont passés en casse de phrase dans le code.
- Histogramme : barres unies turquoise (plus de dégradé), barre minimale de 2 dp pour un jour non nul, `contentDescription` + résumé visible « Jours actifs : N sur 30 · pic … (date) ».
- Patterns : une seule couleur de barre (les couleurs cycliques sans légende sont supprimées), nom + pourcentage en tête, « durée · N sessions » dessous ; lignes fusionnées pour TalkBack.
- Actions : Exporter / Importer côte à côte (48 dp, icône + mot, roue pendant l'opération), « Effacer » isolé en bas, pleine largeur, contour rouge. Effacement toujours en 2 étapes, boutons de dialogue 48 dp.

**Partage de ma caméra**
- Marges 16 dp, textes ≥ 14 sp, bouton « Arrêter le partage » en `OssmButton` Danger (rouge, 56 dp, icône), erreurs (lien invalide, moteur arrêté) en cartes icône + texte annoncées par TalkBack, carte de permissions en `OssmButton`, journal avec icône par ligne et mot de niveau devant les alertes et erreurs (plus de « • » ni de couleur seule), titre de journal en style section.

**QR code** : coins `OssmDimens.ControlRadius`, zone de silence 20 dp conservée, texte « QR code indisponible » si le contenu ne peut pas être encodé.

## 3. Tableau « élément du contrat -> emplacement dans le nouveau code »

### ScanScreen.kt (inventaire A, section 2)

| Élément du contrat | Emplacement |
|---|---|
| Scan automatique au premier affichage | `ScanScreen` : `LaunchedEffect(Unit)` (inchangé) |
| Connexion automatique à l'unique OSSM, seulement en `Scanning` | `ScanScreen` : `LaunchedEffect(autoConnectAddress)` ; `scanAutoConnectAddress`, `uniqueOssmAddress` (inchangés) |
| 1 En-tête : logo, titre, version, état BLE | `ScanHeader` + `ConnectionLinkBadge` |
| 2 Bouton scan / arrêter, désactivé si connexion ou connecté | `ScanActionButton` (`enabled = !isConnecting && !isConnected`, `onScan` / `onStop`) |
| 3 `RemoteCameraCard` (tag `remote_camera_card`, heading, toujours visible, au-dessus de la liste) | `RemoteCameraCard`, item `camera` placé avant les appareils |
| Camera NoSession / Starting / Unavailable / Available | `RemoteCameraCard`, `when (section)` ; `remoteCameraSection` inchangé |
| Partager ma caméra, inviter (chooser), copier (IS_SENSITIVE), confirmation | `RemoteCameraCard` branche `Available` ; `copyCameraLink` inchangé |
| `toString()` masquant le lien | `RemoteCameraSection.Available` (inchangé) |
| `cameraShareStillAllowed` | fin de `ScanScreen.kt` (inchangé) |
| 4 Liste d'appareils : compteur, clé = adresse, clic = `onConnect`, inactive pendant connexion | items `devices_title` et `items(devices, key = address)` ; `DeviceRow` |
| `DeviceRow` : icône OSSM / autre, nom, adresse, RSSI coloré, badge OSSM | `DeviceRow`, `ScanOssmBadge`, `scanSignalQuality` |
| 5 Carte « Connecté » + nom | `ScanConnectedCard` |
| 6 Carte « Connexion en cours… » + roue | `ScanConnectingCard` |
| 7 Carte erreur (message de `BleConnectionState.Error`) | `ScanErrorCard` |
| 8 États vides SEARCHING / NONE_FOUND + 3 conseils | `ScanEmptyStateCard`, `ScanTip` ; mêmes conditions dans `ScanScreen` (`emptyState`) |

### DiagnosticsScreen.kt (inventaire A, section 3)

| Élément du contrat | Emplacement |
|---|---|
| Titre `diag_title` | `DiagnosticsScreen` (premier `Text`, heading) |
| Copier le journal (format `heure [tag] message`, cd `diag_copy`) | `DiagActionButton` (tag `diag_copy`), même `joinToString` |
| Effacer (cd `diag_clear`, sans confirmation) | `DiagActionButton` (tag `diag_clear`) -> `onClear` |
| Carte d'état : État BLE, dernière commande (« — » si vide), MAC si connecté | `OssmCard` (tag `diag_state_card`) + `DiagField` |
| Compteur `diag_entries` | rangée sous la carte d'état |
| Liste clé = id, défilement auto à chaque entrée | `LazyColumn` (tag `diag_log_list`), `LaunchedEffect(logs.size)` (inchangé) |
| Ligne : heure `[tag]` + message, niveau | `LogRow`, `logLevelStyle` |

### CameraShareScreen.kt (inventaire B, section 4)

| Élément du contrat | Emplacement |
|---|---|
| Titre (heading), texte d'aide | début de `CameraShareScreen` |
| Bouton « Arrêter le partage » (tag `camera_stop_sharing`, 56 dp) | `OssmButton` Danger -> `stop()` |
| Zone centrale : lien invalide / permissions / moteur arrêté / WebView, même priorité | `when` dans `CameraShareScreen` ; `CameraErrorCard`, `CameraPermissionCard`, `CameraShareWebView` (inchangé) |
| Carte de permissions, bouton Autoriser, bouton réglages si refusé | `CameraPermissionCard` |
| Panneau diagnostic : titre, dernier message (liveRegion), journal défilant | `CameraDiagnosticsPanel`, `CameraJournalLine` |
| BackHandler = stop, ON_STOP arrête, ON_RESUME relit les permissions | `CameraShareScreen` (inchangé) |
| WebView verrouillée, politique d'URL, libération, journal d'ouverture | `CameraShareWebView`, `LaunchedEffect(Unit)` (inchangés) |
| Fond opaque, `systemBarsPadding`, `pointerInput(Unit) {}` | `Column` racine (inchangé, marge 16 dp via `OssmDimens.ScreenGutter`) |

### StatisticsSection.kt (inventaire B, section 5.7)

| Élément du contrat | Emplacement |
|---|---|
| Titre `stat_title` | `StatisticsSection` (heading) |
| Total, aujourd'hui, 7 jours, 30 jours | `OverviewCard` |
| Sessions, durée moyenne, durée max, vitesse moyenne, vitesse max | `SessionsCard` |
| Historique 30 jours, « cumulés », histogramme, dates début / fin | `HistoryCard`, `DailyHistoryChart` |
| Résumé textuel de l'histogramme | `HistoryCard` (`summary`, `chartDescription`) |
| Patterns utilisés, durée, pourcentage, nombre de sessions, barre, état vide | `PatternsCard`, `PatternBar` |
| Effacer en 2 étapes (Continuer, puis Supprimer définitivement, Annuler à chaque étape) | `StatisticsSection` : deux `AlertDialog` ; `ActionsCard` (bouton `stats_reset`) |
| Exporter (état « Export… », `CreateDocument`, nom `ossm_sessions_AAAA-MM-JJ.json`) | `ActionsCard` (`stats_export`), `LaunchedEffect(pendingExportJson)`, `writeExport`, `exportFileName` |
| Importer (état « Import… », `OpenDocument`, erreur `stat_import_error`) | `ActionsCard` (`stats_import`), `readJson` |
| `formatDuration` publique | fin de `StatisticsSection.kt` (inchangée) |

### QrCodeImage.kt (inventaire A, section 4)

| Élément du contrat | Emplacement |
|---|---|
| QR local ZXing, correction M, UTF-8, noir sur blanc, zone de silence 20 dp, rôle Image | `QrCodeImage` (inchangé hors forme et repli) |

## 4. Ce que je n'ai pas pu vérifier

- Aucune compilation, aucun test, aucun rendu : imports et noms relus à la main. À surveiller à la compilation : icônes `BugReport`, `WarningAmber`, `ErrorOutline`, `Usb`, `BluetoothDisabled`, `FileDownload` / `FileUpload` (jeu étendu, déjà utilisé ailleurs pour la plupart) ; `HorizontalDivider` (déjà utilisé avant) ; `LocalClipboardManager` (déprécié, conservé comme avant).
- Rendu à 130 % de police et sur Moto G, paysage de l'écran caméra (le bloc titre + aide + bouton reste au-dessus de l'aperçu, qui peut devenir très petit en paysage ; comportement hérité, non modifié).
- TalkBack : ordre de focus et annonces (badge de liaison `clearAndSetSemantics`, lignes fusionnées, résumé de l'histogramme).
- Contraste réel des tons `OssmTone` sur `OssmSurfaceRaised` (calculé sur les jetons, non mesuré à l'écran).
- Limites conservées volontairement (comportement, pas habillage) : l'export de statistiques échoue toujours en silence ; « Effacer » du journal diagnostic reste sans confirmation ; le journal défile toujours automatiquement à chaque nouvelle ligne ; les textes de `BleConnectionState.label()` et de `MachineState` restent en dur dans le modèle (non utilisés par ces écrans désormais).
- Le statut « USB-C actif » du contrôle (`ControlStatusLine` / `ControlStatusPill`) appartient à `ControlScreen` : non touché ici. L'état USB est visible sur Connexion et Diagnostic via le badge de liaison.
- Les constantes `dp` d'icônes et de repères sont des `private val` préfixées par fichier (`ScanIconSmall`, `StatsChartHeight`, ...) faute de jetons correspondants dans `OssmDimens` ; à migrer vers `OssmDimens` si le coordinateur souhaite les centraliser.
- Tags de test ajoutés : `scan_screen`, `scan_action`, `scan_device_<adresse>`, `scan_empty_state`, `connection_link_badge`, `diag_copy`, `diag_clear`, `diag_state_card`, `diag_log_list`, `camera_stop_sharing`, `camera_error`, `camera_permission_card`, `camera_diagnostics`, `stats_overview`, `stats_sessions`, `stats_history`, `stats_patterns`, `stats_actions`, `stats_export`, `stats_import`, `stats_reset`. Le tag existant `remote_camera_card` est conservé.

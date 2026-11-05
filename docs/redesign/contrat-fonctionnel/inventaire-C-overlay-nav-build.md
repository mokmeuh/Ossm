# Inventaire C : overlay, navigation, thème, build, tests, variante « redesign »

Lecture seule, rien modifié dans le dépôt. Chemins relatifs à `C:\Users\mikki\Documents\Ossm\app\`. Sources Kotlin : `src/main/java/com/ossm/remote/` (abrégé `…/`).

## 1. Overlay (`…/overlay/`)

**OverlayService.kt** (service au premier plan, `@AndroidEntryPoint`)
- Fenêtre `TYPE_APPLICATION_OVERLAY`, `WRAP_CONTENT`, flags `NOT_FOCUSABLE | NOT_TOUCH_MODAL`, gravity TOP|START. Un `ComposeView` y est posé avec un `OverlayComposeOwner` maison (Lifecycle/ViewModelStore/SavedState). Le contenu est enveloppé dans `OssmRemoteTheme { OverlayContent() }` (ligne ~175) : le thème Compose est donc partagé avec l'appli.
- Permission : `Settings.canDrawOverlays(this)` dans `onCreate`, sinon `stopSelf()`. Manifeste : `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, sous-type `floating_control_overlay`. Cette permission est accordée par package : une variante à autre package doit être ré-autorisée à la main.
- Actions : `ACTION_SHOW = "com.ossm.remote.overlay.SHOW"` et `ACTION_STOP = "com.ossm.remote.overlay.STOP"` (lignes ~402-403). Chaînes en dur mais intents toujours explicites (`Intent(this, OverlayService::class.java)`), donc sans conflit entre deux packages.
- Notification : canal `CHANNEL_ID`, `NOTIFICATION_ID`, icône `R.drawable.ic_launcher_foreground` (à remplacer si l'icône change et n'est plus monochrome). Textes `overlay_notification_*`.
- Démarrage/arrêt piloté par `MainActivity` (observer de cycle de vie : démarre `ACTION_SHOW` quand l'appli quitte le premier plan, `stopService` au retour, `OverlayService.manuallyClosed`). `AppForegroundTracker` est dans `OssmApplication.kt`.

**OverlayUi.kt**
- `OverlayRoot(state, transparencyPercent, expanded, dragging, callbacks…)` : bulle (`OverlayBubble`, glisser/taper, point de statut `bubbleDotColor` : `OssmError` / `OssmConnected` / gris `0xFF9E9E9E` en dur) ou panneau (`OverlayPanel`).
- Panneau : bouton fermer (`Icons.Default.Close`), titre/statut, réduire (`KeyboardArrowDown`), **STOP** = `Box` 44 dp rond `OssmStop`, icône `StopCircle` blanche 30 dp, `detectTapGestures { onEmergencyStop() }`, `contentDescription = overlay_emergency_stop`. Curseurs vitesse/profondeur avec brouillons locaux resynchronisés par `LaunchedEffect`. Message `overlay_pending_guard` en `OssmWarning`.
- Couleurs utilisées : `OssmStop`, `OssmError`, `OssmConnected`, `OssmWarning` (+ surfaces). Icônes : `material-icons-extended`.
- Règle : tout comportement de sécurité reste dans `ControlSessionStore` ; redessiner l'UI sans toucher au câblage `onEmergencyStop`.
- Test : `src/test/…/overlay/OverlayServiceLifecycleTest.kt` (Robolectric sdk 34, `Application` de base). Il vérifie le cycle de vie, pas l'apparence.

## 2. MainActivity et navigation

**MainActivity.kt (649 lignes)**
- Hilt, `FLAG_KEEP_SCREEN_ON`, `setDecorFitsSystemWindows(false)`, `setContent { OssmRemoteTheme { OssmApp(...) } }`.
- Permissions : `requestBlePermissions()` (BLUETOOTH_SCAN/CONNECT sur 12+, sinon BLUETOOTH, BLUETOOTH_ADMIN, FINE_LOCATION) via `registerForActivityResult(RequestMultiplePermissions)`; `enableBtLauncher` (ACTION_REQUEST_ENABLE). Caméra/micro demandés dans `CameraShareScreen` et le pipeline Voix.
- USB attach : `onCreate` (si `savedInstanceState == null`) et `onNewIntent` appellent `handleUsbAttachIntent` : si `ACTION_USB_DEVICE_ATTACHED` alors `usbSerialTransport.noteAttach()` + `rescan()`. Manifeste : intent-filter `USB_DEVICE_ATTACHED` + `meta-data` vers `res/xml/usb_device_filter.xml` (vendor 4292 / product 60000 = CP210x).
- APK partagé : intent-filter `ACTION_SEND` `application/vnd.android.package-archive` → `receiveAndInstallSharedApk`.
- Touche matérielle : `dispatchKeyEvent` → `controlVm.hardwareStop()` (volume). À conserver.
- `OssmApp(...)` (ligne ~250-640) : `rememberPagerState(pageCount = { BottomNavScreens.size })`, `HorizontalPager(userScrollEnabled = false)`, `when (BottomNavScreens[it])` pour Scan / Control / Profiles / Diagnostics.
  - Barre du bas : deux variantes. (a) mode « liveBare » (page Contrôle en Live) : `Row` maison 44 dp, `selectable(role = Tab)`, fond `OssmSurface`, sélection `OssmPrimary` à 15 %, `navigationBarsPadding()`. (b) sinon `NavigationBar` M3 + `NavigationBarItem` arrondie 20 dp. Navigation = `pagerState.scrollToPage(...)`, pas de NavController malgré la dépendance `navigation-compose`.
  - Caméra plein écran : `cameraShareUrl != null` → `fullScreenRoute = Screen.CameraShare`, `CameraShareScreen` superposé (pas une page du pager).
  - Bandeau « USB recovering » (`ctl_usb_recovering`) en haut, `showQuitDialog` : `AlertDialog` avec textes **en dur en français** (« Quitter l'application ? », « La machine sera arrêtée… », « Quitter », « Annuler ») : pas dans les strings, donc hors `StringsParityTest`. À migrer vers des ressources si on touche au dialogue.
  - Clavier : `WindowInsets.isImeVisible`.
  - `shouldReturnToScanAfterDisconnect` / `shouldOpenControlAfterConnect` pilotent les changements de page auto.

**ui/navigation/**
- `NavGraph.kt` : `sealed class Screen(route, label, icon)` : `Scan("scan")`, `Control("control")`, `Diagnostics("diagnostics")`, `Profiles("profiles")`, `CameraShare("camera_share")`. `BottomNavScreens = [Scan, Control, Profiles, Diagnostics]`. `Screen.navigationLabel` → `R.string.nav_*` (FR : Connexion, Contrôle, Réglages, Diagnostic). Les `label` en dur du constructeur ne sont pas utilisés à l'écran. Icônes : `Bluetooth`, `Tune`, `BugReport`, `Bookmark`, `Videocam` (material-icons-extended).
- `NavigationBehavior.kt` : deux fonctions pures sur `BleConnectionState`.
- Piège : `when (Screen)` est exhaustif (ajouter un écran impose de mettre à jour `navigationLabel` et le `when` du pager).

## 3. Thème et ressources

- `ui/theme/Color.kt` : vals top-level `OssmBackground 0C1219`, `OssmSurface 16212B`, `OssmSurfaceRaised`, `OssmGlass`(= Surface), `OssmGlassBorder`, `OssmOutline`, `OssmPrimary B69AFF`, `OssmPrimaryLight`, `OssmOnPrimary`, `OssmAccent 65DDEB`, `OssmStop BA2444`, `OssmStopDark`, `OssmConnected`, `OssmWarning`, `OssmError`, `OssmOnSurface`, `OssmOnSurfaceMuted`, `OssmSecondary`, `PatternColors`. Ces noms sont importés directement dans beaucoup d'écrans (ex. `OssmStop` dans 12 fichiers) : changer la valeur est trivial, renommer est invasif.
- `ui/theme/Theme.kt` : `OssmRemoteTheme` = `MaterialTheme(darkColorScheme(...), OssmTypography)`. Sombre seulement, pas de dynamic color, pas de variante claire.
- `ui/theme/Type.kt` : `Typography` partielle (headlineLarge/Medium, titleLarge, bodyLarge/Medium, labelSmall), `FontFamily.SansSerif` partout. Aucune police embarquée (pas de `res/font`, pas de `ui-text-google-fonts`). Beaucoup d'écrans utilisent `fontSize = N.sp` en dur (MainActivity : 12, 13), donc le style typographique n'est pas entièrement centralisé.
- `res/values/themes.xml` : `Theme.OssmRemote` parent `android:Theme.Material.NoActionBar` (thème plateforme, pas AppCompat/MaterialComponents), `windowBackground` noir, barres système transparentes. Pas de `values-night`, pas de `values-v*`.
- `res/values/ic_launcher_background.xml` : couleur `#141422`.
- Icône launcher : seulement `res/mipmap-hdpi/ic_launcher.xml` et `ic_launcher_round.xml` (adaptive-icon : fond `@color/ic_launcher_background`, premier plan `@drawable/ic_launcher_foreground`, vecteur 1,8 Ko). Pas d'`mipmap-anydpi-v26` : suffisant car minSdk 26. Pas de couche `monochrome` (icône thématisée Android 13 non gérée). Le même `ic_launcher_foreground` sert d'icône de notification de l'overlay.
- Manifeste : `android:icon=@mipmap/ic_launcher`, `roundIcon`, `label=@string/app_name`, `theme=@style/Theme.OssmRemote`, `allowBackup=true`, `usesCleartextTraffic=true`. `app_name` n'est PAS dans les `strings.xml` : il est généré par `resValue("string","app_name","OSSM V$versionName")` dans `defaultConfig` (donc « OSSM V2.3.46 »).
- Chaînes : `values/` (FR) et `values-en/` : `strings.xml`, `strings_natural.xml`, `strings_pc_update.xml`, `strings_ui_polish.xml` (préfixe `ui2_`). Pour un redesign, les nouveaux textes vont dans un nouveau fichier `strings_*.xml` des deux dossiers (le test lit tout fichier `strings*.xml`).
- `res/xml/` : `updater_file_paths.xml` (`<cache-path name="updates" path="."/>`), `usb_device_filter.xml`.

## 4. app/build.gradle.kts

- Plugins : AGP 8.9.3, Kotlin 2.3.21 (+ plugin compose), Hilt 2.58, KSP 2.3.4.
- `namespace = "com.ossm.remote"`, `applicationId = "com.ossm.remote"`, minSdk 26, targetSdk 35, compileSdk 35, buildTools 36.0.0, `versionCode = 194`, `versionName = "2.3.46"`, `abiFilters arm64-v8a`.
- `buildConfigField REMOTE_BROKER_BASE_URL` (propriété Gradle `ossmRemoteBrokerUrl`, défaut `https://ossm-remote-2.ossm-remote.workers.dev`). `buildFeatures { compose, buildConfig }`.
- `buildTypes` : `debug` (`applicationIdSuffix ".debug"`, debuggable, sans minify) et `release` (minify + proguard `proguard-rules.pro`, **aucune signingConfig déclarée**). Aucun `productFlavors`. Signature debug = keystore debug par défaut (`~/.android/debug.keystore`).
- Tâche `assembleNamedDebugApk` (Copy) : dépend de `assembleDebug`, copie `build/outputs/apk/debug/app-debug.apk` vers `build/outputs/named-apk/OSSM-Remote-v<versionName>-debug.apk`. Écrit en dur pour le buildType `debug`.
- Dépendances UI : Compose BOM `2024.12.01` (ui, ui-graphics, ui-tooling-preview, `material3` sans version propre, donc la version du BOM), **`material-icons-extended` présent** (via BOM), `navigation-compose 2.8.5`, `activity-compose 1.9.3`, `lifecycle 2.8.7`, `hilt-navigation-compose 1.2.0`. `ui-tooling` en `debugImplementation`. Pas de `ui-text-google-fonts`, pas de `androidx.core:core-splashscreen`, pas de `material` XML (Material Components). Catalogue : `gradle/libs.versions.toml`.
- Attention : le BOM 2024.12.01 donne Material3 ~1.3.1 : pas d'API M3 Expressive. Monter le BOM touche aussi la compatibilité du compilateur Compose (géré par le plugin Kotlin 2.3.21) et peut déplacer des API : à valider par un build complet avant de s'y engager.
- `local.properties` : `sdk.dir` Linux, à basculer vers le SDK Windows avant Gradle (voir CLAUDE.md racine).

## 5. Tests JVM liés à l'UI ou aux ressources

- `res/StringsParityTest` : lit les vrais fichiers `src/main/res/values` et `values-en` (`strings*.xml`), exige mêmes clés (hors `translatable=false`), mêmes placeholders, aucune valeur anglaise vide. Pas de lecture de `themes.xml`, couleurs ou icônes. Piège pour la variante : un dossier `src/redesign/res/values/strings.xml` n'est PAS vu par ce test.
- `ui/navigation/NavigationBehaviorTest` : fige `BottomNavScreens` = `["scan","control","profiles","diagnostics"]` et la logique de retour auto vers Scan / ouverture de Control. Échoue si on change l'ordre ou le nombre d'onglets.
- `ui/screens/` : `ControlScreenBehaviorTest`, `SensationScaleTest`, `VoiceAsrLabelTest`, `RemoteCameraSectionTest`, `ScanScreenAutoConnectTest`, `ObservedPositionRangeTest`, `ControlDraftEditClockTest` + `components/` (`LiveAnchorStateTest`, `LiveSliderStateTest`, `PrecisionSliderStateTest`) : logique d'état Kotlin pure (références à `R.string`/`BuildConfig` dans certains). Ils ne rendent pas Compose; un redesign qui garde les mêmes fonctions/états ne les casse pas.
- `MainActivityOverlayAutoStartTest` et `overlay/OverlayServiceLifecycleTest` : Robolectric `@Config(sdk=[34], application=android.app.Application::class)`.
- `remote/CameraShareDiagnosticsTest` (BuildConfig/URL du broker).
- Tests qui figent le nom de package : `updater/PcUpdatePolicyTest` (chaînes `com.ossm.remote.debug` / URL `/p/<pkg>/update.json`, passées en paramètre, donc indépendantes du build), `updater/LocalOtaHandshakeTest`, `LocalOtaPairingTest`, `LocalOtaTransferTest` (package passé en paramètre aussi). `tools/apk-pull-server/test/lib.test.js` utilise aussi `com.ossm.remote.debug` en exemple.
- Aucun test Compose (UI) JVM; `androidTest` existe mais ne couvre pas l'UI redessinée (à vérifier avant d'y compter).
- Commande utile : `.\gradlew.bat testDebugUnitTest --tests '*StringsParityTest' --tests '*NavigationBehaviorTest'`.

## 6. Installer une variante « redesign » à côté de l'actuelle

### État actuel de l'identité
- Installé aujourd'hui : `com.ossm.remote.debug` (debug). Le release serait `com.ossm.remote`.
- Tout ce qui dépend du package passe déjà par `context.packageName` ou `${applicationId}` : authorities du FileProvider (manifeste `${applicationId}.fileprovider` et 6 appels `"${context.packageName}.fileprovider"` : `ApkInstaller`, `InstalledApkShare`, `LocalOtaTransfer`, `PcUpdateController`, `ReceivedApkInstaller`), `Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)` (`UsbSerialTransport`, `UsbFirmwareFlasher`), URL de manifeste PC `…/p/<packageName>/update.json` (`PcUpdatePolicy`), jumelage OTA (`pkg` dans le TXT mDNS et `hello`). Les `ACTION_*` en dur (`com.ossm.remote.overlay.SHOW/STOP`, `…USB_PERMISSION`, `…FIRMWARE_USB_PERMISSION`, `…remote.STOP_SESSION`, `…REGENERATE_SESSION`) sont des noms d'action internes, utilisés avec des intents explicites ou `setPackage` ou des receivers `NOT_EXPORTED` : sans conflit entre deux applis installées. Pas de permission custom, pas de `<queries>`, pas de ContentProvider autre que le FileProvider, pas de `android:process`/`taskAffinity`/`sharedUserId`.
- Aucune chaîne `com.ossm.remote` ne doit changer pour installer une 2e appli : le `namespace` (R, BuildConfig, packages Kotlin) reste `com.ossm.remote`, seul l'`applicationId` change.

### Solution la plus petite : un buildType `redesign` (pas de productFlavor)
Dans `android { buildTypes { … } }` :
```kotlin
create("redesign") {
    initWith(getByName("debug"))          // debuggable, même signature debug, sans minify
    applicationIdSuffix = ".redesign"     // remplace ".debug" -> com.ossm.remote.redesign
    resValue("string", "app_name", "OSSM Redesign V${defaultConfig.versionName}")
    matchingFallbacks += "debug"
}
```
- `initWith(debug)` copie aussi `signingConfig` : même keystore debug, ce qui est sans importance puisque le package diffère.
- `applicationIdSuffix` du buildType redesign remplace celui copié de debug (il ne s'additionne pas). Résultat : `com.ossm.remote.redesign`. Vérifier avec `aapt dump badging` après build.
- `resValue` de buildType l'emporte sur celui de `defaultConfig` : nom d'icône distinct « OSSM Redesign V2.3.46 ».
- Pour que les outils (`ui-tooling`) soient présents : ajouter `add("redesignImplementation", libs.androidx.ui.tooling)` (optionnel; sans cela les `@Preview` compilent via `ui-tooling-preview` mais l'aperçu Studio ne marche pas).
- Tâche de nommage à ajouter (le code actuel est lié à `debug`) :
```kotlin
tasks.register<Copy>("assembleNamedRedesignApk") {
    dependsOn("assembleRedesign")
    val v = android.defaultConfig.versionName ?: "dev"
    from(layout.buildDirectory.file("outputs/apk/redesign/app-redesign.apk"))
    into(layout.buildDirectory.dir("outputs/named-apk"))
    rename { "OSSM-Remote-v$v-redesign.apk" }
}
```
  Nom de fichier distinct de `…-debug.apk` pour éviter qu'un dossier d'APK partagé (serveur de mise à jour PC, adb-menu) ne confonde les deux.
- Commandes : `.\gradlew.bat assembleRedesign`, `.\gradlew.bat installRedesign` (utiliser `-s <serial>` côté adb si plusieurs appareils), `testRedesignUnitTest` existera aussi (tests exécutés une 2e fois, inutile sauf CI).
- Icône : pour une distinction visuelle sur l'écran d'accueil, créer `src/redesign/res/` avec le même nom de ressource : `mipmap-hdpi/ic_launcher.xml`, `ic_launcher_round.xml`, `drawable/ic_launcher_foreground.xml`, `values/ic_launcher_background.xml`. Le source set d'un buildType écrase les ressources de `main` de même nom. Attention : `ic_launcher_foreground` sert aussi d'icône de notification de l'overlay.
- Code Kotlin du redesign : un source set `src/redesign/java` ne peut pas redéfinir une classe/fonction qui existe dans `main` (doublon de classe). Deux voies :
  1. (recommandée) travailler le redesign directement dans `main` sur une branche, et utiliser le buildType seulement pour l'installation côte à côte avec la version actuelle (APK déjà installé de l'ancienne branche). Aucun changement de structure.
  2. Si les deux UI doivent coexister dans le même code : déplacer `ui/theme/*` (voire quelques écrans) vers `src/classic/java` et `src/redesign/java`, avec `sourceSets.getByName("debug").java.srcDir("src/classic/java")` et idem pour `release`. Plus lourd; à éviter pour `ControlScreen.kt`, `ControlSessionStore.kt`, `BleManager.kt` (consigne : plus petit changement possible).

### Pourquoi pas un productFlavor
Un flavor impose `flavorDimensions` et renomme toutes les variantes : `assembleDebug` devient `assemble<Flavor>Debug`, le chemin `outputs/apk/debug/app-debug.apk` devient `outputs/apk/<flavor>/debug/…`, ce qui casse `assembleNamedDebugApk`, `installDebug`, `adb-menu.cmd`, `CLAUDE.md` et les habitudes. Un buildType n'ajoute qu'une variante et laisse `debug`/`release` intacts.

### Pièges
1. **Mise à jour depuis le PC** : l'appli demande `…/p/<context.packageName>/update.json`; `tools/apk-pull-server` indexe chaque APK par son `package` (via `aapt dump badging`) et `pickNewest` filtre par package. La variante `com.ossm.remote.redesign` récupère donc uniquement ses propres APK et n'écrase jamais `com.ossm.remote.debug`. Il faut seulement que le dossier surveillé contienne l'APK redesign et que son nom diffère. `versionCode` est comparé par package : garder une numérotation croissante.
2. **OTA locale téléphone à téléphone** : exige même `packageName`, même signature et `versionCode` plus grand (`LocalOtaValidation`, `LocalOtaPairing`). Une variante redesign ne peut s'échanger qu'avec une autre variante redesign (Moto G inclus) : installer le redesign sur les deux téléphones, ou utiliser le serveur PC. Signature : les deux utilisent la clé debug du PC, donc identique tant qu'on compile sur ce PC.
3. **Données isolées** : DataStore, Room `ossm_db` (migration destructive), SharedPreferences BLE, réglages d'overlay, profils, séances sont par package. La variante démarre vide (pas de reprise des profils ni du dernier appareil BLE appairé) ; attendre un nouvel appairage.
4. **Modèles Voix** lus dans `getExternalFilesDir("models")` : le chemin devient `/sdcard/Android/data/com.ossm.remote.redesign/files/models/`. Il faut y recopier (adb push) les modèles Whisper/Parakeet/Gemma, lourds. Sans eux le mode Voix est indisponible dans la variante. `app/libs/sherpa-onnx-*.aar` est déjà commun.
5. **Permissions à re-accorder** : Bluetooth, micro, caméra, notifications, `SYSTEM_ALERT_WINDOW` (overlay), « installer des applis inconnues » (REQUEST_INSTALL_PACKAGES) : toutes par package.
6. **Deux applis, une machine** : le BLE de l'ESP32 n'accepte vraisemblablement qu'une connexion à la fois (à confirmer sur l'appareil) ; ne pas lancer les deux simultanément. Les deux overlays pourraient aussi se superposer (deux bulles) si les deux tournent en arrière-plan : fermer l'autre avant de tester.
7. **Intent-filter USB** (CP210x 4292/60000) et `ACTION_SEND` APK : présents dans les deux applis, Android affichera un sélecteur à chaque branchement USB-C (et peut mémoriser « toujours ouvrir avec » pour une seule). Le test du lancement automatique Live par câble doit se faire en désinstallant ou désactivant l'autre, ou en choisissant l'appli dans la boîte de dialogue. Une seule appli peut aussi détenir la permission USB à la fois.
8. **Même nom de package** : si l'on veut garder `com.ossm.remote.debug` avec le nouveau design, la mise à jour par-dessus fonctionne (même signature) mais n'est pas « à côté ».
9. **`resValue app_name`** : défini dans `defaultConfig`; le buildType le surcharge. Si on ajoute un jour un `strings.xml` avec `app_name`, conflit de ressource en double.
10. **`StringsParityTest`** ne voit pas `src/redesign/res/values*`: ne pas y mettre de chaînes traduisibles, ou ajouter un chemin au test.
11. **Texte en dur** dans `MainActivity` (dialogue Quitter) et `Screen.label` : non couverts par la parité FR/EN.
12. **Release** : pas de `signingConfig` release ni de keystore dans le dépôt ; ne pas compter sur un build release signé pour la variante.
13. `local.properties` (`sdk.dir` Linux) doit être basculé vers le SDK Windows pour tout `gradlew` (voir CLAUDE.md) ; un seul build Gradle à la fois.
14. Ne pas commiter `local.properties*`, `Marche/`, les modèles ni les adresses IP des téléphones.

## Résumé de la proposition
Ajouter un buildType `redesign` (`initWith(debug)`, suffixe `.redesign`, `app_name` distinct, tâche `assembleNamedRedesignApk`), éventuellement `src/redesign/res/` pour l'icône. Aucune chaîne `com.ossm.remote` ni autorité à modifier : tout est déjà dérivé de `packageName`/`${applicationId}`. Travailler le design dans `main` sur une branche (pas de duplication Kotlin).

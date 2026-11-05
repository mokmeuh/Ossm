package com.ossm.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.KeyEvent
import com.ossm.remote.voice.VolumeKeyStop
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.remote.RemoteSessionViewModel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ossm.remote.data.repository.OverlayPreferencesRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.isVoicePattern
import com.ossm.remote.usb.FirmwareFlashViewModel
import com.ossm.remote.usb.FirmwareFlashState
import com.ossm.remote.overlay.OverlayService
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.navigation.BottomNavScreens
import com.ossm.remote.ui.navigation.Screen
import com.ossm.remote.ui.navigation.navigationLabel
import com.ossm.remote.ui.navigation.shouldOpenControlAfterConnect
import com.ossm.remote.ui.navigation.shouldReturnToScanAfterDisconnect
import com.ossm.remote.ui.screens.VoiceControlActions
import com.ossm.remote.ui.screens.*
import com.ossm.remote.ui.theme.*
import com.ossm.remote.updater.UpdaterStatus
import com.ossm.remote.updater.UpdaterViewModel
import com.ossm.remote.updater.installApk
import com.ossm.remote.viewmodel.*
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var overlayPreferencesRepository: OverlayPreferencesRepository
    @Inject lateinit var usbSerialTransport: com.ossm.remote.usb.UsbSerialTransport

    private val bleVm: BleViewModel by viewModels()
    private val controlVm: ControlViewModel by viewModels()
    private val diagnosticsVm: DiagnosticsViewModel by viewModels()
    private val profilesVm: ProfilesViewModel by viewModels()
    private val updaterVm: UpdaterViewModel by viewModels()
    private val overlaySettingsVm: OverlaySettingsViewModel by viewModels()
    private val voiceAiVm: com.ossm.remote.viewmodel.VoiceAiViewModel by viewModels()

    /**
     * Relance l'overlay quand on quitte l'app (home / passage vers une autre app) s'il est
     * activé, à condition qu'il n'ait pas été fermé manuellement dans cette session.
     * Une fermeture manuelle pose [OverlayService.manuallyClosed] jusqu'au prochain retour
     * dans l'app. On ne démarre jamais l'overlay au premier plan : [maybeAutoStartOverlay]
     * ne se déclenche que sur onStop (app quittée).
     */
    private val overlayAutoStartObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            stopService(Intent(this@MainActivity, OverlayService::class.java))
            OverlayService.manuallyClosed = false
            // A cable plugged in while the app was away is picked up without restarting the app.
            usbSerialTransport.rescan()
        }
    }

    override fun onStop() {
        super.onStop()
        // Voix: the microphone never stays open behind the screen.
        voiceAiVm.setListening(false)
        // 2.3.29 SAFETY: background / screen off cancels phone producers with STOP; nothing
        // resumes by itself when the app comes back.
        controlVm.onAppBackground()
        maybeAutoStartOverlay()
    }

    /** True while the Control page is the one shown (set by [OssmApp]). */
    @Volatile
    private var controlScreenVisible = false

    /**
     * 2.3.29 hardware STOP: volume up/down stop at once while the Control screen is in the
     * foreground and a motion mode is active; the event (down and up) is consumed so the
     * volume does not change. Otherwise the keys keep their normal behaviour.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VolumeKeyStop.shouldConsume(event.keyCode, controlScreenVisible, controlVm.isMotionModeActive())) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) controlVm.hardwareStop()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* handle result */ }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it } && bleVm.isBluetoothEnabled()) {
            bleVm.startScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycle.addObserver(overlayAutoStartObserver)

        requestBlePermissions()
        updaterVm.checkOnStartup()

        setContent {
            OssmRemoteTheme {
                OssmApp(
                    bleVm = bleVm,
                    controlVm = controlVm,
                    diagnosticsVm = diagnosticsVm,
                    profilesVm = profilesVm,
                    updaterVm = updaterVm,
                    voiceAiVm = voiceAiVm,
                    usbTransport = usbSerialTransport,
                    onControlVisibleChange = { controlScreenVisible = it },
                    onEnableBluetooth = {
                        if (!bleVm.isBluetoothEnabled()) {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    },
                    onQuit = {
                        controlVm.stop()
                        bleVm.disconnect()
                        finish()
                    }
                )
            }
        }
        if (savedInstanceState == null) handleUsbAttachIntent(intent)
        handleSharedApk(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachIntent(intent)
        handleSharedApk(intent)
    }

    /** The system opened the app because the OSSM USB-C cable was plugged in: arm one Live entry. */
    private fun handleUsbAttachIntent(attachIntent: Intent) {
        if (attachIntent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        usbSerialTransport.noteAttach()
        usbSerialTransport.rescan()
    }

    private fun handleSharedApk(sharedIntent: Intent) {
        lifecycleScope.launch {
            val installerIntent = withContext(Dispatchers.IO) {
                com.ossm.remote.updater.receiveAndInstallSharedApk(this@MainActivity, sharedIntent)
            }
            installerIntent?.let(::startActivity)
        }
    }

    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(perms)
    }

    /**
     * Auto-start silencieux de l'overlay à la sortie de l'app : réutilise le même chemin que
     * le toggle de l'écran Profils (startForegroundService, le service gère sa notification
     * foreground). Sautée sans effet si la permission overlay n'est pas accordée, si l'overlay
     * a été fermé manuellement dans la session, s'il est désactivé, ou si l'app est encore
     * au premier plan (onStop transitoire). L'état est revérifié après la lecture async du
     * réglage : on ne démarre jamais l'overlay au premier plan.
     */
    private fun maybeAutoStartOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (OverlayService.manuallyClosed) return
        if (AppForegroundTracker.isAppInForeground) return
        lifecycleScope.launch {
            val enabled = overlaySettingsVm.overlayEnabled.value
                .takeIf { it }
                ?: overlayPreferencesRepository.overlayEnabled.first()
            if (!enabled) return@launch
            // Revérification après la suspension async : l'utilisateur peut être revenu au
            // premier plan ou avoir fermé l'overlay manuellement pendant la lecture du réglage.
            if (OverlayService.manuallyClosed) return@launch
            if (AppForegroundTracker.isAppInForeground) return@launch
            startForegroundService(
                Intent(this@MainActivity, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_SHOW)
            )
        }
    }
}

@OptIn(ExperimentalAnimationApi::class, ExperimentalLayoutApi::class)
@Composable
fun OssmApp(
    bleVm: BleViewModel,
    controlVm: ControlViewModel,
    diagnosticsVm: DiagnosticsViewModel,
    profilesVm: ProfilesViewModel,
    updaterVm: UpdaterViewModel,
    voiceAiVm: com.ossm.remote.viewmodel.VoiceAiViewModel,
    usbTransport: com.ossm.remote.usb.UsbSerialTransport? = null,
    onControlVisibleChange: (Boolean) -> Unit = {},
    onEnableBluetooth: () -> Unit,
    onQuit: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { BottomNavScreens.size })
    val scope = rememberCoroutineScope()
    var previousConnectionState by remember { mutableStateOf<BleConnectionState?>(null) }

    val connectionState by bleVm.connectionState.collectAsState()
    // While the firmware is being written the USB link is handed over to the flasher, which
    // looks like a disconnection: stay on the current page instead of jumping back to Connexion.
    val flashVm: FirmwareFlashViewModel = hiltViewModel()
    val flashState by flashVm.state.collectAsState()
    val usbLinkState by (usbTransport?.linkState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.ossm.remote.usb.UsbLinkState.Absent) }).collectAsState()
    // USB recovering looks like a disconnection too: stay on the page instead of jumping to Connexion.
    val usbRecovering = usbLinkState == com.ossm.remote.usb.UsbLinkState.Recovering
    val flashHoldsNavigation = flashState !is FirmwareFlashState.Idle || usbRecovering
    val controlUiState by controlVm.uiState.collectAsState()
    val remoteSession by controlVm.remoteSessionView.collectAsState()
    val depthByLoadState by controlVm.depthByLoadState.collectAsState()
    val depthByLoadSettings by controlVm.depthByLoadSettings.collectAsState()
    val voiceAiState by voiceAiVm.state.collectAsState()
    val diagnosticsLogs by diagnosticsVm.logs.collectAsState()
    val lastCommand by diagnosticsVm.lastCommand.collectAsState()
    val presets by profilesVm.presets.collectAsState()
    val scannedDevices by bleVm.scannedDevices.collectAsState()
    val machineState by bleVm.machineState.collectAsState()
    val context = LocalContext.current
    val updaterState by updaterVm.uiState.collectAsState()
    val updaterUrl by updaterVm.manualUrl.collectAsState()
    var showQuitDialog by remember { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.isImeVisible

    // Remote session camera (owner side). The camera link stays in memory only: never in
    // saved instance state, logs or navigation arguments.
    val remoteSessionVm: RemoteSessionViewModel = hiltViewModel()
    val remoteSessionState by remoteSessionVm.state.collectAsState()
    val remoteCamera = remoteCameraSection(remoteSessionState)
    var cameraShareUrl by remember { mutableStateOf<String?>(null) }
    val fullScreenRoute: Screen? = if (cameraShareUrl != null) Screen.CameraShare else null
    LaunchedEffect(remoteCamera, cameraShareUrl) {
        // Session stopped/lost or link regenerated: close the share (destroys the WebView).
        val opened = cameraShareUrl
        if (opened != null && !cameraShareStillAllowed(opened, remoteCamera)) cameraShareUrl = null
    }

    val currentRoute = BottomNavScreens[pagerState.currentPage].route
    val scanPageIndex = BottomNavScreens.indexOfFirst { it.route == Screen.Scan.route }
    val controlPageIndex = BottomNavScreens.indexOfFirst { it.route == Screen.Control.route }

    // Back walks the tabs visited, most recent first. Control is the home tab: back from it
    // always offers to quit (never returns to Connexion), and reaching it resets the history.
    val tabHistory = remember { mutableStateListOf<Int>() }
    var lastPage by remember { mutableIntStateOf(pagerState.currentPage) }
    var navigatingBack by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        val page = pagerState.currentPage
        if (page != lastPage) {
            when {
                page == controlPageIndex -> tabHistory.clear()
                navigatingBack -> Unit
                else -> {
                    tabHistory.remove(lastPage)
                    tabHistory.add(lastPage)
                }
            }
            tabHistory.remove(page)
            lastPage = page
        }
        navigatingBack = false
    }
    BackHandler(enabled = !showQuitDialog && !keyboardOpen && fullScreenRoute == null) {
        val previous = tabHistory.lastOrNull()
        if (pagerState.currentPage == controlPageIndex || previous == null) {
            showQuitDialog = true
        } else {
            tabHistory.removeAt(tabHistory.lastIndex)
            navigatingBack = true
            scope.launch { pagerState.scrollToPage(previous) }
        }
    }
    LaunchedEffect(currentRoute, fullScreenRoute) {
        onControlVisibleChange(currentRoute == Screen.Control.route && fullScreenRoute == null)
    }

    LaunchedEffect(connectionState) {
        when {
            flashHoldsNavigation -> Unit
            shouldOpenControlAfterConnect(previousConnectionState, connectionState, currentRoute) -> {
                pagerState.scrollToPage(controlPageIndex)
            }
            shouldReturnToScanAfterDisconnect(previousConnectionState, connectionState) &&
                pagerState.currentPage != scanPageIndex -> {
                pagerState.scrollToPage(scanPageIndex)
            }
        }
        previousConnectionState = connectionState
    }

    // 2.3.43: OSSM plugged in by USB-C and ready -> Control page + Live (same entry as the manual
    // choice: slow, verified, no touch target). Once per plug-in; waits for the machine.
    LaunchedEffect(usbLinkState, machineState, connectionState) {
        val machineReady = machineState.state != "unknown" && machineState.isReady
        if (usbTransport?.consumeLiveAutoEntry(machineReady) == true) {
            pagerState.scrollToPage(controlPageIndex)
            controlVm.enterLiveFromUsb()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        // While a full-screen route covers the tabs, keep them out of TalkBack.
        // imePadding : en bord à bord le clavier ne redimensionne plus la fenêtre ; sans cette marge
        // il recouvrirait la barre STOP et la barre d'onglets pendant une saisie.
        modifier = (if (fullScreenRoute != null) Modifier.clearAndSetSemantics {} else Modifier)
            .imePadding(),
        containerColor = OssmBackground,
        bottomBar = {
            // The Live pad takes the whole screen: the tab bar is hidden there (the pattern
            // button in the control card leads back to the other modes).
            val liveBare = currentRoute == Screen.Control.route &&
                controlUiState.rangeWizard == null &&
                controlUiState.activePattern?.mode == com.ossm.remote.model.PatternControlMode.STREAMING &&
                controlUiState.activePattern?.isVoicePattern() != true
            if (usbRecovering || !liveBare) {
                // Bas de l'écran : bandeau « USB perdu » (en flux, il ne recouvre ni ne pousse
                // jamais le STOP) puis barre d'onglets. Les marges système sont prises ici, une fois.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OssmSurface)
                        .navigationBarsPadding()
                ) {
                    if (usbRecovering) UsbRecoveringBanner()
                    if (!liveBare) {
                        OssmBottomNav(
                            currentRoute = currentRoute,
                            landscape = LocalConfiguration.current.orientation ==
                                android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                            onSelect = { screen ->
                                scope.launch {
                                    pagerState.scrollToPage(BottomNavScreens.indexOf(screen))
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier.padding(padding)
        ) {
            when (BottomNavScreens[it]) {
                Screen.Scan -> {
                ScanScreen(
                    connectionState = connectionState,
                    devices = scannedDevices,
                    onScan = {
                        if (!bleVm.isBluetoothEnabled()) onEnableBluetooth()
                        else bleVm.startScan()
                    },
                    onConnect = { address ->
                        bleVm.connect(address)
                    },
                    onStop = bleVm::stopScan,
                    remoteCamera = remoteCamera,
                    onShareMyCamera = {
                        (remoteCamera as? RemoteCameraSection.Available)?.let { cameraShareUrl = it.cameraUrl }
                    },
                    // Same actions as the Internet session card in Réglages.
                    onStartRemoteSession = remoteSessionVm::start,
                    onRegenerateRemoteLink = remoteSessionVm::regenerate
                )
                }
                Screen.Control -> {
                ControlScreen(
                    connectionState = connectionState,
                    machineState = machineState,
                    uiState = controlUiState,
                    onSpeedCommit = controlVm::requestSpeedChange,
                    onSpeedLive = controlVm::setSpeedLive,
                    onDepthRangeCommit = controlVm::requestDepthRangeChange,
                    onDepthLive = controlVm::setDepthLive,
                    onSensationCommit = controlVm::requestSensationChange,
                    onSensationLive = controlVm::setSensationLive,
                    onProgressiveMaxCommit = controlVm::setProgressiveMaxSpeed,
                    onChaosToggle = controlVm::setChaosAtMax,
                    onStreamDrag = controlVm::setStreamPositionDrag,
                    onStreamTouchEnd = controlVm::endLiveTouch,
                    onStreamTouchCancel = controlVm::stopLiveTouch,
                    onRangeWizardCapture = controlVm::captureRangePoint,
                    onRangeWizardCancel = controlVm::cancelRangeWizard,
                    onPattern = controlVm::activatePattern,
                    onStop = { controlVm.stop() },
                    onAutoToggleSelected = controlVm::toggleAutoSelected,
                    onAutoMaxSpeedChange = controlVm::setAutoMaxSpeed,
                    onAutoIntensityCapChange = controlVm::setAutoIntensityCap,
                    onAutoRandomnessChange = controlVm::setAutoRandomness,
                    onAutoRequestStart = controlVm::requestAutoStart,
                    onAutoCancelStart = controlVm::cancelAutoStart,
                    onAutoConfirmStart = controlVm::confirmAutoStart,
                    onAutoStop = controlVm::stopAuto,
                    onAutoInitialIntensityChange = controlVm::setAutoInitialIntensity,
                    onPause = controlVm::pause,
                    onResume = controlVm::resume,
                    onSavePreset = profilesVm::savePreset,
                    onSpeedGuardEnabledChange = controlVm::setSpeedGuardEnabled,
                    onPrecisionEnabledChange = controlVm::setPrecisionEnabled,
                    onDepthGuardEnabledChange = controlVm::setDepthGuardEnabled,
                    onDepthLimitsChange = controlVm::setDepthLimits,
                    onRemoteDepthRangeChange = controlVm::setRemoteSessionDepthRange,
                    onConfirmPendingChange = controlVm::confirmPendingManualChange,
                    onDismissPendingChange = controlVm::dismissPendingManualChange,
                    onPatternOrderSave = controlVm::savePatternOrder,
                    onRandomToggle = controlVm::setRandomMode,
                    onSensationRandomRangeChange = controlVm::setRandomSensationRange,
                    onDepthRandomToggle = controlVm::setDepthRandom,
                    onRandomPresetSave = controlVm::saveRandomPreset,
                    onRandomPresetApply = controlVm::applyRandomPreset,
                    onRandomPresetEdit = controlVm::editRandomPreset,
                    onLiveInvertToggle = controlVm::setLiveInvert,
                    onLiveAccelerationChange = controlVm::setLiveAcceleration,
                    onLiveRecordToggle = controlVm::toggleLiveRecording,
                    remoteSession = remoteSession,
                    onSpeedCommitWithEpoch = controlVm::requestSpeedChangeAtEpoch,
                    onSpeedLiveWithEpoch = controlVm::setSpeedLiveAtEpoch,
                    onSensationCommitWithEpoch = controlVm::requestSensationChangeAtEpoch,
                    onSensationLiveWithEpoch = controlVm::setSensationLiveAtEpoch,
                    naturalActions = NaturalControlActions(
                        onStart = controlVm::startNatural,
                        onStop = controlVm::stopNatural,
                        onEnergyChange = controlVm::setNaturalEnergy,
                        onVarietyChange = controlVm::setNaturalVariety,
                        onMaxSpeedChange = controlVm::setNaturalMaxSpeed,
                        onAiEnabledChange = controlVm::setNaturalAiEnabled,
                        onAiAddressChange = controlVm::setNaturalAiAddress,
                        onAiLoadModel = controlVm::loadNaturalAiModel,
                        onAiReasoningRelayChange = controlVm::setNaturalAiReasoningRelay,
                    ),
                    voiceActions = VoiceControlActions(
                        onStopNow = controlVm::voiceStopNow,
                        onMaxSpeedChange = controlVm::setVoiceMaxSpeed,
                        onDepthRangeChange = controlVm::setVoiceDepthRange,
                        onShortWordStopChange = controlVm::setVoiceShortWordStop,
                        onListenChange = voiceAiVm::setListening,
                        hasMicrophonePermission = voiceAiVm::hasMicrophonePermission,
                        onMicrophonePermissionResult = voiceAiVm::onMicrophonePermissionResult,
                    ),
                    voiceAi = voiceAiState,
                    voiceModelRoot = remember(voiceAiVm) { voiceAiVm.modelRoot() },
                    resistanceDepth = ResistanceDepthPanelModel(
                        state = depthByLoadState,
                        settings = depthByLoadSettings,
                        canEnable = connectionState is BleConnectionState.Connected &&
                            controlUiState.activePattern?.mode == PatternControlMode.STROKE_ENGINE,
                        onEnabledChange = controlVm::setDepthByLoadEnabled,
                        onSensitivityCommit = controlVm::setDepthByLoadSensitivity,
                        onFloorCommit = controlVm::setDepthByLoadFloor,
                    )
                )
                }
                Screen.Diagnostics -> {
                DiagnosticsScreen(
                    logs = diagnosticsLogs,
                    connectionState = connectionState,
                    lastCommand = lastCommand,
                    onClear = diagnosticsVm::clearLogs
                )
                }
                Screen.CameraShare -> Unit
                Screen.Profiles -> {
                ProfilesScreen(
                    presets = presets,
                    updaterState = updaterState,
                    updaterUrl = updaterUrl,
                    onApply = { preset ->
                        controlVm.applyPreset(preset)
                        scope.launch { pagerState.scrollToPage(controlPageIndex) }
                    },
                    onDelete = profilesVm::deletePreset,
                    onUpdateUrlSave = updaterVm::saveManualUrl,
                    onUpdateCheck = updaterVm::checkNow,
                    onUpdateDownload = updaterVm::download,
                    onUpdateInstall = { apk -> installApk(context, apk) }
                )
                }
            }
        }
    }

    if (fullScreenRoute == Screen.CameraShare) {
        cameraShareUrl?.let { url ->
            CameraShareScreen(
                cameraUrl = url,
                onStopSharing = { cameraShareUrl = null },
                onDiagnostic = diagnosticsVm::recordCamera,
            )
        }
    }
    }

    if (showQuitDialog) {
        QuitDialog(
            onConfirm = onQuit,
            onDismiss = { showQuitDialog = false }
        )
    }
}

/** Hauteur d'une cellule d'onglet en portrait (cible tactile = cellule moins 2 x 4 dp de marge = 64 dp). */
private val NavItemHeightPortrait = 72.dp

/**
 * Barre d'onglets du bas : quatre cellules égales, icône + libellé, séparées du contenu par un
 * filet. Habillage seul : le changement de page est fait par l'appelant ([onSelect]).
 * Portrait : icône au-dessus du libellé (cible de 64 dp de haut). Paysage : icône à côté du
 * libellé sur une seule rangée fine (cible de 48 dp de haut).
 */
@Composable
private fun OssmBottomNav(
    currentRoute: String,
    landscape: Boolean,
    onSelect: (Screen) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OssmDimens.BorderWidth)
                .background(OssmGlassBorder)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
        ) {
            BottomNavScreens.forEach { screen ->
                OssmNavItem(
                    screen = screen,
                    selected = currentRoute == screen.route,
                    landscape = landscape,
                    onClick = { onSelect(screen) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Une cellule d'onglet. L'onglet actif se reconnaît sans la couleur : cellule en surface « haute »,
 * contour de 2 dp et libellé en gras, en plus de l'icône et du texte en `OssmPrimary`.
 * Rôle Tab + état « sélectionné » lus par TalkBack.
 */
@Composable
private fun OssmNavItem(
    screen: Screen,
    selected: Boolean,
    landscape: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    val tint = if (selected) OssmPrimary else OssmOnSurfaceMuted
    val label = stringResource(screen.navigationLabel)
    val labelStyle = (if (landscape) MaterialTheme.typography.labelMedium else OssmCaption)
        .copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    val minHeight = if (landscape) OssmDimens.TouchTarget + OssmDimens.Space8 else NavItemHeightPortrait

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .padding(OssmDimens.Space4)
            .clip(shape)
            .background(if (selected) OssmSurfaceHigh else Color.Transparent)
            .then(
                if (selected) Modifier.border(OssmDimens.BorderWidth * 2, OssmPrimary, shape) else Modifier
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .testTag("nav_" + screen.route),
        contentAlignment = Alignment.Center
    ) {
        if (landscape) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(screen.icon, contentDescription = null, tint = tint, modifier = Modifier.size(OssmDimens.Space24))
                Spacer(Modifier.width(OssmDimens.Space8))
                Text(text = label, color = tint, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(screen.icon, contentDescription = null, tint = tint, modifier = Modifier.size(OssmDimens.Space24))
                Spacer(Modifier.height(OssmDimens.Space4))
                Text(text = label, color = tint, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Bandeau « USB perdu, reconnexion » : icône + texte, annoncé par TalkBack à son apparition.
 * Placé en bas, dans le flux (au-dessus de la barre d'onglets), pour ne jamais recouvrir le STOP.
 */
@Composable
private fun UsbRecoveringBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OssmSurfaceRaised)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = OssmDimens.ScreenGutter, vertical = OssmDimens.Space8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = OssmWarning,
            modifier = Modifier.size(OssmDimens.Space24)
        )
        Spacer(Modifier.width(OssmDimens.Space8))
        Text(
            text = stringResource(R.string.ctl_usb_recovering),
            color = OssmOnSurface,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/**
 * Dialogue « Quitter l'application ? » : textes en ressources (FR/EN), bouton Quitter rouge
 * (la machine est arrêtée avant de fermer), Annuler en bouton texte. Boutons de 48 dp minimum.
 */
@Composable
private fun QuitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        shape = RoundedCornerShape(OssmDimens.SheetRadius),
        title = {
            Text(
                text = stringResource(R.string.shell_quit_title),
                color = OssmOnSurface,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = stringResource(R.string.shell_quit_body),
                color = OssmOnSurfaceMuted,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            OssmButton(
                text = stringResource(R.string.shell_quit_confirm),
                onClick = onConfirm,
                kind = OssmButtonKind.Danger,
                modifier = Modifier.testTag("quit_confirm")
            )
        },
        dismissButton = {
            OssmButton(
                text = stringResource(R.string.ctl_cancel),
                onClick = onDismiss,
                kind = OssmButtonKind.Text,
                modifier = Modifier.testTag("quit_cancel")
            )
        }
    )
}

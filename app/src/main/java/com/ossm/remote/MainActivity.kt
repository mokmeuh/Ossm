package com.ossm.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ossm.remote.data.repository.OverlayPreferencesRepository
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.overlay.OverlayService
import com.ossm.remote.ui.navigation.BottomNavScreens
import com.ossm.remote.ui.navigation.Screen
import com.ossm.remote.ui.navigation.shouldOpenControlAfterConnect
import com.ossm.remote.ui.navigation.shouldReturnToScanAfterDisconnect
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

    private val bleVm: BleViewModel by viewModels()
    private val controlVm: ControlViewModel by viewModels()
    private val diagnosticsVm: DiagnosticsViewModel by viewModels()
    private val profilesVm: ProfilesViewModel by viewModels()
    private val funscriptVm: FunscriptViewModel by viewModels()
    private val videoSyncVm: VideoSyncViewModel by viewModels()
    private val updaterVm: UpdaterViewModel by viewModels()
    private val overlaySettingsVm: OverlaySettingsViewModel by viewModels()

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
        }
    }

    override fun onStop() {
        super.onStop()
        maybeAutoStartOverlay()
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

    // Mode « à l'écoute » : demande la permission micro au moment où l'utilisateur
    // l'active (jamais au démarrage), puis active le réglage si accordée.
    private var pendingListeningEnable = false
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingListeningEnable) controlVm.setListeningMode(true)
        pendingListeningEnable = false
    }

    private fun toggleListeningMode(enable: Boolean) {
        if (!enable) {
            controlVm.setListeningMode(false)
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            controlVm.setListeningMode(true)
        } else {
            pendingListeningEnable = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                    funscriptVm = funscriptVm,
                    videoSyncVm = videoSyncVm,
                    updaterVm = updaterVm,
                    onEnableBluetooth = {
                        if (!bleVm.isBluetoothEnabled()) {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    },
                    onListeningToggle = ::toggleListeningMode,
                    onQuit = {
                        controlVm.stop()
                        bleVm.disconnect()
                        finish()
                    }
                )
            }
        }
        handleSharedApk(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedApk(intent)
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

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OssmApp(
    bleVm: BleViewModel,
    controlVm: ControlViewModel,
    diagnosticsVm: DiagnosticsViewModel,
    profilesVm: ProfilesViewModel,
    funscriptVm: FunscriptViewModel,
    videoSyncVm: VideoSyncViewModel,
    updaterVm: UpdaterViewModel,
    onEnableBluetooth: () -> Unit,
    onListeningToggle: (Boolean) -> Unit,
    onQuit: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { BottomNavScreens.size })
    val scope = rememberCoroutineScope()
    var previousConnectionState by remember { mutableStateOf<BleConnectionState?>(null) }

    val connectionState by bleVm.connectionState.collectAsState()
    val controlUiState by controlVm.uiState.collectAsState()
    val listeningLevel by controlVm.listeningLevel.collectAsState()
    val diagnosticsLogs by diagnosticsVm.logs.collectAsState()
    val lastCommand by diagnosticsVm.lastCommand.collectAsState()
    val presets by profilesVm.presets.collectAsState()
    val funscriptState by funscriptVm.uiState.collectAsState()
    val videoSyncState by videoSyncVm.uiState.collectAsState()
    val scannedDevices by bleVm.scannedDevices.collectAsState()
    val machineState by bleVm.machineState.collectAsState()
    val context = LocalContext.current
    val updaterState by updaterVm.uiState.collectAsState()
    val updaterUrl by updaterVm.manualUrl.collectAsState()
    var showQuitDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = !showQuitDialog) {
        showQuitDialog = true
    }

    val currentRoute = BottomNavScreens[pagerState.currentPage].route
    val scanPageIndex = BottomNavScreens.indexOfFirst { it.route == Screen.Scan.route }
    val controlPageIndex = BottomNavScreens.indexOfFirst { it.route == Screen.Control.route }

    LaunchedEffect(connectionState) {
        when {
            shouldOpenControlAfterConnect(previousConnectionState, connectionState, currentRoute) -> {
                pagerState.animateScrollToPage(controlPageIndex)
            }
            shouldReturnToScanAfterDisconnect(previousConnectionState, connectionState) &&
                pagerState.currentPage != scanPageIndex -> {
                pagerState.animateScrollToPage(scanPageIndex)
            }
        }
        previousConnectionState = connectionState
    }

    Scaffold(
        containerColor = OssmBackground,
        bottomBar = {
            NavigationBar(
                containerColor = OssmSurface.copy(alpha = 0.95f),
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                BottomNavScreens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(BottomNavScreens.indexOf(screen))
                            }
                        },
                        icon = { Icon(screen.icon, screen.label) },
                        label = {
                            Text(
                                text = screen.label,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OssmPrimary,
                            selectedTextColor = OssmPrimary,
                            unselectedIconColor = OssmOnSurface.copy(0.5f),
                            unselectedTextColor = OssmOnSurface.copy(0.5f),
                            indicatorColor = OssmPrimary.copy(0.15f)
                        )
                    )
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
                    onStop = bleVm::stopScan
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
                    onStreamPosition = controlVm::setStreamPosition,
                    onStreamDrag = controlVm::setStreamPositionDrag,
                    onStreamTouchEnd = controlVm::stopLiveTouch,
                    onRangeWizardStart = controlVm::startRangeWizard,
                    onRangeWizardCapture = controlVm::captureRangePoint,
                    onRangeWizardCancel = controlVm::cancelRangeWizard,
                    onPattern = controlVm::activatePattern,
                    onStop = { controlVm.stop() },
                    onHome = { controlVm.home() },
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
                    onDepthGuardEnabledChange = controlVm::setDepthGuardEnabled,
                    onDepthLimitsChange = controlVm::setDepthLimits,
                    onConfirmPendingChange = controlVm::confirmPendingManualChange,
                    onDismissPendingChange = controlVm::dismissPendingManualChange,
                    onPatternOrderSave = controlVm::savePatternOrder,
                    onRandomToggle = controlVm::setRandomMode,
                    onDepthRandomToggle = controlVm::setDepthRandom,
                    listeningLevel = listeningLevel,
                    onListeningToggle = onListeningToggle,
                    onListeningCeilingCommit = controlVm::setListeningCeiling,
                    onListeningTargetSpeedChange = controlVm::setListeningTargetSpeed,
                    onListeningTargetDepthChange = controlVm::setListeningTargetDepth,
                    onLiveInvertToggle = controlVm::setLiveInvert,
                    onLiveAccelerationChange = controlVm::setLiveAcceleration
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
                Screen.Profiles -> {
                ProfilesScreen(
                    presets = presets,
                    updaterState = updaterState,
                    updaterUrl = updaterUrl,
                    onApply = { preset ->
                        controlVm.applyPreset(preset)
                        scope.launch { pagerState.animateScrollToPage(controlPageIndex) }
                    },
                    onDelete = profilesVm::deletePreset,
                    onUpdateUrlSave = updaterVm::saveManualUrl,
                    onUpdateCheck = updaterVm::checkNow,
                    onUpdateDownload = updaterVm::download,
                    onUpdateInstall = { apk -> installApk(context, apk) }
                )
                }
                Screen.Funscript -> {
                FunscriptScreen(
                    uiState = funscriptState,
                    connectionState = connectionState,
                    onLoad = funscriptVm::loadFunscript,
                    onPlay = funscriptVm::play,
                    onPause = funscriptVm::pause,
                    onStop = funscriptVm::stop,
                    onDepthRangeChange = funscriptVm::setDepthRange,
                    onSpeedChange = funscriptVm::setSpeedFactor
                )
                }
                Screen.VideoSync -> {
                VideoSyncScreen(
                    uiState = videoSyncState,
                    connectionState = connectionState,
                    player = videoSyncVm.getOrCreatePlayer(),
                    onVideoUri = videoSyncVm::setVideoUri,
                    onVideoUrl = videoSyncVm::setVideoUrl,
                    onFunscriptUri = videoSyncVm::loadFunscriptFromUri,
                    onFunscriptUrl = videoSyncVm::loadFunscriptFromUrl,
                    onPlayPause = videoSyncVm::togglePlayPause,
                    onStop = videoSyncVm::stop,
                    onLatencyChange = videoSyncVm::setLatencyOffset,
                    onDepthRangeChange = videoSyncVm::setDepthRange,
                    onGenerateFunscript = videoSyncVm::generateFunscriptFromVideo
                )
                }
            }
        }
    }

    if (showQuitDialog) {
        AlertDialog(
            onDismissRequest = { showQuitDialog = false },
            containerColor = OssmSurface,
            title = { Text("Quitter l'application ?", color = OssmOnSurface) },
            text = {
                Text(
                    "La machine sera arrêtée avant de fermer l'application.",
                    color = OssmOnSurface.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = onQuit,
                    colors = ButtonDefaults.buttonColors(containerColor = OssmStop)
                ) {
                    Text("Quitter")
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuitDialog = false }) {
                    Text("Annuler", color = OssmPrimaryLight)
                }
            }
        )
    }
}

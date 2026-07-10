package com.ossm.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.ui.navigation.BottomNavScreens
import com.ossm.remote.ui.navigation.Screen
import com.ossm.remote.ui.navigation.shouldReturnToScanAfterDisconnect
import com.ossm.remote.ui.screens.*
import com.ossm.remote.ui.theme.*
import com.ossm.remote.viewmodel.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val bleVm: BleViewModel by viewModels()
    private val controlVm: ControlViewModel by viewModels()
    private val diagnosticsVm: DiagnosticsViewModel by viewModels()
    private val profilesVm: ProfilesViewModel by viewModels()
    private val funscriptVm: FunscriptViewModel by viewModels()
    private val videoSyncVm: VideoSyncViewModel by viewModels()

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

        requestBlePermissions()

        setContent {
            OssmRemoteTheme {
                OssmApp(
                    bleVm = bleVm,
                    controlVm = controlVm,
                    diagnosticsVm = diagnosticsVm,
                    profilesVm = profilesVm,
                    funscriptVm = funscriptVm,
                    videoSyncVm = videoSyncVm,
                    onEnableBluetooth = {
                        if (!bleVm.isBluetoothEnabled()) {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    },
                    onListeningToggle = ::toggleListeningMode
                )
            }
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
    onEnableBluetooth: () -> Unit,
    onListeningToggle: (Boolean) -> Unit
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

    val currentRoute = BottomNavScreens[pagerState.currentPage].route
    val scanPageIndex = BottomNavScreens.indexOfFirst { it.route == Screen.Scan.route }
    val controlPageIndex = BottomNavScreens.indexOfFirst { it.route == Screen.Control.route }

    LaunchedEffect(connectionState) {
        if (shouldReturnToScanAfterDisconnect(previousConnectionState, connectionState) &&
            pagerState.currentPage != scanPageIndex
        ) {
            pagerState.animateScrollToPage(scanPageIndex)
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
                        scope.launch { pagerState.animateScrollToPage(controlPageIndex) }
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
                    onStreamTarget = controlVm::setStreamTarget,
                    onStreamActive = controlVm::setStreamActive,
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
                    onConfirmPendingChange = controlVm::confirmPendingManualChange,
                    onDismissPendingChange = controlVm::dismissPendingManualChange,
                    onPatternOrderSave = controlVm::savePatternOrder,
                    onLiveRecordToggle = controlVm::toggleLiveRecord,
                    onRandomToggle = controlVm::setRandomMode,
                    onDepthRandomToggle = controlVm::setDepthRandom,
                    listeningLevel = listeningLevel,
                    onListeningToggle = onListeningToggle,
                    onLiveInvertToggle = controlVm::setLiveInvert,
                    onLiveMaxAccelChange = controlVm::setLiveMaxAccel
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
                    onApply = { preset ->
                        controlVm.applyPreset(preset)
                        scope.launch { pagerState.animateScrollToPage(controlPageIndex) }
                    },
                    onDelete = profilesVm::deletePreset
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
}

package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.model.AutoRandomMixablePatterns
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.isVoicePattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.ui.components.BipolarStyle
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.components.signedValueColor
import com.ossm.remote.ui.components.InlineControlSliderMetrics
import com.ossm.remote.ui.components.LargeControlSliderMetrics
import com.ossm.remote.ui.components.LocalControlSliderMetrics
import com.ossm.remote.ui.components.RangeBarControl
import com.ossm.remote.ui.components.RangeBarMarker
import com.ossm.remote.ui.components.RegularControlSliderMetrics
import com.ossm.remote.ui.components.SliderFrame
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.HomeButton
import com.ossm.remote.ui.components.LocalControlPrecisionEnabled
import com.ossm.remote.ui.components.LiveAnchorPad
import com.ossm.remote.ui.components.LiveLimitIcon
import com.ossm.remote.ui.components.LiveLimitSide
import com.ossm.remote.ui.components.LiveLimitsState
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmSwitchRow
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.components.PrecisionScalarSlider
import com.ossm.remote.ui.components.ProgressiveSpeedBar
import com.ossm.remote.ui.components.StopBar
import com.ossm.remote.ui.components.StopBarHeight
import com.ossm.remote.ui.components.StopButton
import com.ossm.remote.ui.components.StreamPositionSlider
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDepth
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmDisplayValue
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmSectionTitle
import com.ossm.remote.ui.theme.OssmSensation
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOnPrimary
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.remote.PhoneHostMotionLimits
import com.ossm.remote.remote.RemoteSessionView
import kotlin.math.roundToInt
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.modes.LocalModeContext
import com.ossm.remote.ui.modes.ModeContext
import com.ossm.remote.ui.modes.ModeRegistry
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.GuardedControl
import com.ossm.remote.viewmodel.LiveRecordingState
import com.ossm.remote.viewmodel.RandomPresetTarget
import com.ossm.remote.viewmodel.RandomRangePreset
import com.ossm.remote.viewmodel.RandomTarget

@Composable
fun ControlScreen(
    connectionState: BleConnectionState,
    machineState: MachineState,
    uiState: ControlUiState,
    onPrecisionEnabledChange: (String, Boolean) -> Unit,
    onSpeedCommit: (Float) -> Unit,
    onSpeedLive: (Float) -> Unit,
    onDepthRangeCommit: (Float, Float) -> Unit,
    onDepthLive: (Float, Float) -> Unit,
    onSensationCommit: (Float) -> Unit,
    onSensationLive: (Float) -> Unit,
    onProgressiveMaxCommit: (Float) -> Unit,
    onChaosToggle: (Boolean) -> Unit,
    onStreamDrag: (Int) -> Unit,
    onStreamTouchEnd: () -> Unit,
    onStreamTouchCancel: () -> Unit = onStreamTouchEnd,
    onRangeWizardCapture: () -> Unit,
    onRangeWizardCancel: () -> Unit,
    onPattern: (String) -> Unit,
    onStop: () -> Unit,
    onAutoToggleSelected: (String) -> Unit,
    onAutoMaxSpeedChange: (Float) -> Unit,
    onAutoIntensityCapChange: (Float) -> Unit,
    onAutoRandomnessChange: (Float) -> Unit,
    onAutoRequestStart: () -> Unit,
    onAutoCancelStart: () -> Unit,
    onAutoConfirmStart: () -> Unit,
    onAutoStop: () -> Unit,
    onAutoInitialIntensityChange: (Float) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSavePreset: (Preset) -> Unit,
    onSpeedGuardEnabledChange: (Boolean) -> Unit,
    onDepthGuardEnabledChange: (Boolean) -> Unit,
    onDepthLimitsChange: (Float, Float) -> Unit,
    onConfirmPendingChange: (Boolean, Boolean) -> Unit,
    onDismissPendingChange: () -> Unit,
    onPatternOrderSave: (List<String>) -> Unit,
    onRandomToggle: (RandomTarget, Boolean) -> Unit,
    onDepthRandomToggle: (Boolean) -> Unit,
    onRandomPresetSave: (RandomPresetTarget, Int) -> Unit,
    onRandomPresetApply: (RandomPresetTarget, Int) -> Unit,
    onRandomPresetEdit: (RandomPresetTarget, Int, Int, Int, Boolean) -> Unit,
    onLiveInvertToggle: (Boolean) -> Unit,
    onLiveAccelerationChange: (Int) -> Unit,
    onLiveRecordToggle: () -> Unit,
    remoteSession: RemoteSessionView = RemoteSessionView(),
    onRemoteDepthRangeChange: (Float, Float) -> Unit = { _, _ -> },
    onSpeedCommitWithEpoch: ((Float, Long) -> Unit)? = null,
    onSpeedLiveWithEpoch: ((Float, Long) -> Unit)? = null,
    onSensationRandomRangeChange: (Float, Float) -> Unit = { _, _ -> },
    onSensationCommitWithEpoch: ((Float, Long) -> Unit)? = null,
    onSensationLiveWithEpoch: ((Float, Long) -> Unit)? = null,
    naturalActions: NaturalControlActions = NaturalControlActions(),
    voiceActions: VoiceControlActions = VoiceControlActions(),
    voiceAi: com.ossm.remote.voice.VoiceAiUiState = com.ossm.remote.voice.VoiceAiUiState(),
    voiceModelRoot: String? = null,
    resistanceDepth: ResistanceDepthPanelModel? = null,
) {
    val connected = connectionState is BleConnectionState.Connected
    val usbControlActive = (connectionState as? BleConnectionState.Connected)?.address == "USB-C"
    val machineBusy = machineState.isHoming || machineState.isPreflight
    val slidersEnabled = connected && !machineBusy
    var showSaveDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var patternPickerOpen by remember { mutableStateOf(false) }
    // Back closes the pattern picker first (registered after the app-level handler, so it wins).
    androidx.activity.compose.BackHandler(enabled = patternPickerOpen) { patternPickerOpen = false }
    var editingRandomPreset by remember { mutableStateOf<RandomPresetEdit?>(null) }
    val connectionSessionAddress = (connectionState as? BleConnectionState.Connected)?.address
    var observedPositionRange by remember(connectionSessionAddress) {
        mutableStateOf(ObservedPositionRange.empty())
    }
    val observedPositionMm = machineState.positionMm
    LaunchedEffect(connectionSessionAddress, observedPositionMm) {
        if (connectionSessionAddress != null && observedPositionMm != null) {
            observedPositionRange = observedPositionRange.observe(observedPositionMm)
        }
    }
    val positionFraction = observedPositionRange.markerFraction
    var showInfoDialog by remember { mutableStateOf(false) }
    var speedDraft by remember { mutableFloatStateOf(uiState.speed) }
    var depthRangeDraft by remember { mutableStateOf(uiState.depthMin..uiState.depthMax) }
    var sensationDraft by remember { mutableFloatStateOf(uiState.sensation) }
    val activePattern = uiState.activePattern
    val actionVisibility = controlActionVisibility(activePattern)
    val precisionEnabled = activePattern?.let { isPrecisionEnabled(it, uiState.precisionOverrides) } ?: false
    // Capture the epoch from the composition that owns a pointer gesture. The Store compares
    // this token against its authoritative StateFlow value, closing the STOP-to-recomposition
    // window before pointerInput's cancellation key is applied.
    val scalarGestureEpoch = uiState.liveGestureEpoch
    fun dispatchSpeedLive(value: Float) {
        onSpeedLiveWithEpoch?.invoke(value, scalarGestureEpoch) ?: onSpeedLive(value)
    }
    fun dispatchSpeedCommit(value: Float) {
        onSpeedCommitWithEpoch?.invoke(value, scalarGestureEpoch) ?: onSpeedCommit(value)
    }
    fun dispatchSensationLive(value: Float) {
        onSensationLiveWithEpoch?.invoke(value, scalarGestureEpoch) ?: onSensationLive(value)
    }
    fun dispatchSensationCommit(value: Float) {
        onSensationCommitWithEpoch?.invoke(value, scalarGestureEpoch) ?: onSensationCommit(value)
    }

    // During a remote session every local slider shows (and sets) the ENVELOPE maximum, never the
    // value the site is driving. When the session ends the drafts fall back to the safe local state
    // (speed 0, last effective depth/sensation). Only drafts change here: no command is sent.
    //
    // 2.3.17: the drafts are written synchronously by the finger, while uiState/remoteSession
    // reach this composable through collectAsState one frame (or more) later. Re-keying on every
    // drag tick used to overwrite the draft with the PREVIOUS tick's value: the RangeSlider thumb
    // and the displayed numbers flickered backwards during a drag. External values are now
    // applied only after a short quiet period following the last local edit, and always from
    // the LATEST state (never the stale snapshot that restarted the effect). Starting/ending a
    // remote session is applied immediately.
    val draftEditClock = remember { ControlDraftEditClock() }
    val latestUiState = rememberUpdatedState(uiState)
    val latestRemoteSession = rememberUpdatedState(remoteSession)
    LaunchedEffect(
        uiState.speed,
        uiState.depthMin,
        uiState.depthMax,
        uiState.sensation,
        uiState.activePatternKey,
        remoteSession.active,
        remoteSession.speedMax,
        remoteSession.depthMin,
        remoteSession.depthMax,
        remoteSession.sensationMin,
        remoteSession.sensationMax
    ) {
        val sessionToggled = draftEditClock.consumeSessionChange(remoteSession.active)
        if (!sessionToggled) {
            val quietMs = draftEditClock.remainingQuietMs(android.os.SystemClock.uptimeMillis())
            if (quietMs > 0L) kotlinx.coroutines.delay(quietMs)
        }
        val ui = latestUiState.value
        val remote = latestRemoteSession.value
        if (remote.active) {
            speedDraft = remote.speedMax
            depthRangeDraft = remote.depthMin..remote.depthMax
            // Sensation is bipolar: the local slider sets the symmetric intensity |s - 0.5|.
            sensationDraft = sessionSensationDraft(sensationDraft, remote.sensationIntensity)
        } else {
            speedDraft = ui.speed
            depthRangeDraft = ui.depthMin..ui.depthMax
            sensationDraft = ui.sensation
        }
    }

    // The Live pad is the whole screen: no STOP bar, rotation lock or connection banner there.
    val liveBareScreen = uiState.rangeWizard == null &&
        uiState.activePattern?.mode == PatternControlMode.STREAMING &&
        uiState.activePattern?.isVoicePattern() != true
    // Live is always played upright: portrait is forced while the pad is on screen and the
    // previous orientation setting is handed back when it leaves.
    val liveActivity = LocalContext.current.findActivity()
    DisposableEffect(liveBareScreen, liveActivity) {
        val previous = liveActivity?.requestedOrientation
        if (liveBareScreen && liveActivity != null) {
            liveActivity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose {
            if (liveBareScreen && liveActivity != null) {
                liveActivity.requestedOrientation = previous
                    ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    val showStopBar = actionVisibility.showStop && !liveBareScreen
    val showActionRail = showStopBar || actionVisibility.showPause
    val statusKind = if (liveBareScreen) null else controlStatusKind(
        usbControlActive = usbControlActive,
        connected = connected,
        machineBusy = machineBusy,
        machineError = machineState.isError
    )
    val settingsScrollState = rememberScrollState()
    val activeModeDescriptor = ModeRegistry.of(uiState.activePattern)
    val modeContext = remember(activeModeDescriptor, connected, slidersEnabled, remoteSession.active) {
        ModeContext(
            mode = activeModeDescriptor,
            connected = connected,
            interactive = slidersEnabled,
            remoteLimited = remoteSession.active
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        CompositionLocalProvider(
            LocalControlPrecisionEnabled provides precisionEnabled,
            LocalModeContext provides modeContext
        ) {
        CompactControlScaffold(
            showActionRail = showActionRail,
            bare = liveBareScreen,
            actionRail = { landscape ->
                CompactActionRail(
                    landscape = landscape,
                    showStop = showStopBar,
                    showPause = actionVisibility.showPause,
                    showRotation = !liveBareScreen,
                    isPaused = uiState.isPaused,
                    pauseEnabled = connected
                        && uiState.activePattern?.mode != PatternControlMode.STREAMING
                        && (uiState.isRunning || uiState.isPaused),
                    onStop = onStop,
                    onPause = onPause,
                    onResume = onResume
                )
            }
        ) { compactLandscape ->
            // Etat de la machine et connexion toujours en haut : une ligne sous le bord de
            // l'ecran en portrait ; en paysage (pas de hauteur a perdre), une pastille dans
            // l'en-tete de la carte. Rien n'est insere dans la ligne STOP.
            if (!compactLandscape) {
                if (statusKind != null) {
                    ControlStatusLine(kind = statusKind, machineState = machineState)
                } else if (!liveBareScreen) {
                    ControlReadyLine(machineState = machineState)
                }
            }

            // Voix shares the STREAMING entry but has its own scrollable panel (no touch pad).
            val liveControlActive = uiState.rangeWizard == null &&
                activePattern?.mode == PatternControlMode.STREAMING &&
                !activePattern.isVoicePattern()
            val liveLimits = remember { LiveLimitsState() }
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    // The card always takes the whole height between the status line and the STOP rail.
                    .weight(1f)
                    .testTag("control_card"),
                padding = if (liveBareScreen) {
                    OssmDimens.Space4
                } else if (compactLandscape) {
                    OssmDimens.Space12
                } else {
                    OssmDimens.Space16
                }
            ) {
                // En-tete : acces a tous les modes, titre/puces du mode actif, outils du mode.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = OssmDimens.Space8),
                    horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PatternOpenerButton(onClick = { patternPickerOpen = true })
                    // Le nom du mode actif s'affiche a cote du bouton ; aucune rangee a faire defiler.
                    // Un tap sur le nom ouvre le meme selecteur que le bouton.
                    Text(
                        controlHeaderTitle(activePattern),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = OssmDimens.TouchTarget)
                            .clickable(role = Role.Button) { patternPickerOpen = true }
                            .wrapContentHeight(Alignment.CenterVertically)
                            .padding(horizontal = OssmDimens.Space4)
                            .testTag("control_pattern_title")
                    )
                    if (compactLandscape) {
                        if (statusKind != null) {
                            ControlStatusPill(kind = statusKind, machineState = machineState)
                        } else if (!liveBareScreen) {
                            ControlReadyPill()
                        }
                    }
                    if (activePattern != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
                            if (liveControlActive) {
                                LiveLimitButton(
                                    side = LiveLimitSide.UPPER,
                                    limits = liveLimits,
                                    enabled = connected && uiState.streamingReady
                                )
                                LiveLimitButton(
                                    side = LiveLimitSide.LOWER,
                                    limits = liveLimits,
                                    enabled = connected && uiState.streamingReady
                                )
                                LiveRecordButton(
                                    state = uiState.liveRecordingState,
                                    enabled = connected && uiState.streamingReady,
                                    onClick = onLiveRecordToggle
                                )
                            }
                            if (activePattern.isVoicePattern()) {
                                VoiceInfoButton()
                            }
                            SettingsGearButton(onInfoClick = { showInfoDialog = true })
                            if (connected && actionVisibility.showSave) {
                                ControlIconButton(
                                    onClick = { showSaveDialog = true },
                                    contentDescription = stringResource(R.string.ctl_save),
                                    icon = Icons.Default.Save,
                                    tag = "control_save"
                                )
                            }
                        }
                    }
                }

                if (remoteSession.active) {
                    RemoteSessionBanner(
                        view = remoteSession,
                        patternName = remoteSession.remotePatternKey?.let { key ->
                            uiState.availablePatterns.firstOrNull { it.key == key }?.name ?: key
                        },
                        compact = compactLandscape
                    )
                }

                // Live and the range wizard own their gestures, so only the ordinary settings
                // body may scroll. STOP stays outside this card and always visible.
                val scrollBody = !liveControlActive && uiState.rangeWizard == null
                // Slider patterns spread their controls over the full card height.
                val spreadControls = scrollBody && activePattern != null &&
                    (activePattern.mode == PatternControlMode.STROKE_ENGINE ||
                        activePattern.mode == PatternControlMode.PROGRESSIVE)
                BoxWithConstraints(
                    modifier = if (liveControlActive || scrollBody) {
                        Modifier.weight(1f).fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth()
                    }
                ) {
                val bodyViewport = maxHeight
                val bodyBounded = constraints.hasBoundedHeight
                // Slider modes are sized to the card and do not scroll when they fit. Short cards
                // (landscape) put each slider on one line so all of them stay visible and the
                // tracks get nearly the whole card width. Si le contenu depasse malgre tout
                // (petit ecran portrait, police agrandie, cases empilees), la colonne defile
                // en secours plutot que de rogner une commande.
                val spread = spreadControls && bodyBounded
                val sliderMetrics = when {
                    !spread -> RegularControlSliderMetrics
                    compactLandscape -> InlineControlSliderMetrics
                    bodyViewport >= LargeBodyHeight -> LargeControlSliderMetrics
                    else -> RegularControlSliderMetrics
                }
                CompositionLocalProvider(LocalControlSliderMetrics provides sliderMetrics) {
                Column(
                    modifier = when {
                        liveControlActive -> Modifier.fillMaxSize()
                        // Hauteur minimale = le corps (curseurs repartis) ; au-dela, defilement de secours.
                        spread -> Modifier
                            .fillMaxSize()
                            .verticalScroll(settingsScrollState)
                        scrollBody -> Modifier
                            .fillMaxWidth()
                            .verticalScroll(settingsScrollState)
                        else -> Modifier.fillMaxWidth()
                    },
                    verticalArrangement = if (spread) Arrangement.SpaceEvenly else Arrangement.Top
                ) {
                // SAFETY: entering or leaving a remote session recreates the controls, which
                // cancels any drag/hold in progress WITHOUT committing it. A gesture started on
                // the envelope maximum can therefore never be committed as a direct motor
                // command once the session has ended.
                key(remoteSession.active) {
                when {
                    uiState.rangeWizard != null -> {
                        val wiz = uiState.rangeWizard
                        Text(
                            if (wiz.step == 1)
                                stringResource(R.string.ctl_wizard_step1)
                            else
                                stringResource(R.string.ctl_wizard_step2),
                            color = OssmOnSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = OssmDimens.Space12)
                        )
                        if (!uiState.streamingReady) {
                            OssmStatusLine(
                                text = buildString {
                                    append(stringResource(R.string.ctl_wizard_preparing, machineState.state.toString()))
                                    if (machineState.isPreflight) {
                                        append("\n" + stringResource(R.string.ctl_knob_to_zero))
                                    }
                                },
                                tone = OssmTone.Warn,
                                modifier = Modifier.padding(bottom = OssmDimens.Space12)
                            )
                        }
                        StreamPositionSlider(
                            positionPercent = uiState.streamPositionPercent,
                            enabled = slidersEnabled && uiState.streamingReady,
                            onPositionDrag = onStreamDrag,
                            // Range calibration is a stop-only workflow; the phone-owned
                            // slow return belongs exclusively to the Live radial pad.
                            onTouchEnd = onStreamTouchCancel,
                            onTouchCancel = onStreamTouchCancel,
                            gestureCancellationKey = uiState.liveGestureEpoch,
                            compact = compactLandscape,
                            fillAvailable = false
                        )
                        Spacer(Modifier.height(OssmDimens.Space12))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
                        ) {
                            OssmButton(
                                text = if (wiz.step == 1) {
                                    stringResource(R.string.ctl_wizard_set_depth)
                                } else {
                                    stringResource(R.string.ctl_wizard_set_retract)
                                },
                                onClick = onRangeWizardCapture,
                                modifier = Modifier.weight(1f),
                                kind = OssmButtonKind.Primary,
                                enabled = slidersEnabled && uiState.streamingReady
                            )
                            OssmButton(
                                text = stringResource(R.string.ctl_cancel),
                                onClick = onRangeWizardCancel,
                                modifier = Modifier.weight(1f),
                                kind = OssmButtonKind.Secondary,
                                enabled = connected
                            )
                        }
                        if (wiz.step == 2 && wiz.capturedMax != null) {
                            Spacer(Modifier.height(OssmDimens.Space8))
                            OssmStatusLine(
                                text = stringResource(R.string.ctl_wizard_depth_set, (wiz.capturedMax * 100).toInt()),
                                tone = OssmTone.Ok
                            )
                        }
                    }
                    activePattern == null -> {
                        Text(
                            stringResource(R.string.ctl_select_pattern_to_start),
                            color = OssmOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    activePattern.mode == PatternControlMode.LAUNCH_ONLY -> {
                        Text(
                            stringResource(R.string.ctl_launch_only_hint),
                            color = OssmOnSurfaceMuted,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    activePattern.isVoicePattern() -> {
                        VoiceControlPanel(
                            uiState = uiState,
                            connected = connected,
                            slidersEnabled = slidersEnabled,
                            actions = voiceActions,
                            voiceAi = voiceAi,
                            modelRoot = voiceModelRoot,
                        )
                    }
                    activePattern.mode == PatternControlMode.STREAMING -> {
                        if (!uiState.streamingReady) {
                            OssmStatusLine(
                                text = buildString {
                                    append(stringResource(R.string.ctl_live_preparing))
                                    append("\n" + stringResource(R.string.ctl_machine_state, machineState.state.toString()))
                                    machineState.stroke?.let { append("  stroke=$it") }
                                    machineState.depth?.let { append("  depth=$it") }
                                    machineState.speed?.let { append("  speed=$it") }
                                    if (machineState.isPreflight) {
                                        append("\n" + stringResource(R.string.ctl_knob_to_zero_mode))
                                    }
                                },
                                tone = OssmTone.Warn,
                                modifier = Modifier.padding(bottom = OssmDimens.Space12)
                            )
                        }
                        if (remoteSession.active) {
                            // In a session this slider is the envelope's speed maximum (as in the
                            // other modes); without it a maximum of 0 left Live unusable.
                            ControlSlider(
                                label = stringResource(R.string.ctl_speed),
                                value = speedDraft,
                                onValueChange = {
                                    speedDraft = it
                                    draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                    dispatchSpeedLive(it)
                                },
                                onValueCommit = { committed ->
                                    speedDraft = committed
                                    draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                    dispatchSpeedCommit(committed)
                                },
                                enabled = slidersEnabled,
                                activeColor = OssmPrimary,
                                gestureCancellationKey = uiState.liveGestureEpoch,
                            )
                            Spacer(Modifier.height(6.dp))
                            // The two limits (low / high) of the window the browser's pad moves in.
                            DepthRangeEditor(
                                minValue = depthRangeDraft.start,
                                maxValue = depthRangeDraft.endInclusive,
                                enabled = slidersEnabled,
                                onRangeChange = { min, max ->
                                    depthRangeDraft = min..max
                                    draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                    onRemoteDepthRangeChange(min, max)
                                },
                                onRangeCommit = { min, max ->
                                    depthRangeDraft = min..max
                                    draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                    onRemoteDepthRangeChange(min, max)
                                },
                                gestureCancellationKey = uiState.liveGestureEpoch,
                                labelOverride = stringResource(R.string.ctl_remote_limits),
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        LiveAnchorPad(
                            positionPercent = uiState.streamPositionPercent,
                            enabled = slidersEnabled && uiState.streamingReady,
                            onPositionDrag = onStreamDrag,
                            onTouchEnd = onStreamTouchEnd,
                            onTouchCancel = onStreamTouchCancel,
                            gestureCancellationKey = uiState.liveGestureEpoch,
                            limits = liveLimits,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            compact = compactLandscape,
                            fillAvailable = true
                        )
                    }
                    activePattern.mode == PatternControlMode.PROGRESSIVE -> {
                        ProgressiveSpeedBar(
                            currentValue = uiState.speed,
                            maxValue = if (remoteSession.active) remoteSession.speedMax else uiState.progressiveMaxSpeed,
                            enabled = slidersEnabled,
                            onMaxChange = onProgressiveMaxCommit,
                            limited = remoteSession.active
                        )
                        Spacer(Modifier.height(OssmDimens.Space12))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
                            limited = remoteSession.active,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                onDepthRangeCommit(min, max)
                            }
                        )
                        Spacer(Modifier.height(OssmDimens.Space8))
                        CenteredSensationSlider(
                            value = sensationDraft,
                            enabled = slidersEnabled,
                            patternKey = activePattern?.key,
                            remoteSession = remoteSession,
                            gestureCancellationKey = uiState.liveGestureEpoch,
                            onValueChange = { value ->
                                sensationDraft = value
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                dispatchSensationLive(value)
                            },
                            onValueCommit = { value ->
                                sensationDraft = value
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                dispatchSensationCommit(value)
                            }
                        )
                        Spacer(Modifier.height(OssmDimens.Space8))
                        // Toute la rangee est cliquable (et plus seulement la case).
                        OssmSwitchRow(
                            label = stringResource(R.string.ctl_chaos_title),
                            checked = uiState.chaosAtMax,
                            onCheckedChange = { onChaosToggle(it) },
                            supporting = stringResource(R.string.ctl_chaos_hint),
                            enabled = connected
                        )
                    }
                    activePattern.mode == PatternControlMode.NATURAL -> {
                        NaturalControlPanel(
                            uiState = uiState,
                            remoteSession = remoteSession,
                            connected = connected,
                            slidersEnabled = slidersEnabled,
                            actions = naturalActions,
                            depthEditor = {
                                DepthRangeEditor(
                                    minValue = depthRangeDraft.start,
                                    maxValue = depthRangeDraft.endInclusive,
                                    enabled = slidersEnabled,
                                    positionFraction = positionFraction,
                                    randomMinValue = uiState.naturalDepthMinCurrent,
                                    randomMaxValue = uiState.naturalDepthMaxCurrent,
                                    limited = remoteSession.active,
                                    onRangeChange = { min, max ->
                                        depthRangeDraft = min..max
                                        draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                        onDepthLive(min, max)
                                    },
                                    onRangeCommit = { min, max ->
                                        depthRangeDraft = min..max
                                        draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                        onDepthRangeCommit(min, max)
                                    }
                                )
                            }
                        )
                    }
                    activePattern.mode == PatternControlMode.AUTO_RANDOM -> {
                        Text(
                            stringResource(R.string.ctl_auto_intro),
                            color = OssmOnSurfaceMuted,
                            style = OssmCaption,
                            modifier = Modifier.padding(bottom = OssmDimens.Space12)
                        )
                        SectionLabel(stringResource(R.string.ctl_auto_modes_header))
                        Spacer(Modifier.height(OssmDimens.Space8))
                        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
                            AutoRandomMixablePatterns.forEach { pattern ->
                                AutoRandomPatternRow(
                                    label = pattern.name,
                                    checked = pattern.key in uiState.autoSelectedKeys,
                                    onCheckedChange = { onAutoToggleSelected(pattern.key) }
                                )
                            }
                        }
                        Spacer(Modifier.height(OssmDimens.Space16))
                        SectionLabel(stringResource(R.string.ctl_auto_speed_header))
                        Spacer(Modifier.height(OssmDimens.Space8))
                        ControlSlider(
                            label = stringResource(R.string.ctl_auto_speed_ceiling),
                            value = if (remoteSession.active) remoteSession.speedMax else uiState.autoMaxSpeed,
                            onValueChange = onAutoMaxSpeedChange,
                            onValueCommit = onAutoMaxSpeedChange,
                            enabled = slidersEnabled,
                            activeColor = OssmPrimary,
                            gestureCancellationKey = uiState.liveGestureEpoch,
                            limited = remoteSession.active
                        )
                        Spacer(Modifier.height(OssmDimens.Space12))
                        ControlSlider(
                            label = stringResource(R.string.ctl_auto_intensity_cap),
                            value = uiState.autoIntensityCap,
                            onValueChange = onAutoIntensityCapChange,
                            onValueCommit = onAutoIntensityCapChange,
                            enabled = slidersEnabled,
                            activeColor = OssmWarning,
                            gestureCancellationKey = uiState.liveGestureEpoch
                        )
                        Spacer(Modifier.height(OssmDimens.Space12))
                        ControlSlider(
                            label = stringResource(R.string.ctl_auto_randomness),
                            value = uiState.autoRandomness,
                            onValueChange = onAutoRandomnessChange,
                            onValueCommit = onAutoRandomnessChange,
                            enabled = slidersEnabled,
                            activeColor = OssmSensation,
                            gestureCancellationKey = uiState.liveGestureEpoch
                        )
                        Spacer(Modifier.height(OssmDimens.Space12))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
                            limited = remoteSession.active,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                onDepthRangeCommit(min, max)
                            }
                        )
                        Spacer(Modifier.height(OssmDimens.Space12))
                        if (uiState.autoRunning) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                tint = OssmConnected
                            ) {
                                Text(
                                    stringResource(R.string.ctl_auto_intensity_value, (uiState.autoIntensity * 100).toInt()),
                                    color = OssmConnected,
                                    style = OssmDisplayValue
                                )
                                Spacer(Modifier.height(OssmDimens.Space4))
                                Text(
                                    stringResource(R.string.ctl_auto_running_hint),
                                    color = OssmOnSurfaceMuted,
                                    style = OssmCaption
                                )
                            }
                        } else {
                            ControlSlider(
                                label = stringResource(R.string.ctl_auto_initial_intensity),
                                value = uiState.autoInitialIntensity,
                                onValueChange = onAutoInitialIntensityChange,
                                onValueCommit = onAutoInitialIntensityChange,
                                enabled = slidersEnabled,
                                activeColor = OssmPrimary,
                                gestureCancellationKey = uiState.liveGestureEpoch
                            )
                        }
                        Spacer(Modifier.height(OssmDimens.Space12))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
                        ) {
                            OssmButton(
                                text = stringResource(R.string.ctlrd_start),
                                onClick = onAutoRequestStart,
                                modifier = Modifier.weight(1f),
                                kind = OssmButtonKind.Primary,
                                enabled = slidersEnabled && !uiState.autoRunning && uiState.autoSelectedKeys.isNotEmpty()
                            )
                            OssmButton(
                                text = stringResource(R.string.ctlrd_stop_mix),
                                onClick = onAutoStop,
                                modifier = Modifier.weight(1f),
                                kind = OssmButtonKind.Secondary,
                                enabled = connected && uiState.autoRunning
                            )
                        }
                    }
                    else -> {
                        // Simple Stroke et Teasing & Pounding exposent le mode aléatoire /
                        // piston : une case « Aléatoire » collée sous chaque curseur.
                        val isRandomCapable = activePattern.key == "teasingPounding" ||
                            activePattern.key == "simpleStroke"

                        ControlSlider(
                            label = stringResource(R.string.ctl_speed),
                            value = speedDraft,
                            onValueChange = {
                                speedDraft = it
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                dispatchSpeedLive(it)
                            },
                            onValueCommit = { committed ->
                                speedDraft = committed
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                dispatchSpeedCommit(committed)
                            },
                            enabled = slidersEnabled,
                            activeColor = OssmPrimary,
                            gestureCancellationKey = uiState.liveGestureEpoch,
                            currentMarker = uiState.randomSpeedCurrent,
                            displayValue = "${((uiState.randomSpeedCurrent ?: speedDraft) * 100).toInt()}%",
                            displayValueColor = if (uiState.randomSpeedCurrent != null) OssmError else null,
                            limited = remoteSession.active,
                            headerAccessory = if (isRandomCapable) {
                                {
                                    RandomToggle(
                                        label = stringResource(R.string.ctl_random_speed),
                                        checked = uiState.randSpeed,
                                        enabled = slidersEnabled,
                                        onCheckedChange = { onRandomToggle(RandomTarget.SPEED, it) },
                                        visibleLabel = stringResource(R.string.ui2_random_short)
                                    )
                                }
                            } else null
                        )
                        Spacer(Modifier.height(OssmDimens.Space4))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
                            limited = remoteSession.active,
                            headerAccessory = if (isRandomCapable) {
                                {
                                    RandomToggle(
                                        label = stringResource(R.string.ctl_random_depth),
                                        checked = uiState.randDepthMin || uiState.randDepthMax,
                                        enabled = slidersEnabled,
                                        onCheckedChange = { checked -> onDepthRandomToggle(checked) },
                                        visibleLabel = stringResource(R.string.ui2_random_short)
                                    )
                                }
                            } else null,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
                                draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                onDepthRangeCommit(min, max)
                            }
                        )
                        if (shouldShowSensationSlider(activePattern)) {
                            Spacer(Modifier.height(OssmDimens.Space4))
                            CenteredSensationSlider(
                                value = sensationDraft,
                                enabled = slidersEnabled,
                                patternKey = activePattern.key,
                                remoteSession = remoteSession,
                                gestureCancellationKey = uiState.liveGestureEpoch,
                                randomCurrent = uiState.randomSensationCurrent,
                                randomRange = if (isRandomCapable && uiState.sensationTurnActive) {
                                    uiState.randomSensationMin..uiState.randomSensationMax
                                } else null,
                                onRandomRangeChange = onSensationRandomRangeChange,
                                headerAccessory = if (isRandomCapable) {
                                    {
                                        // En paysage les deux cases s'empilent : la colonne du libelle est etroite.
                                        ToggleGroup {
                                            RandomToggle(
                                                label = stringResource(R.string.ctl_random_sensation),
                                                checked = uiState.randSensation,
                                                enabled = slidersEnabled,
                                                onCheckedChange = { onRandomToggle(RandomTarget.SENSATION, it) },
                                                visibleLabel = stringResource(R.string.ui2_random_short)
                                            )
                                            if (shouldShowMaxSensationToggle(activePattern)) {
                                                RandomToggle(
                                                    label = stringResource(R.string.ctl_max_sensation),
                                                    checked = uiState.maxSensation,
                                                    enabled = slidersEnabled,
                                                    onCheckedChange = { onRandomToggle(RandomTarget.SENSATION_MAX, it) },
                                                    visibleLabel = stringResource(R.string.ui2_max_short)
                                                )
                                            }
                                        }
                                    }
                                } else null,
                                onValueChange = { value ->
                                    sensationDraft = value
                                    draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                    dispatchSensationLive(value)
                                },
                                onValueCommit = { value ->
                                    sensationDraft = value
                                    draftEditClock.markLocalEdit(android.os.SystemClock.uptimeMillis())
                                    dispatchSensationCommit(value)
                                }
                            )
                        }
                    }
                }
                } // key(remoteSession.active)
                } // settings body column
                } // slider metrics
                } // settings body viewport
            }

        }
        }

        if (patternPickerOpen) {
            PatternPickerOverlay(
                uiState = uiState,
                railVisible = showActionRail,
                onPattern = { key ->
                    onPattern(key)
                    patternPickerOpen = false
                },
                onReorder = { showReorderDialog = true },
                onDismiss = { patternPickerOpen = false },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    if (showReorderDialog) {
        ReorderPatternsDialog(
            patternNames = uiState.orderedPatterns.map { it.key to it.name },
            onSave = { keys ->
                onPatternOrderSave(keys)
                showReorderDialog = false
            },
            onDismiss = { showReorderDialog = false }
        )
    }

    if (showSaveDialog && activePattern != null && actionVisibility.showSave) {
        SavePresetDialog(
            currentState = uiState,
            onDismiss = { showSaveDialog = false },
            onSave = { preset ->
                onSavePreset(preset)
                showSaveDialog = false
            }
        )
    }

    uiState.pendingManualChange?.let {
        AbruptChangeDialog(
            control = it.control,
            thresholdPercent = it.thresholdPercent,
            onConfirm = onConfirmPendingChange,
            onDismiss = {
                // The guarded value was never applied (state/machine stayed at baseline) — snap
                // the local drag drafts back so the slider thumb doesn't stay stuck mid-air.
                speedDraft = uiState.speed
                depthRangeDraft = uiState.depthMin..uiState.depthMax
                onDismissPendingChange()
            }
        )
    }

    if (showInfoDialog) {
        ControlSettingsDialog(
            speedChecked = uiState.speedGuard.enabled,
            onSpeedCheckedChange = onSpeedGuardEnabledChange,
            depthChecked = uiState.depthGuard.enabled,
            onDepthCheckedChange = onDepthGuardEnabledChange,
            depthLimitMin = uiState.depthLimitMin,
            depthLimitMax = uiState.depthLimitMax,
            onDepthLimitsChange = onDepthLimitsChange,
            precisionChecked = precisionEnabled,
            onPrecisionCheckedChange = { enabled ->
                activePattern?.let { onPrecisionEnabledChange(it.key, enabled) }
            },
            showLiveInvert = shouldShowLiveInvertSetting(activePattern),
            liveInvertChecked = uiState.liveInvert,
            onLiveInvertCheckedChange = onLiveInvertToggle,
            showLiveAcceleration = shouldShowLiveAccelerationSetting(activePattern),
            liveAccelerationPercent = uiState.liveAccelerationPercent,
            onLiveAccelerationChange = onLiveAccelerationChange,
            resistanceDepth = resistanceDepth.takeIf { activePattern?.mode == PatternControlMode.STROKE_ENGINE },
            gestureCancellationKey = uiState.liveGestureEpoch,
            onDismiss = { showInfoDialog = false }
        )
    }

    if (uiState.pendingAutoStart) {
        AutoRandomStartDialog(
            selectedCount = uiState.autoSelectedKeys.size,
            maxSpeedPercent = (uiState.autoMaxSpeed * 100).toInt(),
            depthMinPercent = (uiState.depthMin * 100).toInt(),
            depthMaxPercent = (uiState.depthMax * 100).toInt(),
            onConfirm = onAutoConfirmStart,
            onDismiss = onAutoCancelStart
        )
    }

    editingRandomPreset?.let { edit ->
        val preset = when (edit.target) {
            RandomPresetTarget.SPEED -> uiState.randomSpeedPresets.getOrNull(edit.index)
            RandomPresetTarget.DEPTH -> uiState.randomDepthPresets.getOrNull(edit.index)
        }
        if (preset != null) {
            RandomRangePresetDialog(
                target = edit.target,
                index = edit.index,
                preset = preset,
                currentSpeedPercent = (uiState.speed * 100f).roundToInt(),
                onSave = { min, max, saveSpeedToo ->
                    onRandomPresetEdit(edit.target, edit.index, min, max, saveSpeedToo)
                    editingRandomPreset = null
                },
                onDismiss = { editingRandomPreset = null }
            )
        }
    }
}

/**
 * Gabarit de l'ecran de controle. Portrait : contenu (statut + carte) en haut, rail STOP /
 * pause / verrou de rotation fixe en bas, a portee du pouce. Paysage : rail en colonne a
 * gauche. Le STOP est lu en premier par les lecteurs d'ecran quelle que soit sa place.
 */
@Composable
internal fun CompactControlScaffold(
    modifier: Modifier = Modifier,
    showActionRail: Boolean = true,
    bare: Boolean = false,
    actionRail: @Composable (landscape: Boolean) -> Unit,
    content: @Composable ColumnScope.(landscape: Boolean) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("control_main_surface")
    ) {
        val landscape = maxWidth > maxHeight
        if (landscape && showActionRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = OssmDimens.Space4,
                        end = OssmDimens.Space8,
                        top = OssmDimens.Space4,
                        bottom = OssmDimens.Space4
                    ),
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                // Rail just as wide as the STOP, so the card starts right after it.
                Box(
                    modifier = Modifier
                        .width(OssmDimens.StopHeight + OssmDimens.Space8)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    actionRail(true)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8),
                    content = { content(true) }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (bare) OssmDimens.Space4 else if (landscape) OssmDimens.Space8 else OssmDimens.ScreenGutter,
                        vertical = if (bare) OssmDimens.Space4 else OssmDimens.Space8
                    )
                    .semantics { isTraversalGroup = true },
                verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                content(landscape)
                if (showActionRail) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ActionRailHeight)
                            // Place en bas mais lu en premier (STOP atteignable d'emblee).
                            .semantics {
                                isTraversalGroup = true
                                traversalIndex = -1f
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        actionRail(false)
                    }
                }
            }
        }
    }
}

// Rail portrait = barre STOP (64 dp) + pause + verrou de rotation sur la meme ligne.
private val ActionRailHeight = OssmDimens.StopHeight

// Corps d'un mode a curseurs : a partir de cette hauteur, les curseurs passent en grande taille.
private val LargeBodyHeight = 560.dp

private val ControlIconSize = 24.dp
private val StatusIconSize = 20.dp
private val StatusPillMaxWidth = 200.dp
private val ActiveBorderWidth = 2.dp
private val ChipCheckSize = 18.dp
private val ToggleBoxWidth = 40.dp

/** Hauteur d'une rangee de reglage (case, interrupteur, ligne de dialogue). */
private val SettingsRowMinHeight = 56.dp

internal enum class ControlStatusKind { USB, DISCONNECTED, CALIBRATING, ERROR }

internal fun controlStatusKind(
    usbControlActive: Boolean,
    connected: Boolean,
    machineBusy: Boolean,
    machineError: Boolean
): ControlStatusKind? = when {
    usbControlActive -> ControlStatusKind.USB
    !connected -> ControlStatusKind.DISCONNECTED
    machineBusy -> ControlStatusKind.CALIBRATING
    machineError -> ControlStatusKind.ERROR
    else -> null
}

/** Couleur du statut : vert = lien actif, ambre = prudence (commandes bloquees), rouge = erreur. */
private fun controlStatusTint(kind: ControlStatusKind): Color = when (kind) {
    ControlStatusKind.USB -> OssmConnected
    ControlStatusKind.DISCONNECTED -> OssmWarning
    ControlStatusKind.CALIBRATING -> OssmWarning
    ControlStatusKind.ERROR -> OssmError
}

/** Chaque statut a aussi une icone et un texte : la couleur ne porte jamais l'information seule. */
private fun controlStatusIcon(kind: ControlStatusKind): ImageVector = when (kind) {
    ControlStatusKind.USB -> Icons.Default.Usb
    ControlStatusKind.DISCONNECTED -> Icons.Default.BluetoothDisabled
    ControlStatusKind.CALIBRATING -> Icons.Default.Info
    ControlStatusKind.ERROR -> Icons.Default.Warning
}

@Composable
private fun controlStatusText(
    kind: ControlStatusKind,
    machineState: MachineState,
    withHint: Boolean
): String = when (kind) {
    ControlStatusKind.DISCONNECTED ->
        if (withHint) {
            stringResource(R.string.ui2_status_disconnected_title) + " · " +
                stringResource(R.string.ui2_status_disconnected_hint)
        } else {
            stringResource(R.string.ui2_status_disconnected_title)
        }
    ControlStatusKind.USB -> stringResource(R.string.ctl_usb_active)
    ControlStatusKind.CALIBRATING -> stringResource(R.string.ui2_status_calibrating, machineState.displayLabel)
    ControlStatusKind.ERROR -> machineState.displayLabel
}

/** Bandeau d'etat d'une ligne (portrait) : icone + texte sur fond de controle. */
@Composable
private fun ControlStatusBanner(
    icon: ImageVector,
    tint: Color,
    text: String,
    tag: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, tint.copy(alpha = 0.6f), shape)
            .padding(horizontal = OssmDimens.Space12, vertical = OssmDimens.Space8)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(StatusIconSize))
        Text(
            text,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag(tag)
        )
    }
}

/** Pastille d'etat d'une ligne (paysage) : ne prend aucune hauteur propre dans la carte. */
@Composable
private fun StatusPill(
    icon: ImageVector,
    tint: Color,
    label: String,
    tag: String
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .widthIn(max = StatusPillMaxWidth)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, tint.copy(alpha = 0.6f), shape)
            .padding(horizontal = OssmDimens.Space12, vertical = OssmDimens.Space8)
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(StatusIconSize))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Statut anormal (USB, deconnecte, calibration, erreur), ligne du haut en portrait. */
@Composable
private fun ControlStatusLine(
    kind: ControlStatusKind,
    machineState: MachineState,
    modifier: Modifier = Modifier
) {
    ControlStatusBanner(
        icon = controlStatusIcon(kind),
        tint = controlStatusTint(kind),
        text = controlStatusText(kind, machineState, withHint = true),
        tag = "control_status_line",
        modifier = modifier
    )
}

/** Statut anormal en paysage : pastille dans l'en-tete de la carte. */
@Composable
private fun ControlStatusPill(
    kind: ControlStatusKind,
    machineState: MachineState
) {
    StatusPill(
        icon = controlStatusIcon(kind),
        tint = controlStatusTint(kind),
        label = controlStatusText(kind, machineState, withHint = false),
        tag = "control_status_pill"
    )
}

/** Machine connectee en Bluetooth et prete : on l'affiche aussi, pour que l'etat soit toujours lisible. */
@Composable
private fun ControlReadyLine(machineState: MachineState) {
    ControlStatusBanner(
        icon = Icons.Default.Bluetooth,
        tint = OssmConnected,
        text = stringResource(R.string.ctlrd_status_ready, machineState.displayLabel),
        tag = "control_status_ready"
    )
}

@Composable
private fun ControlReadyPill() {
    StatusPill(
        icon = Icons.Default.Bluetooth,
        tint = OssmConnected,
        label = stringResource(R.string.ctlrd_status_ready_short),
        tag = "control_status_ready"
    )
}

@Composable
private fun CompactActionRail(
    landscape: Boolean,
    showStop: Boolean,
    showPause: Boolean,
    showRotation: Boolean,
    isPaused: Boolean,
    pauseEnabled: Boolean,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    // Same controls in both orientations: the STOP bar, pause/play and the rotation lock.
    // Landscape stands them up in a narrow column (STOP written top to bottom).
    if (landscape) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showStop) {
                StopBar(
                    onClick = onStop,
                    vertical = true,
                    modifier = Modifier
                        .width(OssmDimens.StopHeight)
                        .weight(1f)
                        .testTag("control_stop")
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (showPause) {
                PausePlayButton(
                    isPaused = isPaused,
                    enabled = pauseEnabled,
                    onPause = onPause,
                    onResume = onResume,
                    size = OssmDimens.StopHeight
                )
            }
            if (showRotation) {
                RotationLockButton(width = OssmDimens.StopHeight, height = OssmDimens.TouchTarget)
            }
        }
    } else {
        // One line: wide STOP bar (64 dp, never smaller), pause/play, rotation lock, with at
        // least 12 dp between the STOP and its neighbours to avoid a mis-tap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showStop) {
                StopBar(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(OssmDimens.StopHeight)
                        .testTag("control_stop")
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (showPause) {
                PausePlayButton(
                    isPaused = isPaused,
                    enabled = pauseEnabled,
                    onPause = onPause,
                    onResume = onResume,
                    size = OssmDimens.StopHeight
                )
            }
            if (showRotation) {
                RotationLockButton(width = OssmDimens.TouchTarget, height = OssmDimens.StopHeight)
            }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Locks the screen in its current orientation inside the app only. The phone's own
 * auto-rotate setting is never touched: outside the app it keeps working as before, and
 * unlocking hands the orientation back to the phone.
 */
@Composable
private fun RotationLockButton(width: Dp, height: Dp) {
    val activity = LocalContext.current.findActivity()
    var locked by remember {
        mutableStateOf(
            activity?.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        )
    }
    val description = stringResource(
        if (locked) R.string.ctl_rotation_unlock else R.string.ctl_rotation_lock
    )
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = width, height = height)
            .testTag("control_rotation_lock")
            .clip(shape)
            .background(if (locked) OssmSurfaceHigh else OssmSurfaceRaised)
            // Verrouille : contour turquoise epais ET icone differente (jamais la couleur seule).
            .border(
                if (locked) ActiveBorderWidth else OssmDimens.BorderWidth,
                if (locked) OssmPrimary else OssmOutline,
                shape
            )
            .toggleable(value = locked, role = Role.Switch) { lock ->
                activity?.requestedOrientation = if (lock) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                locked = lock
            }
            .semantics { contentDescription = description }
    ) {
        Icon(
            imageVector = if (locked) Icons.Default.ScreenLockRotation else Icons.Default.ScreenRotation,
            contentDescription = null,
            tint = if (locked) OssmPrimary else OssmOnSurfaceMuted,
            modifier = Modifier.size(ControlIconSize)
        )
    }
}

/** Bouton-icone carre de 48 dp de l'en-tete de la carte (mode, reglages, enregistrer). */
@Composable
private fun ControlIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = OssmOnSurface,
    tag: String? = null
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .size(OssmDimens.TouchTarget)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmOutline, shape)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ControlIconSize)
        )
    }
}

@Composable
private fun PatternOpenerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ControlIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.ctl_patterns_open),
        icon = Icons.Default.TouchApp,
        modifier = modifier,
        tag = "control_pattern_open"
    )
}

/** Titre de section (majuscules espacees), annonce comme un titre par les lecteurs d'ecran. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = OssmOnSurfaceMuted,
        style = OssmSectionTitle,
        modifier = modifier.semantics { heading() }
    )
}

/**
 * Regroupe les cases d'un en-tete de curseur. En paysage la colonne du libelle ne fait que
 * 118 dp : les cases (48 dp de haut chacune) s'y empilent au lieu de se serrer sur une ligne.
 */
@Composable
private fun ToggleGroup(content: @Composable () -> Unit) {
    if (LocalControlSliderMetrics.current.inlineHeader) {
        Column { content() }
    } else {
        content()
    }
}

/** Puce de mode (grille du selecteur) : cible >= 48 dp, etat actif lu par TalkBack. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PatternChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    large: Boolean = false,
    summary: String? = null
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Row(
        modifier = modifier
            .heightIn(min = OssmDimens.TouchTarget)
            .clip(shape)
            .background(if (isActive) OssmSurfaceHigh else OssmSurfaceRaised)
            .border(
                if (isActive) ActiveBorderWidth else OssmDimens.BorderWidth,
                if (isActive) OssmPrimary else OssmOutline,
                shape
            )
            .combinedClickable(
                role = Role.RadioButton,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics { selected = isActive }
            .padding(horizontal = OssmDimens.Space12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isActive) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = OssmPrimary,
                modifier = Modifier.size(ChipCheckSize)
            )
            Spacer(Modifier.width(OssmDimens.Space8))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = OssmOnSurface,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (summary != null) {
                Text(
                    summary,
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PatternPickerOverlay(
    uiState: ControlUiState,
    railVisible: Boolean,
    onPattern: (String) -> Unit,
    onReorder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // L'overlay ne recouvre jamais le rail STOP : colonne de gauche en paysage, bande du bas en portrait.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (landscape) {
                        if (railVisible) OssmDimens.StopHeight + OssmDimens.Space24 else OssmDimens.Space8
                    } else {
                        OssmDimens.Space12
                    },
                    top = if (landscape) OssmDimens.Space8 else PatternOverlayTop,
                    end = OssmDimens.Space12,
                    bottom = if (!landscape && railVisible) {
                        OssmDimens.StopHeight + OssmDimens.Space16
                    } else {
                        OssmDimens.Space8
                    }
                )
                .clip(RoundedCornerShape(OssmDimens.CardRadius))
                .background(OssmBackground)
        ) {
        GlassCard(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ctlrd_patterns_title),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        stringResource(R.string.ctlrd_patterns_hint),
                        color = OssmOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(OssmDimens.TouchTarget)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.ctl_patterns_collapse),
                        tint = OssmOnSurface
                    )
                }
            }

            Spacer(Modifier.height(OssmDimens.Space12))
            if (uiState.availablePatterns.isEmpty()) {
                Text(
                    stringResource(R.string.ctl_no_patterns),
                    color = OssmOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                val ordered = uiState.orderedPatterns
                val rows = ordered.chunked(2)
                // The buttons share the whole card height (no empty space below them); only
                // when there are too many for the card do they keep a minimum height and scroll.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                val gap = OssmDimens.Space8
                val rowHeight = if (rows.isEmpty()) PatternRowMinHeight else {
                    ((maxHeight - gap * (rows.size - 1)) / rows.size).coerceIn(PatternRowMinHeight, PatternRowMaxHeight)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    rows.forEach { rowPatterns ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap)
                        ) {
                            rowPatterns.forEach { pattern ->
                                PatternChip(
                                    label = pattern.name,
                                    isActive = uiState.activePatternKey == pattern.key,
                                    onClick = { onPattern(pattern.key) },
                                    onLongClick = onReorder,
                                    large = rowHeight >= PatternRowLargeText,
                                    summary = if (rowHeight >= PatternRowSummaryHeight) {
                                        ModeRegistry.of(pattern)?.let { stringResource(it.summaryRes) }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(rowHeight)
                                )
                            }
                            if (rowPatterns.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                } // patterns box
            }
        }
        }
    }
}

// Selecteur de modes : marge haute (sous la ligne d'etat) et hauteurs de cellule.
private val PatternOverlayTop = 56.dp
private val PatternRowMinHeight = 52.dp
private val PatternRowMaxHeight = 120.dp
private val PatternRowLargeText = 72.dp
// Sous cette hauteur la phrase d'explication du mode est masquee plutot que tronquee.
private val PatternRowSummaryHeight = 96.dp

internal fun shouldShowSensationSlider(pattern: OssmPattern): Boolean =
    pattern.mode == PatternControlMode.STROKE_ENGINE && pattern.key != "simpleStroke"

/** The "Max" sensation option exists only for Teasing Pounding. */
internal fun shouldShowMaxSensationToggle(pattern: OssmPattern): Boolean =
    pattern.key == "teasingPounding" && shouldShowSensationSlider(pattern)

internal fun controlHeaderTitle(pattern: OssmPattern?): String = pattern?.name.orEmpty()

internal fun shouldShowLiveInvertSetting(pattern: OssmPattern?): Boolean =
    pattern?.mode == PatternControlMode.STREAMING

internal fun shouldShowLiveAccelerationSetting(pattern: OssmPattern?): Boolean =
    pattern?.mode == PatternControlMode.STREAMING

internal fun shouldShowLiveRecordButton(pattern: OssmPattern?): Boolean =
    pattern?.mode == PatternControlMode.STREAMING

internal data class ControlActionVisibility(
    val showStop: Boolean,
    val showPause: Boolean,
    val showSave: Boolean
)

internal fun controlActionVisibility(pattern: OssmPattern?): ControlActionVisibility {
    val isLive = pattern?.mode == PatternControlMode.STREAMING
    return ControlActionVisibility(
        // Live release now starts a slow phone-owned return; STOP must remain reachable
        // while that return is in flight and must never be hidden by the radial surface.
        showStop = true,
        showPause = !isLive,
        showSave = pattern != null && !isLive
    )
}

internal fun isPrecisionEnabled(
    pattern: OssmPattern,
    overrides: Map<String, Boolean>
): Boolean = overrides[pattern.key] ?: false

/** Sensation 0..1 (0.5 = neutral) on the firmware's signed scale −100…0…+100. */
internal fun signedSensationPercent(value: Float): Int =
    ((value.coerceIn(0f, 1f) - 0.5f) * 200f).roundToInt().coerceIn(-100, 100)

/** "+40", "0", "−20" (true minus sign). */
internal fun signedSensationLabel(value: Float): String {
    val signed = signedSensationPercent(value)
    return when {
        signed > 0 -> "+$signed"
        signed < 0 -> "−${-signed}"
        else -> "0"
    }
}

/** Symmetric intensity d (0..0.5) as "±D" on the −100…+100 scale. */
internal fun sensationIntensityLabel(intensity: Float): String =
    "±${(intensity.coerceIn(0f, 0.5f) * 200f).roundToInt()}"

/**
 * In a session the local slider sets only the intensity d = |s − 0.5|. Keep the side the user
 * dragged to (no jump from 0.2 to 0.8); otherwise show the intensity on the positive side.
 */
internal fun sessionSensationDraft(currentDraft: Float, intensity: Float): Float {
    val d = intensity.coerceIn(0f, 0.5f)
    if (kotlin.math.abs(kotlin.math.abs(currentDraft - 0.5f) - d) < 0.005f) return currentDraft
    return if (currentDraft < 0.5f) 0.5f - d else 0.5f + d
}

/**
 * Legend of the two sensation directions, only where the firmware pattern is known
 * (StrokeEngine pattern.h: TeasingPounding and HalfnHalf, sensation < 0 = out faster,
 * > 0 = in faster). Null for the other modes: no guessing.
 */
/** Banner "Sensation ±D (a–b)" arguments on the −100…0…+100 scale. */
internal fun remoteSensationBannerArgs(view: RemoteSessionView): Array<String> = arrayOf(
    sensationIntensityLabel(view.sensationIntensity),
    signedSensationLabel(view.sensationMin),
    signedSensationLabel(view.sensationMax),
)

internal fun sensationLegendRes(patternKey: String?): Int? = when (patternKey) {
    "teasingPounding", "halfNHalf" -> R.string.ctl_sensation_legend_out_in
    else -> null
}

@Composable
private fun CenteredSensationSlider(
    value: Float,
    enabled: Boolean,
    patternKey: String?,
    remoteSession: RemoteSessionView,
    gestureCancellationKey: Long = 0L,
    randomCurrent: Float? = null,
    headerAccessory: (@Composable RowScope.() -> Unit)? = null,
    /** Bounds of the random sensation (0..1, 0.5 = neutral); non-null = random mode on. */
    randomRange: ClosedFloatingPointRange<Float>? = null,
    onRandomRangeChange: (Float, Float) -> Unit = { _, _ -> },
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit
) {
    val inSession = remoteSession.active
    val metrics = LocalControlSliderMetrics.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (randomRange != null) {
            // Random mode: two bars bound the range the random sensation may use; the red
            // line is the value currently sent to the machine.
            val label = stringResource(R.string.ctl_sensation)
            SliderFrame(
                label = label,
                displayValue = randomCurrent?.let(::signedSensationLabel)
                    ?: "${signedSensationLabel(randomRange.start)} / ${signedSensationLabel(randomRange.endInclusive)}",
                enabled = enabled,
                displayValueColor = randomCurrent?.let(::signedValueColor),
                headerAccessory = headerAccessory
            ) {
                RangeBarControl(
                    start = randomRange.start,
                    end = randomRange.endInclusive,
                    enabled = enabled,
                    activeColor = OssmSensation,
                    label = stringResource(R.string.ctl_random_sensation),
                    onRangeChange = onRandomRangeChange,
                    onRangeCommit = onRandomRangeChange,
                    modifier = Modifier.weight(1f),
                    // White line = sensation currently sent (green/red already code the sign).
                    markers = listOfNotNull(randomCurrent?.let { RangeBarMarker(it, OssmOnSurface) }),
                    snapValue = 0.5f,
                    valueText = ::signedSensationLabel,
                    gestureCancellationKey = gestureCancellationKey,
                    bipolar = BipolarStyle()
                )
            }
        } else ControlSlider(
            label = stringResource(
                if (inSession) R.string.ctl_sensation_intensity_max else R.string.ctl_sensation
            ),
            value = value,
            onValueChange = onValueChange,
            onValueCommit = onValueCommit,
            enabled = enabled,
            activeColor = OssmSensation,
            displayValue = when {
                randomCurrent != null -> signedSensationLabel(randomCurrent)
                inSession -> sensationIntensityLabel(PhoneHostMotionLimits.sensationIntensityOf(value))
                else -> signedSensationLabel(value)
            },
            // Green when positive, red when negative (signed value only, not the session cap).
            displayValueColor = if (inSession) null else signedValueColor(randomCurrent ?: value),
            // In a session, the marker shows the sensation the site is actually using;
            // in random mode it shows the value currently sent to the machine.
            currentMarker = randomCurrent ?: if (inSession) remoteSession.remoteSensation else null,
            headerAccessory = headerAccessory,
            gestureCancellationKey = gestureCancellationKey,
            // Invisible detent on neutral: dragging close to 0 lands exactly on 0.
            snapValue = 0.5f,
            bipolar = BipolarStyle(),
            // En session, le curseur regle le plafond d'intensite : cadenas + « limité ».
            limited = inSession
        )
        if (metrics.showScaleLegends) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OssmDimens.Space4),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.ctlrd_scale_min), color = OssmOnSurfaceMuted, style = OssmCaption)
            Text(stringResource(R.string.ctl_sensation_neutral), color = OssmOnSurfaceMuted, style = OssmCaption)
            Text(stringResource(R.string.ctlrd_scale_max), color = OssmOnSurfaceMuted, style = OssmCaption)
        }
        sensationLegendRes(patternKey)?.let { legend ->
            Text(
                stringResource(legend),
                color = OssmOnSurfaceMuted,
                style = OssmCaption,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        } // scale legends
    }
}

@Composable
private fun LiveLimitButton(
    side: LiveLimitSide,
    limits: LiveLimitsState,
    enabled: Boolean
) {
    val moving = limits.isMoving(side)
    val locked = limits.fractionOf(side) != null && !moving
    val description = stringResource(
        when {
            moving -> if (side == LiveLimitSide.UPPER) R.string.ctl_limit_upper_moving else R.string.ctl_limit_lower_moving
            locked -> if (side == LiveLimitSide.UPPER) R.string.ctl_limit_upper_locked else R.string.ctl_limit_lower_locked
            else -> if (side == LiveLimitSide.UPPER) R.string.ctl_limit_upper_add else R.string.ctl_limit_lower_add
        }
    )
    val tint = when {
        moving -> OssmDepth
        locked -> OssmPrimary
        else -> OssmOnSurface
    }
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    IconButton(
        enabled = enabled,
        onClick = { limits.toggle(side) },
        modifier = Modifier
            .size(OssmDimens.TouchTarget)
            .clip(shape)
            .background(if (moving || locked) OssmSurfaceHigh else OssmSurfaceRaised)
            .border(
                if (moving || locked) ActiveBorderWidth else OssmDimens.BorderWidth,
                if (moving) OssmDepth else if (locked) OssmPrimary else OssmOutline,
                shape
            )
            .semantics { contentDescription = description }
    ) {
        // L'etat se lit aussi par un cadenas : ouvert = la ligne suit le doigt, ferme = verrouillee.
        Box(modifier = Modifier.size(ControlIconSize)) {
            LiveLimitIcon(side = side, color = if (enabled) tint else tint.copy(alpha = 0.3f))
            if (moving || locked) {
                Icon(
                    imageVector = if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (enabled) tint else tint.copy(alpha = 0.3f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(LimitBadgeSize)
                )
            }
        }
    }
}

private val LimitBadgeSize = 12.dp

@Composable
private fun LiveRecordButton(
    state: LiveRecordingState,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val description = stringResource(liveRecordContentDescription(state))
    val active = state != LiveRecordingState.IDLE
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    val glyph = OssmError.copy(
        alpha = if (enabled) {
            if (active) 1f else 0.72f
        } else {
            0.25f
        }
    )
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .size(OssmDimens.TouchTarget)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(
                if (active) ActiveBorderWidth else OssmDimens.BorderWidth,
                if (active) OssmError else OssmOutline,
                shape
            )
            .semantics { contentDescription = description }
    ) {
        // Une forme par etat : point = enregistrer, anneau = pret, gros point = REC,
        // triangle = rejouer, carre = arreter la boucle.
        when (state) {
            LiveRecordingState.RECORDED ->
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = glyph, modifier = Modifier.size(ControlIconSize))
            LiveRecordingState.PLAYING ->
                Icon(Icons.Default.Stop, contentDescription = null, tint = glyph, modifier = Modifier.size(ControlIconSize))
            LiveRecordingState.ARMED ->
                Box(
                    modifier = Modifier
                        .size(RecDotSize)
                        .clip(CircleShape)
                        .border(ActiveBorderWidth, glyph, CircleShape)
                )
            LiveRecordingState.RECORDING ->
                Box(
                    modifier = Modifier
                        .size(RecDotLargeSize)
                        .clip(CircleShape)
                        .background(glyph)
                )
            LiveRecordingState.IDLE ->
                Box(
                    modifier = Modifier
                        .size(RecDotSize)
                        .clip(CircleShape)
                        .background(glyph)
                )
        }
    }
}

private val RecDotSize = 16.dp
private val RecDotLargeSize = 20.dp

private fun liveRecordContentDescription(state: LiveRecordingState): Int = when (state) {
    LiveRecordingState.IDLE -> R.string.ctl_rec_idle
    LiveRecordingState.ARMED -> R.string.ctlrd_rec_armed
    LiveRecordingState.RECORDING -> R.string.ctl_rec_recording
    LiveRecordingState.RECORDED -> R.string.ctl_rec_recorded
    LiveRecordingState.PLAYING -> R.string.ctlrd_rec_playing
}

@Composable
private fun SettingsGearButton(
    onInfoClick: () -> Unit
) {
    ControlIconButton(
        onClick = onInfoClick,
        contentDescription = stringResource(R.string.ctl_control_settings),
        icon = Icons.Default.Settings,
        tag = "control_settings"
    )
}

@Composable
private fun PausePlayButton(
    isPaused: Boolean,
    enabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    size: Dp = OssmDimens.StopHeight
) {
    // Ambre = en marche (on peut mettre en pause), vert = en pause (on peut reprendre) ;
    // la forme de l'icone (pause / lecture) porte aussi l'etat.
    val activeColor = if (isPaused) OssmConnected else OssmWarning
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .testTag("control_pause")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size - OssmDimens.Space8)
                .clip(CircleShape)
                .background(if (enabled) activeColor else OssmSurfaceRaised)
                .border(OssmDimens.BorderWidth, if (enabled) activeColor else OssmOutline, CircleShape)
                .clickable(enabled = enabled, role = Role.Button) {
                    if (isPaused) onResume() else onPause()
                }
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPaused) stringResource(R.string.ctl_resume) else stringResource(R.string.ctl_pause),
                tint = if (enabled) OssmBackground else OssmOnSurfaceMuted,
                modifier = Modifier.size(PauseIconSize)
            )
        }
    }
}

private val PauseIconSize = 32.dp

/** Couleurs communes des interrupteurs des reglages (meme rendu que OssmSwitchRow). */
@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = OssmOnPrimary,
    checkedTrackColor = OssmPrimary,
    uncheckedThumbColor = OssmOnSurfaceMuted,
    uncheckedTrackColor = OssmSurfaceRaised,
    uncheckedBorderColor = OssmOutline
)

@Composable
private fun ControlSettingsDialog(
    speedChecked: Boolean,
    onSpeedCheckedChange: (Boolean) -> Unit,
    depthChecked: Boolean,
    onDepthCheckedChange: (Boolean) -> Unit,
    depthLimitMin: Float,
    depthLimitMax: Float,
    onDepthLimitsChange: (Float, Float) -> Unit,
    precisionChecked: Boolean,
    onPrecisionCheckedChange: (Boolean) -> Unit,
    showLiveInvert: Boolean,
    liveInvertChecked: Boolean,
    onLiveInvertCheckedChange: (Boolean) -> Unit,
    showLiveAcceleration: Boolean,
    liveAccelerationPercent: Int,
    onLiveAccelerationChange: (Int) -> Unit,
    resistanceDepth: ResistanceDepthPanelModel? = null,
    gestureCancellationKey: Long = 0L,
    onDismiss: () -> Unit
) {
    var depthLimits by remember { mutableStateOf(depthLimitMin..depthLimitMax) }
    var accelerationDraft by remember { mutableIntStateOf(liveAccelerationPercent) }
    var heldExplanation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(depthLimitMin, depthLimitMax) {
        depthLimits = depthLimitMin..depthLimitMax
    }
    LaunchedEffect(liveAccelerationPercent) {
        accelerationDraft = liveAccelerationPercent
    }

    val precisionHint = stringResource(R.string.ctl_precision_option_hint)
    val invertHint = stringResource(R.string.ctl_live_invert_hint)
    val accelerationHint = stringResource(R.string.ctl_live_acceleration_hint)
    val speedGuardHint = stringResource(R.string.ctl_speed_guard_hint)
    val depthGuardHint = stringResource(R.string.ctl_depth_guard_hint)
    val depthLimitsHint = stringResource(R.string.ctl_depth_limits_hint)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = OssmDimens.Space16, vertical = OssmDimens.Space24)
                    .widthIn(max = SettingsDialogMaxWidth)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.9f),
                shape = RoundedCornerShape(OssmDimens.SheetRadius),
                color = OssmSurface,
                border = BorderStroke(OssmDimens.BorderWidth, OssmGlassBorder)
            ) {
                Column(modifier = Modifier.padding(OssmDimens.Space16)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ctl_control_settings),
                            color = OssmOnSurface,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() }
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(OssmDimens.TouchTarget)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.ctl_close_settings),
                                tint = OssmOnSurface
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
                        ) {
                            SectionLabel(stringResource(R.string.ctlrd_settings_section_input))
                            SettingsToggleRow(
                                title = stringResource(R.string.ctl_precision_option),
                                explanation = precisionHint,
                                checked = precisionChecked,
                                onCheckedChange = onPrecisionCheckedChange,
                                onExplanationVisibilityChange = { visible ->
                                    heldExplanation = precisionHint.takeIf { visible }
                                }
                            )
                            if (showLiveAcceleration || showLiveInvert) {
                                HorizontalDivider(color = OssmGlassBorder)
                                SectionLabel(stringResource(R.string.ctlrd_settings_section_live))
                            }
                            if (showLiveAcceleration) {
                                SettingLabelWithInfo(
                                    title = stringResource(R.string.ctlrd_live_acceleration_title),
                                    value = "$accelerationDraft%",
                                    explanation = accelerationHint,
                                    onExplanationVisibilityChange = { visible ->
                                        heldExplanation = accelerationHint.takeIf { visible }
                                    }
                                )
                                PrecisionScalarSlider(
                                    valuePercent = (accelerationDraft - 10) / 90f * 100f,
                                    enabled = true,
                                    precisionEnabled = precisionChecked,
                                    activeColor = OssmPrimary,
                                    label = stringResource(
                                        R.string.ctl_live_acceleration_value,
                                        accelerationDraft
                                    ),
                                    onValueChange = { percent ->
                                        accelerationDraft = (10f + percent * 0.9f)
                                            .roundToInt()
                                            .coerceIn(10, 100)
                                    },
                                    onValueCommit = { percent ->
                                        val acceleration = (10f + percent * 0.9f)
                                            .roundToInt()
                                            .coerceIn(10, 100)
                                        accelerationDraft = acceleration
                                        onLiveAccelerationChange(acceleration)
                                    },
                                    gestureCancellationKey = gestureCancellationKey,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = SettingsRowMinHeight)
                                )
                            }
                            if (showLiveInvert) {
                                SettingsToggleRow(
                                    title = stringResource(R.string.ctl_live_invert),
                                    explanation = invertHint,
                                    checked = liveInvertChecked,
                                    onCheckedChange = onLiveInvertCheckedChange,
                                    onExplanationVisibilityChange = { visible ->
                                        heldExplanation = invertHint.takeIf { visible }
                                    }
                                )
                            }
                            HorizontalDivider(color = OssmGlassBorder)
                            SectionLabel(stringResource(R.string.ctlrd_settings_section_guards))
                            Text(
                                text = stringResource(R.string.ctl_guards_intro),
                                color = OssmOnSurfaceMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            SettingsToggleRow(
                                title = stringResource(R.string.ctl_speed),
                                explanation = speedGuardHint,
                                checked = speedChecked,
                                onCheckedChange = onSpeedCheckedChange,
                                onExplanationVisibilityChange = { visible ->
                                    heldExplanation = speedGuardHint.takeIf { visible }
                                }
                            )
                            SettingsToggleRow(
                                title = stringResource(R.string.ctl_depth_guard),
                                explanation = depthGuardHint,
                                checked = depthChecked,
                                onCheckedChange = onDepthCheckedChange,
                                onExplanationVisibilityChange = { visible ->
                                    heldExplanation = depthGuardHint.takeIf { visible }
                                }
                            )
                            SettingLabelWithInfo(
                                title = stringResource(R.string.ctlrd_depth_limits_title),
                                value = "${(depthLimits.start * 100).toInt()}%–${(depthLimits.endInclusive * 100).toInt()}%",
                                explanation = depthLimitsHint,
                                onExplanationVisibilityChange = { visible ->
                                    heldExplanation = depthLimitsHint.takeIf { visible }
                                }
                            )
                            RangeSlider(
                                value = depthLimits,
                                onValueChange = { depthLimits = it },
                                onValueChangeFinished = {
                                    onDepthLimitsChange(depthLimits.start, depthLimits.endInclusive)
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.heightIn(min = OssmDimens.TouchTarget),
                                colors = SliderDefaults.colors(
                                    thumbColor = OssmDepth,
                                    activeTrackColor = OssmDepth,
                                    inactiveTrackColor = OssmSurfaceHigh
                                )
                            )
                            if (resistanceDepth != null && shouldShowResistanceDepthPanel(resistanceDepth.state)) {
                                HorizontalDivider(color = OssmGlassBorder)
                                ResistanceDepthPanel(resistanceDepth)
                            }
                            HorizontalDivider(color = OssmGlassBorder)
                            SectionLabel(stringResource(R.string.ctlrd_settings_section_help))
                            InfoLine(
                                stringResource(R.string.ctl_stop_info_title),
                                stringResource(R.string.ctl_stop_info_hint)
                            )
                            InfoLine(
                                stringResource(R.string.ctl_pause_info_title),
                                stringResource(R.string.ctl_pause_info_hint)
                            )
                        }

                        heldExplanation?.let { explanation ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(horizontal = OssmDimens.Space8, vertical = OssmDimens.Space12),
                                shape = RoundedCornerShape(OssmDimens.ControlRadius),
                                color = OssmSurfaceHigh,
                                border = BorderStroke(OssmDimens.BorderWidth, OssmPrimary)
                            ) {
                                Text(
                                    text = explanation,
                                    color = OssmOnSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(OssmDimens.Space16)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(OssmDimens.Space12))
                    OssmButton(
                        text = stringResource(R.string.ctl_understood),
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                        kind = OssmButtonKind.Primary
                    )
                }
            }
        }
    }
}

private val SettingsDialogMaxWidth = 720.dp

@Composable
private fun SettingsToggleRow(
    title: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onExplanationVisibilityChange: (Boolean) -> Unit
) {
    // 2.3.31 a11y: label + switch form ONE toggleable target (TalkBack reads name + state);
    // the press-and-hold info button stays outside the toggleable area.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsRowMinHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = SettingsRowMinHeight)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = OssmOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = settingsSwitchColors()
            )
        }
        Spacer(Modifier.width(OssmDimens.Space8))
        PressHoldInfo(
            explanation = explanation,
            onVisibilityChange = onExplanationVisibilityChange
        )
    }
}

@Composable
private fun SettingLabelWithInfo(
    title: String,
    explanation: String,
    onExplanationVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = OssmOnSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                color = OssmOnSurface,
                style = OssmValue
            )
            Spacer(Modifier.width(OssmDimens.Space8))
        }
        PressHoldInfo(
            explanation = explanation,
            onVisibilityChange = onExplanationVisibilityChange
        )
    }
}

@Composable
private fun PressHoldInfo(
    explanation: String,
    onVisibilityChange: (Boolean) -> Unit
) {
    // 2.3.31 a11y: TalkBack reads the explanation and can toggle it with a double tap
    // (the touch gesture stays press-and-hold).
    var shownByAccessibility by remember(explanation) { mutableStateOf(false) }
    val holdLabel = stringResource(R.string.ctl_hold_for_info)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(OssmDimens.TouchTarget)
            .clip(CircleShape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmOutline, CircleShape)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = holdLabel
                stateDescription = explanation
                onClick(label = holdLabel) {
                    shownByAccessibility = !shownByAccessibility
                    onVisibilityChange(shownByAccessibility)
                    true
                }
            }
            .pointerInput(explanation) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onVisibilityChange(true)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed ||
                                !shouldKeepHoldInfoVisible(
                                    distancePx = (change.position - down.position).getDistance(),
                                    touchSlopPx = viewConfiguration.touchSlop
                                )
                            ) break
                        }
                    } finally {
                        shownByAccessibility = false
                        onVisibilityChange(false)
                    }
                }
            }
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = OssmPrimaryLight,
            modifier = Modifier.size(ControlIconSize)
        )
    }
}

internal fun shouldKeepHoldInfoVisible(distancePx: Float, touchSlopPx: Float): Boolean =
    distancePx <= touchSlopPx

@Composable
private fun InfoLine(title: String, description: String) {
    Column {
        Text(title, color = OssmOnSurface, style = MaterialTheme.typography.labelLarge)
        Text(description, color = OssmOnSurfaceMuted, style = OssmCaption)
    }
}

private data class RandomPresetEdit(
    val target: RandomPresetTarget,
    val index: Int
)

@Composable
private fun RandomToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    visibleLabel: String = label
) {
    // Compact one-line toggle (>= 48dp, meme en paysage) : the whole row is one checkbox target,
    // so TalkBack reads "label, checkbox, state" instead of an unnamed box next to loose text.
    // The on-screen text may be shorter than [label] (the section header already names
    // speed/depth); TalkBack still gets the full [label].
    val toggleSize = maxOf(LocalControlSliderMetrics.current.toggleMinSize, OssmDimens.TouchTarget)
    Row(
        modifier = Modifier
            .heightIn(min = toggleSize)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = ToggleBoxWidth, minHeight = toggleSize),
            colors = CheckboxDefaults.colors(
                checkedColor = OssmPrimary,
                uncheckedColor = OssmOutline,
                checkmarkColor = OssmOnPrimary,
                disabledCheckedColor = OssmOutline,
                disabledUncheckedColor = OssmGlassBorder
            )
        )
        Text(
            text = visibleLabel,
            color = if (enabled) OssmOnSurface else OssmOnSurfaceMuted,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RandomPresetRow(
    presets: List<RandomRangePreset>,
    selectedIndex: Int,
    enabled: Boolean,
    target: RandomPresetTarget,
    onSave: (RandomPresetTarget, Int) -> Unit,
    onApply: (RandomPresetTarget, Int) -> Unit,
    onLongPress: (Int) -> Unit
) {
    val activePresetLabel = stringResource(R.string.ctl_random_preset_active)
    val inactivePresetLabel = stringResource(R.string.ctl_random_preset_inactive)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = OssmDimens.Space4),
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
    ) {
        presets.forEachIndexed { index, preset ->
            val selected = index == selectedIndex
            val presetDescription = preset.savedSpeedPercent?.let { saved ->
                stringResource(R.string.ui2_preset_cd_saved, preset.minPercent, preset.maxPercent, saved)
            } ?: "${preset.minPercent}%–${preset.maxPercent}%"
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = OssmDimens.TouchTarget)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { onSave(target, index) },
                        onDoubleClick = { onApply(target, index) },
                        onLongClick = { onLongPress(index) }
                    )
                    .semantics {
                        contentDescription = presetDescription
                        stateDescription = if (selected) activePresetLabel else inactivePresetLabel
                        role = Role.Button
                    }
                    .border(
                        width = OssmDimens.BorderWidth,
                        color = if (selected) OssmPrimary else OssmOutline,
                        shape = RoundedCornerShape(OssmDimens.ControlRadius)
                    ),
                shape = RoundedCornerShape(OssmDimens.ControlRadius),
                color = if (selected) OssmSurfaceHigh else OssmSurfaceRaised
            ) {
                // Compact chip: one readable "min–max%" line, plus an optional saved-speed line.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OssmDimens.Space4, vertical = OssmDimens.Space8),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${preset.minPercent}–${preset.maxPercent}%",
                        color = if (selected) OssmPrimary else OssmOnSurface,
                        style = OssmValue,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center
                    )
                    preset.savedSpeedPercent?.let { saved ->
                        Text(
                            text = stringResource(R.string.ui2_preset_saved_speed_short, saved),
                            color = OssmOnSurfaceMuted,
                            style = OssmCaption,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RandomRangePresetDialog(
    target: RandomPresetTarget,
    index: Int,
    preset: RandomRangePreset,
    currentSpeedPercent: Int,
    onSave: (Int, Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var minText by remember(target, index, preset.minPercent) { mutableStateOf(preset.minPercent.toString()) }
    var maxText by remember(target, index, preset.maxPercent) { mutableStateOf(preset.maxPercent.toString()) }
    var saveSpeedToo by remember(target, index, preset.savedSpeedPercent) {
        mutableStateOf(preset.savedSpeedPercent != null)
    }
    val min = minText.toIntOrNull()
    val max = maxText.toIntOrNull()
    val valid = min != null && max != null && min in 0..100 && max in 0..100 && min <= max
    val targetLabel = if (target == RandomPresetTarget.SPEED) {
        stringResource(R.string.ctl_speed)
    } else {
        stringResource(R.string.ctl_depth)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        shape = RoundedCornerShape(OssmDimens.SheetRadius),
        tonalElevation = NoElevation,
        title = {
            Text(
                stringResource(R.string.ctl_random_preset_title, targetLabel, index + 1),
                color = OssmOnSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
            ) {
                Text(
                    stringResource(R.string.ctl_random_preset_hint),
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption
                )
                Row(horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.ctl_random_preset_min)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OssmPrimary,
                            unfocusedBorderColor = OssmOutline,
                            focusedTextColor = OssmOnSurface,
                            unfocusedTextColor = OssmOnSurface
                        )
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.ctl_random_preset_max)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OssmPrimary,
                            unfocusedBorderColor = OssmOutline,
                            focusedTextColor = OssmOnSurface,
                            unfocusedTextColor = OssmOnSurface
                        )
                    )
                }
                OssmSwitchRow(
                    label = stringResource(R.string.ctl_random_save_speed, currentSpeedPercent),
                    checked = saveSpeedToo,
                    onCheckedChange = { saveSpeedToo = it }
                )
            }
        },
        confirmButton = {
            OssmButton(
                text = stringResource(R.string.ctl_save),
                onClick = {
                    if (valid) onSave(min ?: 0, max ?: 0, saveSpeedToo)
                },
                kind = OssmButtonKind.Primary,
                enabled = valid
            )
        },
        dismissButton = {
            OssmButton(
                text = stringResource(R.string.ctl_cancel),
                onClick = onDismiss,
                kind = OssmButtonKind.Text
            )
        }
    )
}

@Composable
private fun AutoRandomPatternRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // Toute la rangee est la cible (libelle + case), 56 dp mini ; coche + contour turquoise = choisi.
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (checked) OssmSurfaceHigh else OssmSurfaceRaised)
            .border(
                if (checked) ActiveBorderWidth else OssmDimens.BorderWidth,
                if (checked) OssmPrimary else OssmOutline,
                shape
            )
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = SettingsRowMinHeight)
            .padding(horizontal = OssmDimens.Space12),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = OssmOnSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = OssmPrimary,
                uncheckedColor = OssmOutline,
                checkmarkColor = OssmOnPrimary
            )
        )
    }
}

@Composable
private fun AutoRandomStartDialog(
    selectedCount: Int,
    maxSpeedPercent: Int,
    depthMinPercent: Int,
    depthMaxPercent: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        tonalElevation = NoElevation,
        title = { Text(stringResource(R.string.ctl_auto_dialog_title), color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)) {
                Text(
                    stringResource(R.string.ctl_auto_dialog_count, selectedCount),
                    color = OssmOnSurface,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.ctl_auto_dialog_limits, maxSpeedPercent, depthMinPercent, depthMaxPercent),
                    color = OssmOnSurfaceMuted,
                    style = OssmValue
                )
                OssmStatusLine(
                    text = stringResource(R.string.ctl_auto_dialog_warning),
                    tone = OssmTone.Warn
                )
            }
        },
        confirmButton = {
            OssmButton(
                text = stringResource(R.string.ctlrd_start),
                onClick = onConfirm,
                kind = OssmButtonKind.Primary
            )
        },
        dismissButton = {
            OssmButton(
                text = stringResource(R.string.ctl_cancel),
                onClick = onDismiss,
                kind = OssmButtonKind.Text
            )
        }
    )
}

/** Les dialogues restent a plat : pas de teinte d'elevation. */
private val NoElevation = 0.dp

// Ecart entre deux lignes du dialogue de reorganisation (le seuil de permutation en tient compte).
private val ReorderRowSpacing = 6.dp

@Composable
private fun SliderGuardRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OssmSwitchRow(
        label = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        supporting = description
    )
}

@Composable
internal fun DepthRangeEditor(
    minValue: Float,
    maxValue: Float,
    enabled: Boolean,
    onRangeChange: (Float, Float) -> Unit,
    onRangeCommit: (Float, Float) -> Unit,
    positionFraction: Float? = null,
    randomMinValue: Float? = null,
    randomMaxValue: Float? = null,
    headerAccessory: (@Composable RowScope.() -> Unit)? = null,
    gestureCancellationKey: Long = 0L,
    labelOverride: String? = null,
    /** Plafond imposé par une session distante : cadenas, mot « limité » et lecture TalkBack. */
    limited: Boolean = LocalModeContext.current.remoteLimited
) {
    val displayedMin = randomMinValue ?: minValue
    val displayedMax = randomMaxValue ?: maxValue
    val randomRangeActive = randomMinValue != null || randomMaxValue != null
    val markers = buildList {
        positionFraction?.let { add(RangeBarMarker(it, OssmError.copy(alpha = 0.9f))) }
        randomMinValue?.let { add(RangeBarMarker(it, OssmError)) }
        randomMaxValue?.let { add(RangeBarMarker(it, OssmError)) }
    }
    val label = labelOverride ?: stringResource(R.string.ctl_depth)
    SliderFrame(
        label = label,
        displayValue = "${(displayedMin * 100).toInt()}–${(displayedMax * 100).toInt()}%",
        enabled = enabled,
        displayValueColor = when {
            !enabled -> OssmOnSurfaceMuted
            randomRangeActive -> OssmError
            else -> null
        },
        headerAccessory = headerAccessory,
        limited = limited
    ) {
        // -/+ move the bar touched last (retract or depth); the depth bar by default.
        RangeBarControl(
            start = minValue,
            end = maxValue,
            enabled = enabled,
            activeColor = OssmDepth,
            label = label,
            onRangeChange = onRangeChange,
            onRangeCommit = onRangeCommit,
            modifier = Modifier.weight(1f),
            markers = markers,
            gestureCancellationKey = gestureCancellationKey,
            stateSuffix = if (limited) stringResource(R.string.sld_limited) else null
        )
    }
}

/**
 * Réorganisation des patterns : appui long sur une ligne pour la saisir, glisser
 * pour la déplacer, relâcher pour la déposer. L'ordre s'applique à la grille.
 * Actions « Monter » / « Descendre » en plus pour les lecteurs d'écran.
 */
@Composable
private fun ReorderPatternsDialog(
    patternNames: List<Pair<String, String>>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var order by remember { mutableStateOf(patternNames) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableIntStateOf(1) }
    val moveUpLabel = stringResource(R.string.ctlrd_reorder_up)
    val moveDownLabel = stringResource(R.string.ctlrd_reorder_down)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface.copy(alpha = if (draggingIndex >= 0) 0.75f else 1f),
        tonalElevation = NoElevation,
        title = { Text(stringResource(R.string.ctl_reorder_title), color = OssmOnSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ReorderRowSpacing)
            ) {
                Text(
                    stringResource(R.string.ctl_reorder_hint),
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption
                )
                order.forEachIndexed { index, entry ->
                    val key = entry.first
                    val isDragging = index == draggingIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { if (index == 0) rowHeightPx = it.height + 6 }
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffsetPx else 0f
                                alpha = if (isDragging) 0.85f else 1f
                            }
                            .clip(RoundedCornerShape(OssmDimens.ControlRadius))
                            .background(
                                if (isDragging) OssmSurfaceHigh else OssmSurfaceRaised
                            )
                            .border(
                                OssmDimens.BorderWidth,
                                if (isDragging) OssmPrimary else OssmOutline,
                                RoundedCornerShape(OssmDimens.ControlRadius)
                            )
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUpLabel) {
                                        val current = order.indexOfFirst { it.first == key }
                                        if (current > 0) {
                                            val m = order.toMutableList()
                                            m.add(current - 1, m.removeAt(current))
                                            order = m
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                    CustomAccessibilityAction(moveDownLabel) {
                                        val current = order.indexOfFirst { it.first == key }
                                        if (current >= 0 && current < order.size - 1) {
                                            val m = order.toMutableList()
                                            m.add(current + 1, m.removeAt(current))
                                            order = m
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                )
                            }
                            .pointerInput(key, order.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = order.indexOfFirst { it.first == key }
                                        dragOffsetPx = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetPx += amount.y
                                        val current = order.indexOfFirst { it.first == key }
                                        val h = rowHeightPx.toFloat()
                                        if (dragOffsetPx > h / 2 && current < order.size - 1) {
                                            val m = order.toMutableList()
                                            val item = m.removeAt(current)
                                            m.add(current + 1, item)
                                            order = m
                                            draggingIndex = current + 1
                                            dragOffsetPx -= h
                                        } else if (dragOffsetPx < -h / 2 && current > 0) {
                                            val m = order.toMutableList()
                                            val item = m.removeAt(current)
                                            m.add(current - 1, item)
                                            order = m
                                            draggingIndex = current - 1
                                            dragOffsetPx += h
                                        }
                                    },
                                    onDragEnd = { draggingIndex = -1; dragOffsetPx = 0f },
                                    onDragCancel = { draggingIndex = -1; dragOffsetPx = 0f }
                                )
                            }
                            .heightIn(min = OssmDimens.TouchTarget)
                            .padding(horizontal = OssmDimens.Space12, vertical = OssmDimens.Space8)
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            tint = OssmOnSurfaceMuted,
                            modifier = Modifier.size(ControlIconSize)
                        )
                        Spacer(Modifier.width(OssmDimens.Space12))
                        Text(
                            entry.second,
                            color = OssmOnSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            OssmButton(
                text = stringResource(R.string.ctl_save),
                onClick = { onSave(order.map { it.first }) },
                kind = OssmButtonKind.Primary
            )
        },
        dismissButton = {
            OssmButton(
                text = stringResource(R.string.ctl_cancel),
                onClick = onDismiss,
                kind = OssmButtonKind.Text
            )
        }
    )
}

@Composable
private fun SavePresetDialog(
    currentState: ControlUiState,
    onDismiss: () -> Unit,
    onSave: (Preset) -> Unit
) {
    val defaultPresetName = stringResource(R.string.ctl_preset_default_name)
    val fallbackPresetName = stringResource(R.string.ctl_preset_fallback_name)
    var name by remember { mutableStateOf(defaultPresetName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        tonalElevation = NoElevation,
        title = { Text(stringResource(R.string.ctl_preset_save_title), color = OssmOnSurface) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.ctl_preset_name_label)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OssmPrimary,
                    unfocusedBorderColor = OssmOutline,
                    focusedTextColor = OssmOnSurface,
                    unfocusedTextColor = OssmOnSurface
                )
            )
        },
        confirmButton = {
            OssmButton(
                text = stringResource(R.string.ctl_preset_save),
                onClick = {
                    val pattern = currentState.activePattern ?: return@OssmButton
                    onSave(
                        Preset(
                            name = name.ifBlank { fallbackPresetName },
                            patternKey = pattern.key,
                            patternName = pattern.name,
                            speed = currentState.speed,
                            depthMin = currentState.depthMin,
                            depthMax = currentState.depthMax
                        )
                    )
                },
                kind = OssmButtonKind.Primary
            )
        },
        dismissButton = {
            OssmButton(
                text = stringResource(R.string.ctl_cancel),
                onClick = onDismiss,
                kind = OssmButtonKind.Text
            )
        }
    )
}

@Composable
private fun AbruptChangeDialog(
    control: GuardedControl,
    thresholdPercent: Int,
    onConfirm: (Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var skipForSession by remember { mutableStateOf(false) }
    var neverAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        tonalElevation = NoElevation,
        title = { Text(stringResource(R.string.ctl_abrupt_title), color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)) {
                Text(
                    stringResource(R.string.ctl_abrupt_body, stringResource(controlLabel(control)), thresholdPercent),
                    color = OssmOnSurfaceMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(color = OssmGlassBorder)
                GuardCheckboxRow(
                    label = stringResource(R.string.ctl_abrupt_skip_session),
                    checked = skipForSession,
                    onCheckedChange = { skipForSession = it }
                )
                GuardCheckboxRow(
                    label = stringResource(R.string.ctl_abrupt_never),
                    checked = neverAskAgain,
                    onCheckedChange = { neverAskAgain = it }
                )
            }
        },
        confirmButton = {
            // Changement risque : bouton neutre (contour), l'action sure (Annuler) reste la plus visible.
            OssmButton(
                text = stringResource(R.string.ctl_abrupt_confirm),
                onClick = { onConfirm(skipForSession, neverAskAgain) },
                kind = OssmButtonKind.Secondary
            )
        },
        dismissButton = {
            OssmButton(
                text = stringResource(R.string.ctl_cancel),
                onClick = onDismiss,
                kind = OssmButtonKind.Primary
            )
        }
    )
}

private fun controlLabel(control: GuardedControl): Int = when (control) {
    GuardedControl.SPEED -> R.string.ctl_abrupt_speed
    GuardedControl.DEPTH -> R.string.ctl_abrupt_depth
}

@Composable
private fun GuardCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    OssmSwitchRow(
        label = label,
        checked = checked,
        onCheckedChange = onCheckedChange
    )
}

/**
 * Bandeau compact affiché pendant une session distante active : maximums (vitesse, profondeur,
 * sensation) fixés par les curseurs locaux, valeurs EN DIRECT envoyées par le site, et avertissement si le maximum de
 * vitesse est à 0 % (le site ne peut alors rien faire bouger).
 * Ambre + cadenas = limite imposée par la session ; le texte porte l'information, jamais la couleur seule.
 */
@Composable
private fun RemoteSessionBanner(
    view: RemoteSessionView,
    patternName: String?,
    compact: Boolean
) {
    val blocked = view.motionBlockedByZeroMaximum
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    fun pct(value: Float) = (value.coerceIn(0f, 1f) * 100f).roundToInt()
    val live = buildList {
        view.remoteSpeed?.let { add(stringResource(R.string.ui2_remote_live_speed, pct(it))) }
        val liveMin = view.remoteDepthMin
        val liveMax = view.remoteDepthMax
        if (liveMin != null && liveMax != null) {
            add(stringResource(R.string.ui2_remote_live_depth, pct(liveMin), pct(liveMax)))
        }
        view.remoteSensation?.let { add(stringResource(R.string.ui2_remote_live_sensation, signedSensationLabel(it))) }
        patternName?.let { add(stringResource(R.string.ui2_remote_live_pattern, it)) }
        view.remoteLivePercent?.let { add(stringResource(R.string.ui2_remote_live_position, it)) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (compact) OssmDimens.Space8 else OssmDimens.Space12)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, OssmWarning.copy(alpha = 0.6f), shape)
            .padding(horizontal = OssmDimens.Space12, vertical = OssmDimens.Space8)
            .testTag("remote_session_banner"),
        verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = OssmWarning,
                modifier = Modifier.size(StatusIconSize)
            )
            Spacer(Modifier.width(OssmDimens.Space8))
            Text(
                stringResource(
                    R.string.ui2_remote_banner_limits,
                    pct(view.speedMax),
                    pct(view.depthMin),
                    pct(view.depthMax)
                ) + " · " + stringResource(R.string.ui2_remote_banner_sensation_range, *remoteSensationBannerArgs(view)),
                color = OssmWarning,
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (blocked) {
            OssmStatusLine(
                text = stringResource(R.string.ui2_remote_blocked_zero),
                tone = OssmTone.Warn
            )
        }
        Text(
            if (live.isEmpty()) {
                stringResource(R.string.ui2_remote_live_waiting)
            } else {
                stringResource(R.string.ui2_remote_live_prefix, live.joinToString(" · "))
            },
            color = OssmOnSurfaceMuted,
            style = OssmCaption
        )
    }
}

/** Quiet period after a local slider edit before external state may overwrite the drafts. */
internal const val CONTROL_DRAFT_QUIET_MS = 250L

/**
 * Tracks the last local slider edit (plain fields: written on every drag tick without
 * triggering recomposition) and remote-session transitions for the ControlScreen draft sync.
 */
internal class ControlDraftEditClock(private val quietMs: Long = CONTROL_DRAFT_QUIET_MS) {
    private var lastLocalEditAt = Long.MIN_VALUE
    private var lastSessionActive: Boolean? = null

    fun markLocalEdit(uptimeMillis: Long) {
        lastLocalEditAt = uptimeMillis
    }

    /** Remaining wait before external values may replace the drafts (0 = apply now). */
    fun remainingQuietMs(uptimeMillis: Long): Long {
        if (lastLocalEditAt == Long.MIN_VALUE) return 0L
        return (lastLocalEditAt + quietMs - uptimeMillis).coerceIn(0L, quietMs)
    }

    /** True when the remote-session flag changed since the last sync (apply immediately). */
    fun consumeSessionChange(active: Boolean): Boolean {
        val previous = lastSessionActive
        lastSessionActive = active
        return previous != null && previous != active
    }
}

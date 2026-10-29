package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
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
import com.ossm.remote.ui.components.PatternButton
import com.ossm.remote.ui.components.PrecisionScalarSlider
import com.ossm.remote.ui.components.ProgressiveSpeedBar
import com.ossm.remote.ui.components.StopBar
import com.ossm.remote.ui.components.StopBarHeight
import com.ossm.remote.ui.components.StopButton
import com.ossm.remote.ui.components.StreamPositionSlider
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
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
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.ui.theme.PatternColors
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
    onSpeedCommitWithEpoch: ((Float, Long) -> Unit)? = null,
    onSpeedLiveWithEpoch: ((Float, Long) -> Unit)? = null,
    onSensationRandomRangeChange: (Float, Float) -> Unit = { _, _ -> },
    onSensationCommitWithEpoch: ((Float, Long) -> Unit)? = null,
    onSensationLiveWithEpoch: ((Float, Long) -> Unit)? = null,
    naturalActions: NaturalControlActions = NaturalControlActions(),
    voiceActions: VoiceControlActions = VoiceControlActions(),
    voiceAi: com.ossm.remote.voice.VoiceAiUiState = com.ossm.remote.voice.VoiceAiUiState(),
    voiceModelRoot: String? = null,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        CompositionLocalProvider(LocalControlPrecisionEnabled provides precisionEnabled) {
        CompactControlScaffold(
            showActionRail = showActionRail,
            bare = liveBareScreen,
            actionRail = { landscape ->
                CompactActionRail(
                    landscape = landscape,
                    showStop = showStopBar,
                    showPause = actionVisibility.showPause,
                    showRotation = !liveBareScreen,
                    statusKind = null,
                    machineState = machineState,
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
            // The status is never inserted in the STOP line: a one-row banner under the STOP
            // bar in portrait; in landscape, where there is no height to spare, a pill in the
            // card header instead.
            if (statusKind != null && !compactLandscape) {
                ControlStatusLine(
                    kind = statusKind,
                    machineState = machineState,
                    boxed = true,
                    singleLine = true
                )
            }

            // Voix shares the STREAMING entry but has its own scrollable panel (no touch pad).
            val liveControlActive = uiState.rangeWizard == null &&
                activePattern?.mode == PatternControlMode.STREAMING &&
                !activePattern.isVoicePattern()
            val liveLimits = remember { LiveLimitsState() }
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    // The card always takes the whole height between STOP and the bottom bar.
                    .weight(1f)
                    .testTag("control_card"),
                padding = if (liveBareScreen) 6.dp else if (compactLandscape) 12.dp else 16.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PatternOpenerButton(
                        onClick = { patternPickerOpen = true },
                        modifier = Modifier
                    )
                    Text(
                        controlHeaderTitle(activePattern),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    if (compactLandscape && statusKind != null) {
                        ControlStatusPill(kind = statusKind, machineState = machineState)
                    }
                    if (activePattern != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                IconButton(
                                    onClick = { showSaveDialog = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(OssmSurfaceRaised)
                                        .border(1.dp, OssmOutline, RoundedCornerShape(12.dp))
                                ) {
                                    Icon(
                                        Icons.Default.Save,
                                        stringResource(R.string.ctl_save),
                                        tint = OssmPrimaryLight
                                    )
                                }
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
                // Slider modes never scroll: the controls are sized to the card. Short cards
                // (landscape) put each slider on one line so all of them stay visible and the
                // tracks get nearly the whole card width.
                val sliderMetrics = when {
                    !spreadControls || !bodyBounded -> RegularControlSliderMetrics
                    compactLandscape || bodyViewport < 400.dp -> InlineControlSliderMetrics
                    bodyViewport >= 560.dp -> LargeControlSliderMetrics
                    else -> RegularControlSliderMetrics
                }
                CompositionLocalProvider(LocalControlSliderMetrics provides sliderMetrics) {
                Column(
                    modifier = when {
                        liveControlActive -> Modifier.fillMaxSize()
                        spreadControls && bodyBounded -> Modifier.fillMaxSize()
                        scrollBody -> Modifier
                            .fillMaxWidth()
                            .verticalScroll(settingsScrollState)
                        else -> Modifier.fillMaxWidth()
                    },
                    verticalArrangement = if (spreadControls) Arrangement.SpaceEvenly else Arrangement.Top
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
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        if (!uiState.streamingReady) {
                            Text(
                                buildString {
                                    append(stringResource(R.string.ctl_wizard_preparing, machineState.state.toString()))
                                    if (machineState.isPreflight) {
                                        append("\n" + stringResource(R.string.ctl_knob_to_zero))
                                    }
                                },
                                color = OssmWarning.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 10.dp)
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
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onRangeWizardCapture,
                                enabled = slidersEnabled && uiState.streamingReady,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
                            ) {
                                Text(
                                    if (wiz.step == 1) stringResource(R.string.ctl_wizard_set_depth) else stringResource(R.string.ctl_wizard_set_retract),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                            Button(
                                onClick = onRangeWizardCancel,
                                enabled = connected,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
                            ) {
                                Text(
                                    stringResource(R.string.ctl_cancel),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        if (wiz.step == 2 && wiz.capturedMax != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.ctl_wizard_depth_set, (wiz.capturedMax * 100).toInt()),
                                color = OssmConnected,
                                fontSize = 12.sp
                            )
                        }
                    }
                    activePattern == null -> {
                        Text(
                            stringResource(R.string.ctl_select_pattern_to_start),
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                    activePattern.mode == PatternControlMode.LAUNCH_ONLY -> {
                        Text(
                            stringResource(R.string.ctl_launch_only_hint),
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 13.sp
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
                            Text(
                                buildString {
                                    append(stringResource(R.string.ctl_live_preparing))
                                    append("\n" + stringResource(R.string.ctl_machine_state, machineState.state.toString()))
                                    machineState.stroke?.let { append("  stroke=$it") }
                                    machineState.depth?.let { append("  depth=$it") }
                                    machineState.speed?.let { append("  speed=$it") }
                                    if (machineState.isPreflight) {
                                        append("\n" + stringResource(R.string.ctl_knob_to_zero_mode))
                                    }
                                },
                                color = OssmWarning.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
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
                            onMaxChange = onProgressiveMaxCommit
                        )
                        Spacer(Modifier.height(12.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
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
                        Spacer(Modifier.height(8.dp))
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
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = uiState.chaosAtMax,
                                onCheckedChange = { onChaosToggle(it) },
                                enabled = connected,
                                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
                                colors = androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = OssmAccent,
                                    uncheckedColor = OssmOnSurface.copy(alpha = 0.5f)
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    stringResource(R.string.ctl_chaos_title),
                                    color = OssmOnSurface,
                                    fontSize = 14.sp
                                )
                                Text(
                                    stringResource(R.string.ctl_chaos_hint),
                                    color = OssmOnSurface.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
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
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Text(
                            stringResource(R.string.ctl_auto_modes_header),
                            color = OssmPrimaryLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AutoRandomMixablePatterns.forEach { pattern ->
                                AutoRandomPatternRow(
                                    label = pattern.name,
                                    checked = pattern.key in uiState.autoSelectedKeys,
                                    onCheckedChange = { onAutoToggleSelected(pattern.key) }
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.ctl_auto_speed_header),
                            color = OssmPrimaryLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        ControlSlider(
                            label = stringResource(R.string.ctl_auto_speed_ceiling),
                            value = if (remoteSession.active) remoteSession.speedMax else uiState.autoMaxSpeed,
                            onValueChange = onAutoMaxSpeedChange,
                            onValueCommit = onAutoMaxSpeedChange,
                            enabled = slidersEnabled,
                            activeColor = OssmPrimary,
                            gestureCancellationKey = uiState.liveGestureEpoch
                        )
                        Spacer(Modifier.height(12.dp))
                        ControlSlider(
                            label = stringResource(R.string.ctl_auto_intensity_cap),
                            value = uiState.autoIntensityCap,
                            onValueChange = onAutoIntensityCapChange,
                            onValueCommit = onAutoIntensityCapChange,
                            enabled = slidersEnabled,
                            activeColor = OssmAccent,
                            gestureCancellationKey = uiState.liveGestureEpoch
                        )
                        Spacer(Modifier.height(12.dp))
                        ControlSlider(
                            label = stringResource(R.string.ctl_auto_randomness),
                            value = uiState.autoRandomness,
                            onValueChange = onAutoRandomnessChange,
                            onValueCommit = onAutoRandomnessChange,
                            enabled = slidersEnabled,
                            activeColor = OssmWarning,
                            gestureCancellationKey = uiState.liveGestureEpoch
                        )
                        Spacer(Modifier.height(12.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
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
                        Spacer(Modifier.height(12.dp))
                        if (uiState.autoRunning) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                tint = OssmConnected
                            ) {
                                Text(
                                    stringResource(R.string.ctl_auto_intensity_value, (uiState.autoIntensity * 100).toInt()),
                                    color = OssmConnected,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.ctl_auto_running_hint),
                                    color = OssmOnSurface.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            ControlSlider(
                                label = stringResource(R.string.ctl_auto_initial_intensity),
                                value = uiState.autoInitialIntensity,
                                onValueChange = onAutoInitialIntensityChange,
                                onValueCommit = onAutoInitialIntensityChange,
                                enabled = slidersEnabled,
                                activeColor = OssmConnected,
                                gestureCancellationKey = uiState.liveGestureEpoch
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onAutoRequestStart,
                                enabled = slidersEnabled && !uiState.autoRunning && uiState.autoSelectedKeys.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
                            ) {
                                Text(
                                    stringResource(R.string.ctl_start),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                            Button(
                                onClick = onAutoStop,
                                enabled = connected && uiState.autoRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
                            ) {
                                Text(
                                    stringResource(R.string.ctl_stop_mix),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
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
                        Spacer(Modifier.height(6.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
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
                            Spacer(Modifier.height(6.dp))
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
                    .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Rail just as wide as the round STOP, so the card starts right after it.
                Box(
                    modifier = Modifier
                        .width(StopBarHeight + 8.dp)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    actionRail(true)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = { content(true) }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (bare) 4.dp else if (landscape) 8.dp else 16.dp,
                        vertical = if (bare) 4.dp else if (landscape) 6.dp else 8.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showActionRail) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ActionRailHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        actionRail(false)
                    }
                }
                content(landscape)
            }
        }
    }
}

// 2.3.28: portrait rail = compact STOP bar (56dp) + pause on the same line.
private val ActionRailHeight = StopBarHeight
private val StopButtonSize = 88.dp

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

/**
 * Compact connection/machine status. [boxed] draws it as a one-row tinted banner (landscape,
 * Live); otherwise it is bare content meant to sit inside the portrait action rail.
 */
@Composable
private fun ControlStatusLine(
    kind: ControlStatusKind,
    machineState: MachineState,
    modifier: Modifier = Modifier,
    boxed: Boolean = false,
    singleLine: Boolean = false
) {
    val tint = when (kind) {
        ControlStatusKind.USB -> OssmConnected
        ControlStatusKind.DISCONNECTED -> OssmWarning
        ControlStatusKind.CALIBRATING -> OssmAccent
        ControlStatusKind.ERROR -> OssmError
    }
    val icon = when (kind) {
        ControlStatusKind.USB -> Icons.Default.CheckCircle
        ControlStatusKind.DISCONNECTED -> Icons.Default.BluetoothDisabled
        ControlStatusKind.CALIBRATING -> Icons.Default.Info
        ControlStatusKind.ERROR -> Icons.Default.Warning
    }
    val shape = RoundedCornerShape(12.dp)
    val rowModifier = modifier
        .then(
            if (boxed) {
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tint.copy(alpha = 0.12f))
                    .border(1.dp, tint.copy(alpha = 0.4f), shape)
                    .padding(horizontal = 12.dp, vertical = if (singleLine) 6.dp else 8.dp)
            } else {
                Modifier
            }
        )
        .semantics(mergeDescendants = true) {}
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (singleLine) 20.dp else 24.dp))
        if (singleLine) {
            val text = when (kind) {
                ControlStatusKind.DISCONNECTED ->
                    stringResource(R.string.ui2_status_disconnected_title) + " · " +
                        stringResource(R.string.ui2_status_disconnected_hint)
                ControlStatusKind.USB -> stringResource(R.string.ctl_usb_active)
                ControlStatusKind.CALIBRATING -> stringResource(R.string.ui2_status_calibrating, machineState.displayLabel)
                ControlStatusKind.ERROR -> machineState.displayLabel
            }
            Text(
                text,
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("control_status_line")
            )
        } else Column(modifier = Modifier.weight(1f, fill = false)) {
            when (kind) {
                ControlStatusKind.DISCONNECTED -> {
                    Text(
                        stringResource(R.string.ui2_status_disconnected_title),
                        color = tint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.ui2_status_disconnected_hint),
                        color = OssmOnSurfaceMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ControlStatusKind.USB -> Text(
                    stringResource(R.string.ctl_usb_active),
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3
                )
                ControlStatusKind.CALIBRATING -> Text(
                    stringResource(R.string.ui2_status_calibrating, machineState.displayLabel),
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3
                )
                ControlStatusKind.ERROR -> Text(
                    machineState.displayLabel,
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3
                )
            }
        }
    }
}

/** One-row status pill for the landscape card header; takes no vertical space of its own. */
@Composable
private fun ControlStatusPill(
    kind: ControlStatusKind,
    machineState: MachineState
) {
    val tint = when (kind) {
        ControlStatusKind.USB -> OssmConnected
        ControlStatusKind.DISCONNECTED -> OssmWarning
        ControlStatusKind.CALIBRATING -> OssmAccent
        ControlStatusKind.ERROR -> OssmError
    }
    val icon = when (kind) {
        ControlStatusKind.USB -> Icons.Default.CheckCircle
        ControlStatusKind.DISCONNECTED -> Icons.Default.BluetoothDisabled
        ControlStatusKind.CALIBRATING -> Icons.Default.Info
        ControlStatusKind.ERROR -> Icons.Default.Warning
    }
    val label = when (kind) {
        ControlStatusKind.DISCONNECTED -> stringResource(R.string.ui2_status_disconnected_title)
        ControlStatusKind.USB -> stringResource(R.string.ctl_usb_active)
        ControlStatusKind.CALIBRATING ->
            stringResource(R.string.ui2_status_calibrating, machineState.displayLabel)
        ControlStatusKind.ERROR -> machineState.displayLabel
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.45f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("control_status_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactActionRail(
    landscape: Boolean,
    showStop: Boolean,
    showPause: Boolean,
    showRotation: Boolean,
    statusKind: ControlStatusKind?,
    machineState: MachineState,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showStop) {
                StopBar(
                    onClick = onStop,
                    vertical = true,
                    modifier = Modifier
                        .width(StopBarHeight)
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
                    size = StopBarHeight
                )
            }
            if (showRotation) RotationLockButton(width = StopBarHeight, height = 44.dp)
        }
    } else {
        // 2.3.28: one compact line: wide STOP bar (first in TalkBack order), pause/play 56dp.
        // The status banner is drawn under this line, never inside it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showStop) {
                StopBar(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(StopBarHeight)
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
                    size = StopBarHeight
                )
            }
            if (showRotation) RotationLockButton(width = 44.dp, height = StopBarHeight)
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
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(if (locked) OssmPrimary.copy(alpha = 0.28f) else OssmSurfaceRaised)
            .border(1.dp, if (locked) OssmPrimary else OssmOutline, shape)
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
            tint = if (locked) OssmPrimaryLight else OssmOnSurfaceMuted,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun PatternOpenerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OssmSurfaceRaised)
            .border(1.dp, OssmOutline, RoundedCornerShape(12.dp))
    ) {
        Icon(
            Icons.Default.TouchApp,
            contentDescription = stringResource(R.string.ctl_patterns_open),
            tint = OssmPrimaryLight,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun PatternPickerOverlay(
    uiState: ControlUiState,
    onPattern: (String) -> Unit,
    onReorder: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (landscape) 112.dp else 12.dp,
                    top = if (landscape) 8.dp else 100.dp,
                    end = 12.dp,
                    bottom = 8.dp
                )
                .clip(RoundedCornerShape(20.dp))
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
                        stringResource(R.string.ctl_patterns),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.ctl_patterns_hint),
                        color = OssmOnSurfaceMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.ctl_patterns_collapse),
                        tint = OssmPrimaryLight
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            if (uiState.availablePatterns.isEmpty()) {
                Text(
                    stringResource(R.string.ctl_no_patterns),
                    color = OssmOnSurface.copy(alpha = 0.7f),
                    fontSize = 13.sp
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
                val gap = 8.dp
                val rowHeight = if (rows.isEmpty()) 52.dp else {
                    ((maxHeight - gap * (rows.size - 1)) / rows.size).coerceIn(52.dp, 120.dp)
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
                                val index = ordered.indexOf(pattern)
                                PatternButton(
                                    label = pattern.name,
                                    color = PatternColors[index % PatternColors.size],
                                    isActive = uiState.activePatternKey == pattern.key,
                                    onClick = { onPattern(pattern.key) },
                                    onLongClick = onReorder,
                                    enabled = true,
                                    fontSize = if (rowHeight >= 72.dp) 16.sp else 13.sp,
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
                    activeColor = OssmAccent,
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
            activeColor = OssmAccent,
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
            bipolar = BipolarStyle()
        )
        if (metrics.showScaleLegends) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("−100", color = OssmOnSurfaceMuted, fontSize = 11.sp)
            Text(stringResource(R.string.ctl_sensation_neutral), color = OssmOnSurfaceMuted, fontSize = 11.sp)
            Text("+100", color = OssmOnSurfaceMuted, fontSize = 11.sp)
        }
        sensationLegendRes(patternKey)?.let { legend ->
            Text(
                stringResource(legend),
                color = OssmOnSurfaceMuted,
                fontSize = 11.sp,
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
        moving -> OssmAccent
        locked -> OssmPrimaryLight
        else -> OssmOnSurface.copy(alpha = 0.8f)
    }
    IconButton(
        enabled = enabled,
        onClick = { limits.toggle(side) },
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (moving) OssmAccent.copy(alpha = 0.22f) else OssmSurfaceRaised)
            .border(
                if (moving || locked) 2.dp else 1.dp,
                if (moving) OssmAccent else if (locked) OssmPrimaryLight else OssmOutline,
                RoundedCornerShape(10.dp)
            )
            .semantics { contentDescription = description }
    ) {
        LiveLimitIcon(side = side, color = if (enabled) tint else tint.copy(alpha = 0.3f))
    }
}

@Composable
private fun LiveRecordButton(
    state: LiveRecordingState,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val description = stringResource(liveRecordContentDescription(state))
    val active = state != LiveRecordingState.IDLE
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OssmSurfaceRaised)
            .border(
                1.dp,
                if (active) OssmError else OssmOutline,
                RoundedCornerShape(10.dp)
            )
            .semantics { contentDescription = description }
    ) {
        Box(
            modifier = Modifier
                .size(if (state == LiveRecordingState.RECORDING) 18.dp else 14.dp)
                .clip(CircleShape)
                .background(
                    OssmError.copy(
                        alpha = if (enabled) {
                            if (active) 1f else 0.72f
                        } else {
                            0.25f
                        }
                    )
                )
        )
    }
}

private fun liveRecordContentDescription(state: LiveRecordingState): Int = when (state) {
    LiveRecordingState.IDLE -> R.string.ctl_rec_idle
    LiveRecordingState.ARMED -> R.string.ctl_rec_armed
    LiveRecordingState.RECORDING -> R.string.ctl_rec_recording
    LiveRecordingState.RECORDED -> R.string.ctl_rec_recorded
    LiveRecordingState.PLAYING -> R.string.ctl_rec_playing
}

@Composable
private fun SettingsGearButton(
    onInfoClick: () -> Unit
) {
    IconButton(
        onClick = onInfoClick,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(OssmSurfaceRaised)
            .border(1.dp, OssmOutline, RoundedCornerShape(12.dp))
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = stringResource(R.string.ctl_control_settings),
            tint = OssmPrimaryLight,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun PausePlayButton(
    isPaused: Boolean,
    enabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    size: Dp = 64.dp
) {
    val activeColor = if (isPaused) OssmConnected else OssmWarning
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size - 4.dp)
                .clip(CircleShape)
                .background(if (enabled) activeColor else OssmSurfaceRaised)
                .border(1.dp, if (enabled) activeColor else OssmOutline, CircleShape)
                .clickable(enabled = enabled, role = Role.Button) {
                    if (isPaused) onResume() else onPause()
                }
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPaused) stringResource(R.string.ctl_resume) else stringResource(R.string.ctl_pause),
                tint = if (enabled) OssmBackground else OssmOnSurfaceMuted,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

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
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.9f),
                shape = RoundedCornerShape(24.dp),
                color = OssmSurface,
                tonalElevation = 10.dp,
                shadowElevation = 18.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, OssmOutline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ctl_control_settings),
                            color = OssmOnSurface,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.ctl_close_settings),
                                tint = OssmPrimaryLight
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsToggleRow(
                                title = stringResource(R.string.ctl_precision_option),
                                explanation = precisionHint,
                                checked = precisionChecked,
                                onCheckedChange = onPrecisionCheckedChange,
                                onExplanationVisibilityChange = { visible ->
                                    heldExplanation = precisionHint.takeIf { visible }
                                }
                            )
                            if (showLiveAcceleration) {
                                HorizontalDivider(color = OssmGlassBorder)
                                SettingLabelWithInfo(
                                    title = stringResource(
                                        R.string.ctl_live_acceleration_value,
                                        accelerationDraft
                                    ),
                                    explanation = accelerationHint,
                                    onExplanationVisibilityChange = { visible ->
                                        heldExplanation = accelerationHint.takeIf { visible }
                                    }
                                )
                                PrecisionScalarSlider(
                                    valuePercent = (accelerationDraft - 10) / 90f * 100f,
                                    enabled = true,
                                    precisionEnabled = precisionChecked,
                                    activeColor = OssmAccent,
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
                                        .heightIn(min = 56.dp)
                                )
                            }
                            if (showLiveInvert) {
                                HorizontalDivider(color = OssmGlassBorder)
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
                                title = stringResource(
                                    R.string.ctl_depth_limits_value,
                                    (depthLimits.start * 100).toInt(),
                                    (depthLimits.endInclusive * 100).toInt()
                                ),
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
                                colors = SliderDefaults.colors(
                                    thumbColor = OssmAccent,
                                    activeTrackColor = OssmAccent
                                )
                            )
                            HorizontalDivider(color = OssmGlassBorder)
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
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = OssmSurfaceRaised,
                                tonalElevation = 12.dp,
                                shadowElevation = 14.dp,
                                border = androidx.compose.foundation.BorderStroke(1.dp, OssmPrimaryLight)
                            ) {
                                Text(
                                    text = explanation,
                                    color = OssmOnSurface,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OssmPrimary,
                            contentColor = OssmOnPrimary
                        )
                    ) {
                        Text(stringResource(R.string.ctl_understood))
                    }
                }
            }
        }
    }
}

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
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
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
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OssmOnPrimary,
                    checkedTrackColor = OssmPrimary
                )
            )
        }
        Spacer(Modifier.width(4.dp))
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
    modifier: Modifier = Modifier
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
            .size(48.dp)
            .clip(CircleShape)
            .background(OssmGlass)
            .border(1.dp, OssmGlassBorder, CircleShape)
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
            modifier = Modifier.size(20.dp)
        )
    }
}

internal fun shouldKeepHoldInfoVisible(distancePx: Float, touchSlopPx: Float): Boolean =
    distancePx <= touchSlopPx

@Composable
private fun InfoLine(title: String, description: String) {
    Column {
        Text(title, color = OssmPrimaryLight, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(description, color = OssmOnSurface.copy(alpha = 0.8f), fontSize = 12.sp)
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
    // Compact one-line toggle (48dp): the whole row is one checkbox target, so TalkBack reads
    // "label, checkbox, state" instead of an unnamed box next to loose text. The on-screen text
    // may be shorter than [label] (the section header already names speed/depth); TalkBack
    // still gets the full [label].
    val toggleSize = LocalControlSliderMetrics.current.toggleMinSize
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
            modifier = Modifier.sizeIn(minWidth = toggleSize, minHeight = toggleSize),
            colors = CheckboxDefaults.colors(
                checkedColor = OssmAccent,
                uncheckedColor = OssmOnSurface.copy(alpha = 0.5f)
            )
        )
        Text(
            text = visibleLabel,
            color = if (enabled) OssmOnSurface.copy(alpha = 0.9f) else OssmOnSurface.copy(alpha = 0.5f),
            fontSize = 13.sp,
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
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEachIndexed { index, preset ->
            val selected = index == selectedIndex
            val presetDescription = preset.savedSpeedPercent?.let { saved ->
                stringResource(R.string.ui2_preset_cd_saved, preset.minPercent, preset.maxPercent, saved)
            } ?: "${preset.minPercent}%–${preset.maxPercent}%"
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
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
                        width = 1.dp,
                        color = if (selected) OssmAccent else OssmGlassBorder,
                        shape = RoundedCornerShape(10.dp)
                    ),
                shape = RoundedCornerShape(10.dp),
                color = if (selected) OssmPrimary.copy(alpha = 0.22f) else OssmGlass.copy(alpha = 0.72f)
            ) {
                // Compact chip: one readable "min–max%" line, plus an optional saved-speed line.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${preset.minPercent}–${preset.maxPercent}%",
                        color = if (selected) OssmAccent else OssmOnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center
                    )
                    preset.savedSpeedPercent?.let { saved ->
                        Text(
                            text = stringResource(R.string.ui2_preset_saved_speed_short, saved),
                            color = OssmAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
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
        containerColor = OssmGlass,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
        title = {
            Text(
                stringResource(R.string.ctl_random_preset_title, targetLabel, index + 1),
                color = OssmOnSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.ctl_random_preset_hint),
                    color = OssmOnSurface.copy(alpha = 0.72f),
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.ctl_random_preset_min)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OssmPrimary,
                            unfocusedBorderColor = OssmGlassBorder,
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
                            unfocusedBorderColor = OssmGlassBorder,
                            focusedTextColor = OssmOnSurface,
                            unfocusedTextColor = OssmOnSurface
                        )
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = saveSpeedToo,
                        onCheckedChange = { saveSpeedToo = it },
                        colors = CheckboxDefaults.colors(checkedColor = OssmAccent)
                    )
                    Text(
                        stringResource(R.string.ctl_random_save_speed, currentSpeedPercent),
                        color = OssmOnSurface,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (valid) onSave(min ?: 0, max ?: 0, saveSpeedToo)
                },
                enabled = valid,
                colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
            ) {
                Text(stringResource(R.string.ctl_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ctl_cancel), color = OssmPrimaryLight)
            }
        }
    )
}

@Composable
private fun AutoRandomPatternRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OssmGlassBorder, shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = OssmOnSurface,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = OssmAccent,
                uncheckedColor = OssmOnSurface.copy(alpha = 0.5f)
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
        title = { Text(stringResource(R.string.ctl_auto_dialog_title), color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.ctl_auto_dialog_count, selectedCount),
                    color = OssmOnSurface
                )
                Text(
                    stringResource(R.string.ctl_auto_dialog_limits, maxSpeedPercent, depthMinPercent, depthMaxPercent),
                    color = OssmOnSurface.copy(alpha = 0.8f)
                )
                Text(
                    stringResource(R.string.ctl_auto_dialog_warning),
                    color = OssmWarning,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
            ) {
                Text(stringResource(R.string.ctl_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ctl_cancel), color = OssmPrimaryLight)
            }
        }
    )
}

@Composable
private fun SliderGuardRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = OssmOnSurface,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                description,
                color = OssmOnSurface.copy(alpha = 0.65f),
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmPrimary,
                checkedTrackColor = OssmPrimary.copy(alpha = 0.35f)
            )
        )
    }
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
    gestureCancellationKey: Long = 0L
) {
    val displayedMin = randomMinValue ?: minValue
    val displayedMax = randomMaxValue ?: maxValue
    val randomRangeActive = randomMinValue != null || randomMaxValue != null
    val markers = buildList {
        positionFraction?.let { add(RangeBarMarker(it, OssmError.copy(alpha = 0.9f))) }
        randomMinValue?.let { add(RangeBarMarker(it, OssmError)) }
        randomMaxValue?.let { add(RangeBarMarker(it, OssmError)) }
    }
    val label = stringResource(R.string.ctl_depth)
    SliderFrame(
        label = label,
        displayValue = "${(displayedMin * 100).toInt()}–${(displayedMax * 100).toInt()}%",
        enabled = enabled,
        displayValueColor = when {
            !enabled -> OssmOnSurface.copy(alpha = 0.4f)
            randomRangeActive -> OssmError
            else -> null
        },
        headerAccessory = headerAccessory
    ) {
        // -/+ move the bar touched last (retract or depth); the depth bar by default.
        RangeBarControl(
            start = minValue,
            end = maxValue,
            enabled = enabled,
            activeColor = OssmAccent,
            label = label,
            onRangeChange = onRangeChange,
            onRangeCommit = onRangeCommit,
            modifier = Modifier.weight(1f),
            markers = markers,
            gestureCancellationKey = gestureCancellationKey
        )
    }
}

/**
 * Réorganisation des patterns : appui long sur une ligne pour la saisir, glisser
 * pour la déplacer, relâcher pour la déposer. L'ordre s'applique à la grille.
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface.copy(alpha = if (draggingIndex >= 0) 0.75f else 1f),
        title = { Text(stringResource(R.string.ctl_reorder_title), color = OssmOnSurface, fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.ctl_reorder_hint),
                    color = OssmOnSurface.copy(alpha = 0.6f),
                    fontSize = 12.sp
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isDragging) OssmPrimary.copy(alpha = 0.35f) else OssmGlass
                            )
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
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("☰", color = OssmPrimaryLight, fontSize = 14.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(entry.second, color = OssmOnSurface, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(order.map { it.first }) },
                colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
            ) { Text(stringResource(R.string.ctl_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ctl_cancel), color = OssmOnSurface.copy(alpha = 0.7f)) }
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
        title = { Text(stringResource(R.string.ctl_preset_save_title), color = OssmOnSurface) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.ctl_preset_name_label)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OssmPrimary,
                    unfocusedBorderColor = OssmGlassBorder,
                    focusedTextColor = OssmOnSurface,
                    unfocusedTextColor = OssmOnSurface
                )
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val pattern = currentState.activePattern ?: return@Button
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
                colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
            ) {
                Text(stringResource(R.string.ctl_preset_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ctl_cancel), color = OssmPrimaryLight) }
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
        title = { Text(stringResource(R.string.ctl_abrupt_title), color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.ctl_abrupt_body, stringResource(controlLabel(control)), thresholdPercent),
                    color = OssmOnSurface.copy(alpha = 0.8f)
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
            Button(
                onClick = { onConfirm(skipForSession, neverAskAgain) },
                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
            ) {
                Text(stringResource(R.string.ctl_abrupt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ctl_cancel), color = OssmError)
            }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = OssmOnSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmPrimary,
                checkedTrackColor = OssmPrimary.copy(alpha = 0.35f)
            )
        )
    }
}

/**
 * Bandeau compact affiché pendant une session distante active : maximums (vitesse, profondeur,
 * sensation) fixés par les curseurs locaux, valeurs EN DIRECT envoyées par le site, et avertissement si le maximum de
 * vitesse est à 0 % (le site ne peut alors rien faire bouger).
 */
@Composable
private fun RemoteSessionBanner(
    view: RemoteSessionView,
    patternName: String?,
    compact: Boolean
) {
    val blocked = view.motionBlockedByZeroMaximum
    val accent = if (blocked) OssmWarning else OssmPrimaryLight
    val shape = RoundedCornerShape(10.dp)
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
            .padding(bottom = if (compact) 6.dp else 8.dp)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(1.dp, accent.copy(alpha = 0.6f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("remote_session_banner"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(
                R.string.ui2_remote_banner_limits,
                pct(view.speedMax),
                pct(view.depthMin),
                pct(view.depthMax)
            ) + " · " + stringResource(R.string.ui2_remote_banner_sensation_range, *remoteSensationBannerArgs(view)),
            color = accent,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (blocked) {
            Text(
                stringResource(R.string.ui2_remote_blocked_zero),
                color = OssmWarning,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Text(
            if (live.isEmpty()) {
                stringResource(R.string.ui2_remote_live_waiting)
            } else {
                stringResource(R.string.ui2_remote_live_prefix, live.joinToString(" · "))
            },
            color = OssmOnSurface.copy(alpha = 0.85f),
            fontSize = 12.sp,
            lineHeight = 16.sp
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

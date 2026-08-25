package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
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
import com.ossm.remote.adaptive.AdaptivePace
import com.ossm.remote.adaptive.AdaptiveUiState
import com.ossm.remote.data.repository.AdaptiveSettings
import com.ossm.remote.model.AutoRandomMixablePatterns
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.KnownStrokeEnginePatterns
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.OssmPattern
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.HomeButton
import com.ossm.remote.ui.components.LocalControlPrecisionEnabled
import com.ossm.remote.ui.components.PatternButton
import com.ossm.remote.ui.components.PrecisionScalarSlider
import com.ossm.remote.ui.components.ProgressiveSpeedBar
import com.ossm.remote.ui.components.StopButton
import com.ossm.remote.ui.components.StreamPositionSlider
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurface
import kotlin.math.roundToInt
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.ui.theme.PatternColors
import com.ossm.remote.viewmodel.AdaptiveViewModel
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.GuardedControl
import com.ossm.remote.viewmodel.RandomTarget

@Composable
fun ControlScreen(
    connectionState: BleConnectionState,
    machineState: MachineState,
    uiState: ControlUiState,
    onSpeedCommit: (Float) -> Unit,
    onSpeedLive: (Float) -> Unit,
    onDepthRangeCommit: (Float, Float) -> Unit,
    onDepthLive: (Float, Float) -> Unit,
    onSensationCommit: (Float) -> Unit,
    onSensationLive: (Float) -> Unit,
    onProgressiveMaxCommit: (Float) -> Unit,
    onChaosToggle: (Boolean) -> Unit,
    onStreamPosition: (Int) -> Unit,
    onStreamDrag: (Int) -> Unit,
    onStreamTouchEnd: () -> Unit,
    onRangeWizardStart: () -> Unit,
    onRangeWizardCapture: () -> Unit,
    onRangeWizardCancel: () -> Unit,
    onPattern: (String) -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
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
    listeningLevel: Float,
    onListeningToggle: (Boolean) -> Unit,
    onListeningCeilingCommit: (Float) -> Unit,
    onListeningTargetSpeedChange: (Boolean) -> Unit,
    onListeningTargetDepthChange: (Boolean) -> Unit,
    onLiveInvertToggle: (Boolean) -> Unit,
    onLiveAccelerationChange: (Int) -> Unit
) {
    val connected = connectionState is BleConnectionState.Connected
    val machineBusy = machineState.isHoming || machineState.isPreflight
    val slidersEnabled = connected && !machineBusy
    val adaptiveVm: AdaptiveViewModel = hiltViewModel()
    val adaptiveState by adaptiveVm.adaptiveUiState.collectAsState()
    val adaptiveSettings by adaptiveVm.settings.collectAsState()
    val adaptiveManualAdjustments by adaptiveVm.manualAdjustments.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var patternPickerOpen by remember { mutableStateOf(false) }
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
    val precisionOverrides = remember { mutableStateMapOf<String, Boolean>() }
    val activePattern = uiState.activePattern
    val actionVisibility = controlActionVisibility(activePattern)
    val precisionEnabled = activePattern?.let { isPrecisionEnabled(it, precisionOverrides) } ?: true

    LaunchedEffect(
        uiState.speed,
        uiState.depthMin,
        uiState.depthMax,
        uiState.sensation,
        uiState.activePatternKey
    ) {
        speedDraft = uiState.speed
        depthRangeDraft = uiState.depthMin..uiState.depthMax
        sensationDraft = uiState.sensation
    }

    // Retour au curseur manuel après le mode à l'écoute : tant que le micro pilote la
    // vitesse, le slider est masqué. À la sortie (mode coupé ou cible vitesse décochée),
    // resynchronise le brouillon sur la vitesse manuelle restaurée par le ViewModel,
    // sinon le pouce resterait sur la dernière valeur live du micro.
    LaunchedEffect(uiState.listeningMode, uiState.listeningTargetSpeed) {
        if (!(uiState.listeningMode && uiState.listeningTargetSpeed)) {
            speedDraft = uiState.speed
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OssmBackground, OssmSecondary)))
    ) {
        CompositionLocalProvider(LocalControlPrecisionEnabled provides precisionEnabled) {
        CompactControlScaffold(
            showActionRail = actionVisibility.showStop || actionVisibility.showPause,
            actionRail = { landscape ->
                CompactActionRail(
                    landscape = landscape,
                    showStop = actionVisibility.showStop,
                    showPause = actionVisibility.showPause,
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
            if (!connected) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmWarning) {
                    Text(
                        stringResource(R.string.ctl_connect_prompt),
                        color = OssmWarning,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (machineBusy) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmAccent) {
                    Text(
                        "Calibration en cours (${machineState.displayLabel}) — attendez la fin",
                        color = OssmAccent,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (machineState.isError) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
                    Text(
                        machineState.displayLabel,
                        color = OssmError,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            val liveControlActive = uiState.rangeWizard == null &&
                activePattern?.mode == PatternControlMode.STREAMING
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (liveControlActive) Modifier.weight(1f) else Modifier)
                    .testTag("control_card"),
                padding = if (compactLandscape) 10.dp else 16.dp
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
                        color = OssmPrimaryLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                    if (activePattern != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsGearButton(onInfoClick = { showInfoDialog = true })
                            if (connected && actionVisibility.showSave) {
                                IconButton(
                                    onClick = { showSaveDialog = true },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(OssmGlass)
                                        .border(1.dp, OssmGlassBorder, RoundedCornerShape(10.dp))
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

                when {
                    uiState.rangeWizard != null -> {
                        val wiz = uiState.rangeWizard
                        Text(
                            if (wiz.step == 1)
                                "Étape 1/2 — Place la machine au FOND désiré avec le slider, puis appuie sur « Définir le fond »."
                            else
                                "Étape 2/2 — Place maintenant le point de RETRAIT, puis appuie sur « Définir le retrait ».",
                            color = OssmOnSurface,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        if (!uiState.streamingReady) {
                            Text(
                                buildString {
                                    append("Préparation… Machine : ${machineState.state}")
                                    if (machineState.isPreflight) {
                                        append("\n→ Baisse le bouton de vitesse physique à 0.")
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
                            onTouchEnd = onStreamTouchEnd,
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
                                    if (wiz.step == 1) "Définir le fond" else "Définir le retrait",
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
                                    "Annuler",
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        if (wiz.step == 2 && wiz.capturedMax != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Fond défini : ${(wiz.capturedMax * 100).toInt()} %",
                                color = OssmConnected,
                                fontSize = 12.sp
                            )
                        }
                    }
                    activePattern == null -> {
                        Text(
                            "Selectionnez un pattern pour commencer.",
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                    activePattern.mode == PatternControlMode.LAUNCH_ONLY -> {
                        Text(
                            "Pattern lancé — aucun paramètre à ajuster.",
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                    activePattern.mode == PatternControlMode.STREAMING -> {
                        if (!uiState.streamingReady) {
                            Text(
                                buildString {
                                    append(stringResource(R.string.ctl_live_preparing))
                                    append("\nMachine : ${machineState.state}")
                                    machineState.stroke?.let { append("  stroke=$it") }
                                    machineState.depth?.let { append("  depth=$it") }
                                    machineState.speed?.let { append("  speed=$it") }
                                    if (machineState.isPreflight) {
                                        append("\n→ Baisse le bouton de vitesse physique à 0 pour entrer dans le mode.")
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
                            onTouchEnd = onStreamTouchEnd,
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
                            maxValue = uiState.progressiveMaxSpeed,
                            enabled = slidersEnabled,
                            onMaxChange = onProgressiveMaxCommit
                        )
                        Spacer(Modifier.height(12.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            listeningDepthCurrent = uiState.listeningDepthCurrent,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
                                onDepthRangeCommit(min, max)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        CenteredSensationSlider(
                            value = sensationDraft,
                            enabled = slidersEnabled,
                            onValueChange = { value -> sensationDraft = value; onSensationLive(value) },
                            onValueCommit = { value -> sensationDraft = value; onSensationCommit(value) }
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
                                    "Mode aléatoire au max",
                                    color = OssmOnSurface,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Au plafond, coups variés et imprévisibles",
                                    color = OssmOnSurface.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                    activePattern.mode == PatternControlMode.AUTO_RANDOM -> {
                        Text(
                            "Choisis les modes a melanger, fixe tes limites, puis demarre le mix aleatoire.",
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Text(
                            "MODES A MELANGER",
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
                            "VITESSE MAX",
                            color = OssmPrimaryLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        ControlSlider(
                            label = "Plafond du mix",
                            value = uiState.autoMaxSpeed,
                            onValueChange = onAutoMaxSpeedChange,
                            onValueCommit = onAutoMaxSpeedChange,
                            enabled = slidersEnabled,
                            activeColor = OssmPrimary
                        )
                        Spacer(Modifier.height(12.dp))
                        ControlSlider(
                            label = "Intensite max",
                            value = uiState.autoIntensityCap,
                            onValueChange = onAutoIntensityCapChange,
                            onValueCommit = onAutoIntensityCapChange,
                            enabled = slidersEnabled,
                            activeColor = OssmAccent
                        )
                        Spacer(Modifier.height(12.dp))
                        ControlSlider(
                            label = "Randomness",
                            value = uiState.autoRandomness,
                            onValueChange = onAutoRandomnessChange,
                            onValueCommit = onAutoRandomnessChange,
                            enabled = slidersEnabled,
                            activeColor = OssmWarning
                        )
                        Spacer(Modifier.height(12.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            listeningDepthCurrent = uiState.listeningDepthCurrent,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
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
                                    "Intensite ${(uiState.autoIntensity * 100).toInt()}%",
                                    color = OssmConnected,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Le mix monte progressivement en intensite sans depasser tes limites.",
                                    color = OssmOnSurface.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            ControlSlider(
                                label = "Intensite de depart",
                                value = uiState.autoInitialIntensity,
                                onValueChange = onAutoInitialIntensityChange,
                                onValueCommit = onAutoInitialIntensityChange,
                                enabled = slidersEnabled,
                                activeColor = OssmConnected
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
                    activePattern.mode == PatternControlMode.ADAPTIVE -> {
                        AdaptiveModeContent(
                            adaptiveState = adaptiveState,
                            settings = adaptiveSettings,
                            manualAdjustments = adaptiveManualAdjustments,
                            connected = connected,
                            onEnabledChange = adaptiveVm::setEnabled,
                            onPaceChange = adaptiveVm::setPace,
                            onDiversityChange = adaptiveVm::setDiversity,
                            onStart = adaptiveVm::startAdaptive,
                            onStop = adaptiveVm::stopAdaptive
                        )
                    }
                    else -> {
                        // Simple Stroke et Teasing & Pounding exposent le mode aléatoire /
                        // piston : une case « Aléatoire » collée sous chaque curseur.
                        val isRandomCapable = activePattern.key == "teasingPounding" ||
                            activePattern.key == "simpleStroke"

                        if (uiState.listeningMode && uiState.listeningTargetSpeed) {
                            // Mode à l'écoute : la vitesse est PILOTÉE par le VOLUME du
                            // micro. Deux barres, comme le mode Progressif : plafond ROUGE
                            // réglable (max que le micro peut atteindre) + barre BLEUE live
                            // = vitesse réelle (volume × plafond).
                            ProgressiveSpeedBar(
                                currentValue = uiState.speed,
                                maxValue = uiState.listeningCeiling,
                                enabled = slidersEnabled,
                                onMaxChange = onListeningCeilingCommit,
                                label = stringResource(R.string.ctl_speed_listening),
                                fillColor = OssmAccent
                            )
                        } else {
                            ControlSlider(
                                label = stringResource(R.string.ctl_speed),
                                value = speedDraft,
                                onValueChange = { speedDraft = it; onSpeedLive(it) },
                                onValueCommit = { committed ->
                                    speedDraft = committed
                                    onSpeedCommit(committed)
                                },
                                enabled = slidersEnabled,
                                activeColor = OssmPrimary,
                                currentMarker = uiState.randomSpeedCurrent,
                                displayValue = "${((uiState.randomSpeedCurrent ?: speedDraft) * 100).toInt()}%",
                                displayValueColor = if (uiState.randomSpeedCurrent != null) OssmError else null
                            )
                        }
                        if (isRandomCapable) {
                            RandomModeRow(
                                label = stringResource(R.string.ctl_random_speed),
                                checked = uiState.randSpeed,
                                onCheckedChange = { onRandomToggle(RandomTarget.SPEED, it) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            positionFraction = positionFraction,
                            listeningDepthCurrent = uiState.listeningDepthCurrent,
                            randomMinValue = uiState.randomDepthMinCurrent,
                            randomMaxValue = uiState.randomDepthMaxCurrent,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
                                onDepthRangeCommit(min, max)
                            }
                        )
                        if (isRandomCapable) {
                            // Une SEULE case pour la profondeur : le piston se promène
                            // entre le min et le max réglés.
                            RandomModeRow(
                                label = stringResource(R.string.ctl_random_depth),
                                checked = uiState.randDepthMin || uiState.randDepthMax,
                                onCheckedChange = { onDepthRandomToggle(it) }
                            )
                        }
                        if (shouldShowSensationSlider(activePattern)) {
                            Spacer(Modifier.height(12.dp))
                            CenteredSensationSlider(
                                value = sensationDraft,
                                enabled = slidersEnabled,
                                onValueChange = { value -> sensationDraft = value; onSensationLive(value) },
                                onValueCommit = { value -> sensationDraft = value; onSensationCommit(value) }
                            )
                        }
                        // Pied de section : mode à l'écoute (micro).
                        if (isRandomCapable) {
                            Spacer(Modifier.height(16.dp))
                            ListeningModeFooter(
                            enabled = uiState.listeningMode,
                            level = listeningLevel,
                            onToggle = onListeningToggle,
                            speedTargetEnabled = uiState.listeningTargetSpeed,
                            depthTargetEnabled = uiState.listeningTargetDepth,
                            onSpeedTargetChange = onListeningTargetSpeedChange,
                            onDepthTargetChange = onListeningTargetDepthChange
                            )
                        }
                    }
                }
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
                activePattern?.let { precisionOverrides[it.key] = enabled }
            },
            showLiveInvert = shouldShowLiveInvertSetting(activePattern),
            liveInvertChecked = uiState.liveInvert,
            onLiveInvertCheckedChange = onLiveInvertToggle,
            showLiveAcceleration = shouldShowLiveAccelerationSetting(activePattern),
            liveAccelerationPercent = uiState.liveAccelerationPercent,
            onLiveAccelerationChange = onLiveAccelerationChange,
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
}

@Composable
internal fun CompactControlScaffold(
    modifier: Modifier = Modifier,
    showActionRail: Boolean = true,
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
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(112.dp)
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
                        horizontal = if (landscape) 8.dp else 16.dp,
                        vertical = if (landscape) 6.dp else 8.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showActionRail) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
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

@Composable
private fun CompactActionRail(
    landscape: Boolean,
    showStop: Boolean,
    showPause: Boolean,
    isPaused: Boolean,
    pauseEnabled: Boolean,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val actions: @Composable () -> Unit = {
        if (showStop) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .testTag("control_stop"),
                contentAlignment = Alignment.Center
            ) {
                StopButton(onClick = onStop, modifier = Modifier.size(110.dp))
            }
        }
        if (showPause) {
            PausePlayButton(
                isPaused = isPaused,
                enabled = pauseEnabled,
                onPause = onPause,
                onResume = onResume
            )
        }
    }
    if (landscape) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            actions()
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions()
        }
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
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OssmGlass)
            .border(1.dp, OssmGlassBorder, RoundedCornerShape(10.dp))
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
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ctl_patterns),
                        color = OssmPrimaryLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        stringResource(R.string.ctl_patterns_hint),
                        color = OssmOnSurface.copy(alpha = 0.62f),
                        fontSize = 11.sp
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rows.forEach { rowPatterns ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowPatterns.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        }
    }
}

internal fun shouldShowSensationSlider(pattern: OssmPattern): Boolean =
    pattern.mode == PatternControlMode.STROKE_ENGINE && pattern.key != "simpleStroke"

internal fun controlHeaderTitle(pattern: OssmPattern?): String = pattern?.name.orEmpty()

internal fun shouldShowLiveInvertSetting(pattern: OssmPattern?): Boolean =
    pattern?.mode == PatternControlMode.STREAMING

internal fun shouldShowLiveAccelerationSetting(pattern: OssmPattern?): Boolean =
    pattern?.mode == PatternControlMode.STREAMING

internal data class ControlActionVisibility(
    val showStop: Boolean,
    val showPause: Boolean,
    val showSave: Boolean
)

internal fun controlActionVisibility(pattern: OssmPattern?): ControlActionVisibility {
    val isLive = pattern?.mode == PatternControlMode.STREAMING
    return ControlActionVisibility(
        showStop = !isLive,
        showPause = !isLive,
        showSave = pattern != null && !isLive
    )
}

internal fun isPrecisionEnabled(
    pattern: OssmPattern,
    overrides: Map<String, Boolean>
): Boolean = overrides[pattern.key] ?: (pattern.mode != PatternControlMode.STREAMING)

@Composable
private fun CenteredSensationSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit
) {
    ControlSlider(
        label = stringResource(R.string.ctl_sensation),
        value = value,
        onValueChange = onValueChange,
        onValueCommit = onValueCommit,
        enabled = enabled,
        activeColor = OssmAccent
    )
}

@Composable
private fun SettingsGearButton(
    onInfoClick: () -> Unit
) {
    IconButton(
        onClick = onInfoClick,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OssmGlass)
            .border(1.dp, OssmGlassBorder, RoundedCornerShape(10.dp))
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
    onResume: () -> Unit
) {
    val activeColor = if (isPaused) OssmConnected else OssmWarning
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(80.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (enabled) {
                            listOf(activeColor, activeColor.copy(alpha = 0.6f))
                        } else {
                            listOf(OssmOnSurface.copy(0.15f), OssmOnSurface.copy(0.08f))
                        }
                    )
                )
                .border(2.dp, activeColor.copy(alpha = if (enabled) 0.6f else 0.15f), CircleShape)
                .pointerInput(isPaused, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { if (isPaused) onResume() else onPause() }
                }
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPaused) "Reprendre" else "Pause",
                tint = androidx.compose.ui.graphics.Color.White,
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
                color = OssmSurface.copy(alpha = 0.98f),
                tonalElevation = 10.dp,
                shadowElevation = 18.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, OssmGlassBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
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
                        IconButton(onClick = onDismiss) {
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
                                color = OssmOnSurface.copy(alpha = 0.8f),
                                fontSize = 13.sp
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
                                color = OssmSecondary.copy(alpha = 0.98f),
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
                        colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingLabelWithInfo(
            title = title,
            explanation = explanation,
            onExplanationVisibilityChange = onExplanationVisibilityChange,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmPrimary,
                checkedTrackColor = OssmPrimary.copy(alpha = 0.35f)
            )
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(OssmGlass)
            .border(1.dp, OssmGlassBorder, CircleShape)
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
                        onVisibilityChange(false)
                    }
                }
            }
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = stringResource(R.string.ctl_hold_for_info),
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

@Composable
private fun ListeningModeFooter(
    enabled: Boolean,
    level: Float,
    onToggle: (Boolean) -> Unit,
    speedTargetEnabled: Boolean,
    depthTargetEnabled: Boolean,
    onSpeedTargetChange: (Boolean) -> Unit,
    onDepthTargetChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = OssmGlassBorder)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ctl_listening_title),
                    color = OssmOnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.ctl_listening_hint),
                    color = OssmOnSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OssmAccent,
                    checkedTrackColor = OssmAccent.copy(alpha = 0.4f)
                )
            )
        }
        // Témoin de niveau sonore (visible seulement quand le mode est actif).
        if (enabled) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(OssmOnSurface.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(OssmAccent)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Le micro agit sur",
                color = OssmPrimaryLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            SliderGuardRow(
                title = "Vitesse",
                description = "Le volume du son pilote la vitesse.",
                checked = speedTargetEnabled,
                onCheckedChange = onSpeedTargetChange
            )
            SliderGuardRow(
                title = "Profondeur",
                description = "La fréquence des pics sonores pilote la profondeur.",
                checked = depthTargetEnabled,
                onCheckedChange = onDepthTargetChange
            )
        }
    }
}

@Composable
private fun RandomModeRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = OssmAccent,
                uncheckedColor = OssmOnSurface.copy(alpha = 0.5f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = OssmOnSurface,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
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
        title = { Text("Verifier tes limites", color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Tu vas lancer Auto Random avec $selectedCount mode(s) selectionne(s).",
                    color = OssmOnSurface
                )
                Text(
                    "Vitesse max: $maxSpeedPercent%\nProfondeur: $depthMinPercent% - $depthMaxPercent%",
                    color = OssmOnSurface.copy(alpha = 0.8f)
                )
                Text(
                    "Assure-toi que ces limites sont bien celles que tu ne veux jamais depasser avant le depart.",
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
                Text("Annuler", color = OssmPrimaryLight)
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
        modifier = Modifier.fillMaxWidth(),
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
            onCheckedChange = onCheckedChange,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmPrimary,
                checkedTrackColor = OssmPrimary.copy(alpha = 0.35f)
            )
        )
    }
}

@Composable
private fun DepthRangeEditor(
    minValue: Float,
    maxValue: Float,
    enabled: Boolean,
    onRangeChange: (Float, Float) -> Unit,
    onRangeCommit: (Float, Float) -> Unit,
    positionFraction: Float? = null,
    listeningDepthCurrent: Float? = null,
    randomMinValue: Float? = null,
    randomMaxValue: Float? = null
) {
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    // Dernière barre touchée : true = FOND (max, droite), false = RETRAIT (min, gauche).
    // Les boutons +/- agissent sur CETTE barre. Défaut = fond (comportement d'avant).
    var selectedIsMax by remember { mutableStateOf(true) }
    val displayedMin = randomMinValue ?: minValue
    val displayedMax = randomMaxValue ?: maxValue
    val randomRangeActive = randomMinValue != null || randomMaxValue != null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.ctl_depth),
                color = OssmPrimaryLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                "${(displayedMin * 100).toInt()}% - ${(displayedMax * 100).toInt()}%",
                color = when {
                    !enabled -> OssmOnSurface.copy(alpha = 0.4f)
                    randomRangeActive -> OssmError
                    else -> OssmOnSurface
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // −/+ agissent sur la DERNIÈRE barre touchée (retrait ou fond), pas de 1 %.
            FilledTonalIconButton(
                onClick = {
                    if (selectedIsMax) {
                        onRangeCommit(minValue, (maxValue - 0.01f).coerceIn(minValue, 1f))
                    } else {
                        onRangeCommit((minValue - 0.01f).coerceIn(0f, maxValue), maxValue)
                    }
                },
                enabled = enabled,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = OssmAccent.copy(alpha = 0.18f),
                    contentColor = OssmAccent,
                    disabledContainerColor = OssmAccent.copy(0.06f),
                    disabledContentColor = OssmAccent.copy(0.3f)
                )
            ) {
                Text("−", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { sliderWidthPx = it.width }
                    .pointerInput(enabled, minValue, maxValue, sliderWidthPx) {
                        detectTapGestures { offset ->
                            if (!enabled || sliderWidthPx <= 0) return@detectTapGestures
                            val fraction = (offset.x / sliderWidthPx.toFloat()).coerceIn(0f, 1f)
                            val tappedValue = fraction
                            val tappedMin = kotlin.math.abs(tappedValue - minValue) <= kotlin.math.abs(tappedValue - maxValue)
                            selectedIsMax = !tappedMin
                        }
                    }
            ) {
                // Surbrillance discrète du curseur SÉLECTIONNÉ (celui que +/- ajustent).
                if (sliderWidthPx > 0) {
                    val selFrac = (if (selectedIsMax) maxValue else minValue).coerceIn(0f, 1f)
                    val hx = with(LocalDensity.current) { (sliderWidthPx * selFrac).toDp() }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = hx - 15.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(OssmAccent.copy(alpha = 0.22f))
                    )
                }
                RangeSlider(
                    value = minValue..maxValue,
                    onValueChange = { range ->
                        // Détecte le curseur déplacé pour cibler les boutons +/-.
                        val dMin = kotlin.math.abs(range.start - minValue)
                        val dMax = kotlin.math.abs(range.endInclusive - maxValue)
                        if (dMin > 0.0001f || dMax > 0.0001f) selectedIsMax = dMax >= dMin
                        onRangeChange(range.start, range.endInclusive)
                    },
                    onValueChangeFinished = {
                        onRangeCommit(minValue, maxValue)
                    },
                    valueRange = 0f..1f,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = OssmAccent,
                        activeTrackColor = OssmAccent,
                        inactiveTrackColor = OssmAccent.copy(alpha = 0.25f)
                    )
                )
                if (positionFraction != null && sliderWidthPx > 0) {
                    val xDp = with(LocalDensity.current) {
                        (sliderWidthPx * positionFraction.coerceIn(0f, 1f)).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - 1.5.dp)
                            .width(3.dp)
                            .height(48.dp)
                        .background(OssmError.copy(alpha = 0.9f))
                    )
                }
                listeningDepthCurrent?.let { depth ->
                    val xDp = with(LocalDensity.current) {
                        (sliderWidthPx * depth.coerceIn(0f, 1f)).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - 1.5.dp)
                            .width(3.dp)
                            .height(48.dp)
                            .background(OssmError)
                    )
                }
                randomMinValue?.let { randomValue ->
                    val xDp = with(LocalDensity.current) {
                        (sliderWidthPx * randomValue.coerceIn(0f, 1f)).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - 1.5.dp)
                            .width(3.dp)
                            .height(48.dp)
                            .background(OssmError.copy(alpha = 0.95f))
                    )
                }
                randomMaxValue?.let { randomValue ->
                    val xDp = with(LocalDensity.current) {
                        (sliderWidthPx * randomValue.coerceIn(0f, 1f)).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - 1.5.dp)
                            .width(3.dp)
                            .height(48.dp)
                            .background(OssmError.copy(alpha = 0.95f))
                    )
                }
            }

            FilledTonalIconButton(
                onClick = {
                    if (selectedIsMax) {
                        onRangeCommit(minValue, (maxValue + 0.01f).coerceIn(minValue, 1f))
                    } else {
                        onRangeCommit((minValue + 0.01f).coerceIn(0f, maxValue), maxValue)
                    }
                },
                enabled = enabled,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = OssmAccent.copy(alpha = 0.18f),
                    contentColor = OssmAccent,
                    disabledContainerColor = OssmAccent.copy(0.06f),
                    disabledContentColor = OssmAccent.copy(0.3f)
                )
            ) {
                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
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
            TextButton(onClick = onDismiss) { Text("Annuler", color = OssmOnSurface.copy(alpha = 0.7f)) }
        }
    )
}

@Composable
private fun SavePresetDialog(
    currentState: ControlUiState,
    onDismiss: () -> Unit,
    onSave: (Preset) -> Unit
) {
    var name by remember { mutableStateOf("Mon Profil") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        title = { Text("Sauvegarder le profil", color = OssmOnSurface) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom du profil") },
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
                            name = name.ifBlank { "Profil" },
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
                Text("Sauvegarder")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = OssmPrimaryLight) }
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
        title = { Text("Changement brusque detecte", color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Vous etes sur de vouloir changer autant d'un coup sur ${controlLabel(control)}? La variation depasse ${thresholdPercent} %. Cette protection evite un mouvement soudain si le telephone ou le slider est accroche.",
                    color = OssmOnSurface.copy(alpha = 0.8f)
                )
                HorizontalDivider(color = OssmGlassBorder)
                GuardCheckboxRow(
                    label = "Ne pas me le rappeler pour cette session",
                    checked = skipForSession,
                    onCheckedChange = { skipForSession = it }
                )
                GuardCheckboxRow(
                    label = "Ne plus jamais me le redemander",
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
                Text("Oui, je suis sur")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = OssmError)
            }
        }
    )
}

private fun controlLabel(control: GuardedControl): String = when (control) {
    GuardedControl.SPEED -> "la vitesse"
    GuardedControl.DEPTH -> "la profondeur"
}

@Composable
private fun GuardCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            onCheckedChange = onCheckedChange,
            modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmPrimary,
                checkedTrackColor = OssmPrimary.copy(alpha = 0.35f)
            )
        )
    }
}

@Composable
private fun AdaptiveModeContent(
    adaptiveState: AdaptiveUiState,
    settings: AdaptiveSettings,
    manualAdjustments: Long,
    connected: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPaceChange: (AdaptivePace) -> Unit,
    onDiversityChange: (Float) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    AdaptiveStatusCard(
        state = adaptiveState,
        manualAdjustments = manualAdjustments
    )
    Spacer(Modifier.height(10.dp))
    AdaptiveSettingsCard(
        settings = settings,
        running = adaptiveState.running,
        enabled = connected,
        onEnabledChange = onEnabledChange,
        onPaceChange = onPaceChange,
        onDiversityChange = onDiversityChange,
        onStart = onStart,
        onStop = onStop
    )
}

@Composable
private fun AdaptiveStatusCard(
    state: AdaptiveUiState,
    manualAdjustments: Long
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmPrimary) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (state.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = stringResource(
                    if (state.locked) R.string.adapt_locked else R.string.adapt_unlocked
                ),
                tint = if (state.locked) OssmWarning else OssmConnected,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (state.locked) R.string.adapt_locked else R.string.adapt_unlocked
                    ),
                    color = if (state.locked) OssmWarning else OssmConnected,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(
                        if (state.locked) R.string.adapt_locked_hint else R.string.adapt_unlocked_hint
                    ),
                    color = OssmOnSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            Text(
                state.firmwareLevel.name,
                color = OssmAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        AdaptiveBar(
            label = stringResource(R.string.adapt_progression),
            percent = state.progressionPercent,
            fillColor = OssmPrimary
        )

        Spacer(Modifier.height(10.dp))
        AdaptiveBar(
            label = stringResource(R.string.adapt_confidence),
            percent = state.confidencePercent,
            fillColor = OssmAccent,
            valueColor = confidenceColor(state.confidencePercent)
        )

        Spacer(Modifier.height(14.dp))
        val summary = state.learningSummary
        AdaptiveStatRow(
            label = stringResource(R.string.adapt_sessions),
            value = "${summary.sessionsCompleted}"
        )
        AdaptiveStatRow(
            label = stringResource(R.string.adapt_total_active),
            value = formatDuration(summary.totalActiveMillis)
        )
        AdaptiveStatRow(
            label = stringResource(R.string.adapt_distinct_days),
            value = "${summary.distinctDays}"
        )
        AdaptiveStatRow(
            label = stringResource(R.string.adapt_manual_adjustments),
            value = "$manualAdjustments"
        )
        summary.topPatternKey?.let { topKey ->
            AdaptiveStatRow(
                label = stringResource(R.string.adapt_top_pattern),
                value = KnownStrokeEnginePatterns.firstOrNull { it.key == topKey }?.name ?: topKey
            )
        }
    }
}

@Composable
private fun AdaptiveBar(
    label: String,
    percent: Int,
    fillColor: Color,
    valueColor: Color = OssmOnSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = OssmPrimaryLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$percent%",
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(OssmOnSurface.copy(alpha = 0.12f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fillColor)
        )
    }
}

@Composable
private fun AdaptiveStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = OssmOnSurface.copy(alpha = 0.65f),
            fontSize = 13.sp
        )
        Text(
            value,
            color = OssmOnSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Spacer(Modifier.height(4.dp))
}

private fun confidenceColor(percent: Int): Color = when {
    percent >= 60 -> OssmConnected
    percent >= 30 -> OssmWarning
    else -> OssmError
}

@Composable
private fun AdaptiveSettingsCard(
    settings: AdaptiveSettings,
    running: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onPaceChange: (AdaptivePace) -> Unit,
    onDiversityChange: (Float) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmAccent) {
        Text(
            stringResource(R.string.adapt_settings_title),
            color = OssmPrimaryLight,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.adapt_enabled),
                    color = OssmOnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.adapt_enabled_hint),
                    color = OssmOnSurface.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = onEnabledChange,
                enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OssmAccent,
                    checkedTrackColor = OssmAccent.copy(alpha = 0.4f),
                    uncheckedThumbColor = OssmOnSurface.copy(alpha = 0.5f),
                    uncheckedTrackColor = OssmOnSurface.copy(alpha = 0.12f)
                )
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.adapt_pace),
            color = OssmPrimaryLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        PaceSelector(
            selected = settings.pace,
            enabled = enabled,
            onPaceChange = onPaceChange
        )
        Spacer(Modifier.height(12.dp))
        ControlSlider(
            label = stringResource(R.string.adapt_diversity),
            value = settings.diversity,
            onValueChange = onDiversityChange,
            onValueCommit = onDiversityChange,
            enabled = enabled,
            activeColor = OssmAccent,
            displayValue = "${(settings.diversity * 100).toInt()}%"
        )
        Text(
            stringResource(R.string.adapt_diversity_hint),
            color = OssmOnSurface.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
        if (running) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.adapt_running_hint),
                color = OssmConnected,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        if (running) {
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
            ) {
                Text(
                    stringResource(R.string.adapt_stop),
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        } else {
            Button(
                onClick = onStart,
                enabled = enabled && settings.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
            ) {
                Text(
                    stringResource(R.string.adapt_start),
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun PaceSelector(
    selected: AdaptivePace,
    enabled: Boolean,
    onPaceChange: (AdaptivePace) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaceOption(
            label = stringResource(R.string.adapt_pace_calm),
            selected = selected == AdaptivePace.CALME,
            enabled = enabled,
            onClick = { onPaceChange(AdaptivePace.CALME) },
            modifier = Modifier.weight(1f)
        )
        PaceOption(
            label = stringResource(R.string.adapt_pace_moderate),
            selected = selected == AdaptivePace.MODERE,
            enabled = enabled,
            onClick = { onPaceChange(AdaptivePace.MODERE) },
            modifier = Modifier.weight(1f)
        )
        PaceOption(
            label = stringResource(R.string.adapt_pace_dynamic),
            selected = selected == AdaptivePace.DYNAMIQUE,
            enabled = enabled,
            onClick = { onPaceChange(AdaptivePace.DYNAMIQUE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PaceOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> OssmOnSurface.copy(alpha = 0.06f)
                    selected -> OssmPrimary.copy(alpha = 0.28f)
                    else -> OssmGlass
                }
            )
            .border(
                1.dp,
                if (selected) OssmPrimary else OssmGlassBorder,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                !enabled -> OssmOnSurface.copy(alpha = 0.35f)
                selected -> OssmPrimaryLight
                else -> OssmOnSurface.copy(alpha = 0.7f)
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

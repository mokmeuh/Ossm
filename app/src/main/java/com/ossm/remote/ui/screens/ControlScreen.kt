package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.model.AutoRandomMixablePatterns
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.MachineState
import com.ossm.remote.model.PatternControlMode
import com.ossm.remote.model.Preset
import com.ossm.remote.ui.components.BleStatusIndicator
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.HomeButton
import com.ossm.remote.ui.components.LiveStreamPad
import com.ossm.remote.ui.components.PatternButton
import com.ossm.remote.ui.components.ProgressiveSpeedBar
import com.ossm.remote.ui.components.StopButton
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.ui.theme.PatternColors
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.LiveRecState
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
    onStreamTarget: (Int) -> Unit,
    onStreamActive: (Boolean) -> Unit,
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
    onConfirmPendingChange: (Boolean, Boolean) -> Unit,
    onDismissPendingChange: () -> Unit,
    onPatternOrderSave: (List<String>) -> Unit,
    onLiveRecordToggle: () -> Unit,
    onRandomToggle: (RandomTarget, Boolean) -> Unit,
    onDepthRandomToggle: (Boolean) -> Unit,
    listeningLevel: Float,
    onListeningToggle: (Boolean) -> Unit,
    onLiveInvertToggle: (Boolean) -> Unit
) {
    val connected = connectionState is BleConnectionState.Connected
    val machineBusy = machineState.isHoming || machineState.isPreflight
    val slidersEnabled = connected && !machineBusy
    var showSaveDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    // Boîte Patterns repliable en bulle flottante déplaçable (jamais sur STOP).
    var patternsCollapsed by remember { mutableStateOf(false) }
    var bubbleX by remember { mutableFloatStateOf(24f) }
    var bubbleY by remember { mutableFloatStateOf(500f) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var stopRect by remember { mutableStateOf<Rect?>(null) }
    // Position réelle → fraction 0..1 de la course. Échelle auto-calibrée sur la
    // PLAGE observée (min..max) ; orientée pour que 0 % = home (la borne la plus
    // proche de zéro). Masquée tant que la plage vue est < 5 mm.
    var minPosMm by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var maxPosMm by remember { mutableFloatStateOf(-Float.MAX_VALUE) }
    val positionFraction: Float? = machineState.positionMm?.let { raw ->
        if (raw < minPosMm) minPosMm = raw
        if (raw > maxPosMm) maxPosMm = raw
        val range = maxPosMm - minPosMm
        if (range < 5f) null else {
            val f = ((raw - minPosMm) / range).coerceIn(0f, 1f)
            if (kotlin.math.abs(maxPosMm) <= kotlin.math.abs(minPosMm)) 1f - f else f
        }
    }
    var showInfoDialog by remember { mutableStateOf(false) }
    var speedDraft by remember { mutableFloatStateOf(uiState.speed) }
    var depthRangeDraft by remember { mutableStateOf(uiState.depthMin..uiState.depthMax) }
    var sensationDraft by remember { mutableFloatStateOf(uiState.sensation) }

    LaunchedEffect(uiState.speed, uiState.depthMin, uiState.depthMax, uiState.sensation, uiState.activePatternKey) {
        speedDraft = uiState.speed
        depthRangeDraft = uiState.depthMin..uiState.depthMax
        sensationDraft = uiState.sensation
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OssmBackground, OssmSecondary)))
            .onSizeChanged { rootSize = it }
    ) {
        // Quand un pad tactile vertical est affiché (Live ou assistant de plage), on
        // désactive le scroll de la page : sinon il vole les glissements du doigt.
        val padOnScreen = uiState.rangeWizard != null ||
            uiState.activePattern?.mode == PatternControlMode.STREAMING
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (padOnScreen) Modifier
                    else Modifier.verticalScroll(rememberScrollState())
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BleStatusIndicator(state = connectionState)
                if (connected && uiState.activePattern != null) {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Default.Save, "Sauvegarder profil", tint = OssmPrimaryLight)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecuritySquare(
                    speedChecked = uiState.speedGuard.enabled,
                    onSpeedCheckedChange = onSpeedGuardEnabledChange,
                    depthChecked = uiState.depthGuard.enabled,
                    onDepthCheckedChange = onDepthGuardEnabledChange,
                    onInfoClick = { showInfoDialog = true }
                )
                Box(Modifier.onGloballyPositioned { stopRect = it.boundsInRoot() }) {
                    StopButton(onClick = onStop)
                }
                PausePlayButton(
                    isPaused = uiState.isPaused,
                    enabled = connected
                        && uiState.activePattern?.mode != PatternControlMode.STREAMING
                        && (uiState.isRunning || uiState.isPaused),
                    onPause = onPause,
                    onResume = onResume
                )
            }

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

            if (!patternsCollapsed) GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Touche le titre pour replier la boîte en bulle flottante.
                    Text(
                        stringResource(R.string.ctl_patterns),
                        color = OssmPrimaryLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.clickable { patternsCollapsed = true }
                    )
                    IconButton(onClick = { showReorderDialog = true }) {
                        Text("+", color = OssmAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (uiState.availablePatterns.isEmpty()) {
                    Text(
                        stringResource(R.string.ctl_no_patterns),
                        color = OssmOnSurface.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                } else {
                    // Grille 2 lignes, ~4 colonnes visibles, défilement horizontal.
                    val ordered = uiState.orderedPatterns
                    val columns = ordered.chunked(2)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        columns.forEach { col ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                col.forEach { pattern ->
                                    val index = ordered.indexOf(pattern)
                                    PatternButton(
                                        label = pattern.name,
                                        color = PatternColors[index % PatternColors.size],
                                        isActive = uiState.activePatternKey == pattern.key,
                                        onClick = { onPattern(pattern.key) },
                                        enabled = connected,
                                        modifier = Modifier.width(84.dp)
                                    )
                                }
                                if (col.size == 1) Spacer(Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "CONTRÔLE" + (uiState.activePattern?.let { " — ${it.name}" } ?: ""),
                    color = OssmPrimaryLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                val activePattern = uiState.activePattern
                when {
                    uiState.rangeWizard != null -> {
                        val wiz = uiState.rangeWizard
                        Text(
                            if (wiz.step == 1)
                                "Étape 1/2 — Glisse le pad pour amener la machine au FOND désiré, puis appuie sur « Définir le fond »."
                            else
                                "Étape 2/2 — Glisse maintenant jusqu'au point de RETRAIT, puis appuie sur « Définir le retrait ».",
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
                        LiveStreamPad(
                            enabled = slidersEnabled && uiState.streamingReady,
                            onTarget = onStreamTarget,
                            onActive = onStreamActive
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onRangeWizardCapture,
                                enabled = slidersEnabled && uiState.streamingReady,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
                            ) {
                                Text(if (wiz.step == 1) "Définir le fond" else "Définir le retrait")
                            }
                            Button(
                                onClick = onRangeWizardCancel,
                                enabled = connected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
                            ) {
                                Text("Annuler")
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
                        Text(
                            if (uiState.streamingReady)
                                stringResource(R.string.ctl_live_hint_ready)
                            else
                                stringResource(R.string.ctl_live_preparing),
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        if (!uiState.streamingReady) {
                            Text(
                                buildString {
                                    append("Machine : ${machineState.state}")
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
                        LiveStreamPad(
                            enabled = slidersEnabled && uiState.streamingReady,
                            onTarget = onStreamTarget,
                            onActive = onStreamActive
                        )
                        Spacer(Modifier.height(10.dp))
                        // Sens du Live réglable : si le chariot part dans le mauvais sens
                        // (ou cogne au repos), bascule ceci — pas besoin de rebuild.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.ctl_live_invert),
                                    color = OssmOnSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.ctl_live_invert_hint),
                                    color = OssmOnSurface.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = uiState.liveInvert,
                                onCheckedChange = onLiveInvertToggle,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OssmAccent,
                                    checkedTrackColor = OssmAccent.copy(alpha = 0.4f)
                                )
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        val recLabel = when (uiState.liveRec) {
                            LiveRecState.IDLE -> stringResource(R.string.ctl_rec_idle)
                            LiveRecState.ARMED -> stringResource(R.string.ctl_rec_armed)
                            LiveRecState.RECORDING -> stringResource(R.string.ctl_rec_recording)
                            LiveRecState.PLAYING -> stringResource(R.string.ctl_rec_playing)
                        }
                        val recColor = when (uiState.liveRec) {
                            LiveRecState.IDLE -> OssmPrimary
                            LiveRecState.ARMED, LiveRecState.RECORDING -> OssmError
                            LiveRecState.PLAYING -> OssmWarning
                        }
                        Button(
                            onClick = onLiveRecordToggle,
                            enabled = connected && uiState.streamingReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = recColor)
                        ) {
                            Text(recLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
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
                        ControlSlider(
                            label = stringResource(R.string.ctl_sensation_ramp),
                            value = sensationDraft,
                            onValueChange = { sensationDraft = it; onSensationLive(it) },
                            onValueCommit = { committed ->
                                sensationDraft = committed
                                onSensationCommit(committed)
                            },
                            enabled = slidersEnabled,
                            activeColor = OssmAccent
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
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmConnected)
                            ) {
                                Text(stringResource(R.string.ctl_start))
                            }
                            Button(
                                onClick = onAutoStop,
                                enabled = connected && uiState.autoRunning,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = OssmWarning)
                            ) {
                                Text(stringResource(R.string.ctl_stop_mix))
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
                            onValueChange = { speedDraft = it; onSpeedLive(it) },
                            onValueCommit = { committed ->
                                speedDraft = committed
                                onSpeedCommit(committed)
                            },
                            enabled = slidersEnabled,
                            activeColor = OssmPrimary
                        )
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
                        if (activePattern.mode == PatternControlMode.STROKE_ENGINE && activePattern.key != "simpleStroke") {
                            Spacer(Modifier.height(12.dp))
                            ControlSlider(
                                label = stringResource(R.string.ctl_sensation),
                                value = sensationDraft,
                                onValueChange = { sensationDraft = it; onSensationLive(it) },
                                onValueCommit = { committed ->
                                    sensationDraft = committed
                                    onSensationCommit(committed)
                                },
                                enabled = slidersEnabled,
                                activeColor = OssmAccent
                            )
                            // Sensation aléatoire : moitié retour (0–50) et/ou aller (50–100).
                            RandomModeRow(
                                label = stringResource(R.string.ctl_random_sensation_low),
                                checked = uiState.randSensationLow,
                                onCheckedChange = { onRandomToggle(RandomTarget.SENSATION_LOW, it) }
                            )
                            RandomModeRow(
                                label = stringResource(R.string.ctl_random_sensation_high),
                                checked = uiState.randSensationHigh,
                                onCheckedChange = { onRandomToggle(RandomTarget.SENSATION_HIGH, it) }
                            )
                        }
                        // Pied de section : mode à l'écoute (micro).
                        if (isRandomCapable) {
                            Spacer(Modifier.height(16.dp))
                            ListeningModeFooter(
                                enabled = uiState.listeningMode,
                                level = listeningLevel,
                                onToggle = onListeningToggle
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        if (patternsCollapsed) {
            val bubbleSizePx = with(LocalDensity.current) { 62.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(OssmPrimary.copy(alpha = 0.94f))
                    .border(2.dp, OssmPrimaryLight.copy(alpha = 0.7f), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures { patternsCollapsed = false }
                    }
                    .pointerInput(rootSize) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            val maxX = (rootSize.width - bubbleSizePx).coerceAtLeast(0f)
                            val maxY = (rootSize.height - bubbleSizePx).coerceAtLeast(0f)
                            bubbleX = (bubbleX + amount.x).coerceIn(0f, maxX)
                            bubbleY = (bubbleY + amount.y).coerceIn(0f, maxY)
                            // Interdit de recouvrir le bouton STOP.
                            stopRect?.let { r ->
                                val b = Rect(bubbleX, bubbleY, bubbleX + bubbleSizePx, bubbleY + bubbleSizePx)
                                if (b.overlaps(Rect(r.left - 12f, r.top - 12f, r.right + 12f, r.bottom + 12f))) {
                                    bubbleY = if ((bubbleY + bubbleSizePx / 2f) < r.center.y) {
                                        (r.top - bubbleSizePx - 16f).coerceAtLeast(0f)
                                    } else {
                                        (r.bottom + 16f).coerceAtMost(maxY)
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("▦", color = OssmOnSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
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

    if (showSaveDialog && uiState.activePattern != null) {
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
        ButtonsInfoDialog(onDismiss = { showInfoDialog = false })
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
private fun SecuritySquare(
    speedChecked: Boolean,
    onSpeedCheckedChange: (Boolean) -> Unit,
    depthChecked: Boolean,
    onDepthCheckedChange: (Boolean) -> Unit,
    onInfoClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(OssmGlass)
            .border(1.dp, OssmGlassBorder, shape = MaterialTheme.shapes.medium)
            .padding(6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "SECURITE",
                color = OssmPrimaryLight,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniGuardToggle(label = "V", checked = speedChecked, onCheckedChange = onSpeedCheckedChange)
                MiniGuardToggle(label = "P", checked = depthChecked, onCheckedChange = onDepthCheckedChange)
            }
        }
        IconButton(
            onClick = onInfoClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Informations sur les boutons",
                tint = OssmPrimaryLight,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun MiniGuardToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (checked) OssmPrimary.copy(alpha = 0.25f) else OssmOnSurface.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = if (checked) OssmPrimary else OssmOnSurface.copy(alpha = 0.3f),
                shape = CircleShape
            )
            .pointerInput(checked) {
                detectTapGestures { onCheckedChange(!checked) }
            }
    ) {
        Text(
            label,
            color = if (checked) OssmPrimaryLight else OssmOnSurface.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
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
private fun ButtonsInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OssmSurface,
        title = { Text("A quoi servent ces boutons ?", color = OssmOnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoLine("SECURITE (V / P)", "Active/desactive la protection contre un changement brusque de Vitesse ou de Profondeur. Si active, un changement soudain (glissement accroche, doigt qui saute) demande confirmation avant d'etre applique.")
                InfoLine("STOP", "Coupe immediatement le mouvement (vitesse a zero).")
                InfoLine("Pause/Play (jaune/vert)", "Jaune = en mouvement, appuie pour mettre en pause. Vert = en pause, appuie pour reprendre exactement a la meme position/intensite.")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)) {
                Text("Compris")
            }
        }
    )
}

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
    onToggle: (Boolean) -> Unit
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
            colors = CheckboxDefaults.colors(
                checkedColor = OssmAccent,
                uncheckedColor = OssmOnSurface.copy(alpha = 0.5f)
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = OssmOnSurface, fontSize = 14.sp)
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
    positionFraction: Float? = null
) {
    var sliderWidthPx by remember { mutableIntStateOf(0) }

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
                "${(minValue * 100).toInt()}% - ${(maxValue * 100).toInt()}%",
                color = if (enabled) OssmOnSurface else OssmOnSurface.copy(alpha = 0.4f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // − à gauche = recule le MIN ; + à droite = avance le MAX (pas de 1 %).
            FilledTonalIconButton(
                onClick = { onRangeCommit((minValue - 0.01f).coerceIn(0f, maxValue), maxValue) },
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
                            val newRange = if (kotlin.math.abs(tappedValue - minValue) <= kotlin.math.abs(tappedValue - maxValue)) {
                                tappedValue.coerceAtMost(maxValue)..maxValue
                            } else {
                                minValue..tappedValue.coerceAtLeast(minValue)
                            }
                            onRangeChange(newRange.start, newRange.endInclusive)
                            onRangeCommit(newRange.start, newRange.endInclusive)
                        }
                    }
            ) {
                RangeSlider(
                    value = minValue..maxValue,
                    onValueChange = { range ->
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
                // Barre rouge = position réelle du chariot (rapportée par la machine).
                // positionFraction est auto-calibré sur la course OBSERVÉE (0 = point le
                // plus rétracté vu, 1 = point le plus profond vu). On le recale dans la
                // plage ABSOLUE des curseurs [min, max] : la barre rouge reste donc
                // toujours entre le curseur min et le curseur max et ne les dépasse
                // jamais (la machine ne va jamais plus profond que le max réglé).
                if (positionFraction != null && sliderWidthPx > 0) {
                    val absoluteFraction =
                        (minValue + positionFraction.coerceIn(0f, 1f) * (maxValue - minValue))
                            .coerceIn(0f, 1f)
                    val xDp = with(LocalDensity.current) {
                        (sliderWidthPx * absoluteFraction).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = xDp - 1.5.dp)
                            .width(3.dp)
                            .height(48.dp)
                            .background(OssmError.copy(alpha = 0.9f))
                    )
                }
            }

            FilledTonalIconButton(
                onClick = { onRangeCommit(minValue, (maxValue + 0.01f).coerceIn(minValue, 1f)) },
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmPrimary,
                checkedTrackColor = OssmPrimary.copy(alpha = 0.35f)
            )
        )
    }
}

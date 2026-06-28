package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.ui.theme.PatternColors
import com.ossm.remote.viewmodel.ControlUiState
import com.ossm.remote.viewmodel.GuardedControl

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
    onPattern: (String) -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
    onSavePreset: (Preset) -> Unit,
    onSpeedGuardEnabledChange: (Boolean) -> Unit,
    onDepthGuardEnabledChange: (Boolean) -> Unit,
    onConfirmPendingChange: (Boolean, Boolean) -> Unit,
    onDismissPendingChange: () -> Unit
) {
    val connected = connectionState is BleConnectionState.Connected
    val machineBusy = machineState.isHoming || machineState.isPreflight
    val slidersEnabled = connected && !machineBusy
    var showSaveDialog by remember { mutableStateOf(false) }
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StopButton(onClick = onStop)
                HomeButton(onClick = onHome, enabled = connected)
            }

            if (!connected) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmWarning) {
                    Text(
                        "Connectez-vous à un OSSM pour activer les commandes",
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

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "PATTERNS",
                    color = OssmPrimaryLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                if (uiState.availablePatterns.isEmpty()) {
                    Text(
                        "Aucun pattern détecté pour ce firmware.",
                        color = OssmOnSurface.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        uiState.availablePatterns.forEachIndexed { index, pattern ->
                            PatternButton(
                                label = pattern.name,
                                color = PatternColors[index % PatternColors.size],
                                isActive = uiState.activePatternKey == pattern.key,
                                onClick = { onPattern(pattern.key) },
                                enabled = connected,
                                modifier = Modifier.width(148.dp)
                            )
                        }
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "CONTROLE",
                    color = OssmPrimaryLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val activePattern = uiState.activePattern
                when {
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
                            "Glisse ton doigt dans la zone — la machine suit ta position en direct.",
                            color = OssmOnSurface.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        LiveStreamPad(
                            enabled = slidersEnabled,
                            onTarget = onStreamTarget,
                            onActive = onStreamActive
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
                        ControlSlider(
                            label = "Sensation (vitesse de montée)",
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
                    else -> {
                        ControlSlider(
                            label = "Vitesse",
                            value = speedDraft,
                            onValueChange = { speedDraft = it; onSpeedLive(it) },
                            onValueCommit = { committed ->
                                speedDraft = committed
                                onSpeedCommit(committed)
                            },
                            enabled = slidersEnabled,
                            activeColor = OssmPrimary
                        )
                        Spacer(Modifier.height(12.dp))
                        DepthRangeEditor(
                            minValue = depthRangeDraft.start,
                            maxValue = depthRangeDraft.endInclusive,
                            enabled = slidersEnabled,
                            onRangeChange = { min, max ->
                                depthRangeDraft = min..max
                                onDepthLive(min, max)
                            },
                            onRangeCommit = { min, max ->
                                depthRangeDraft = min..max
                                onDepthRangeCommit(min, max)
                            }
                        )
                        if (activePattern.mode == PatternControlMode.STROKE_ENGINE) {
                            Spacer(Modifier.height(12.dp))
                            ControlSlider(
                                label = "Sensation",
                                value = sensationDraft,
                                onValueChange = { sensationDraft = it; onSensationLive(it) },
                                onValueCommit = { committed ->
                                    sensationDraft = committed
                                    onSensationCommit(committed)
                                },
                                enabled = slidersEnabled,
                                activeColor = OssmAccent
                            )
                        }
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SECURITE",
                    color = OssmPrimaryLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SliderGuardRow(
                    title = "Vitesse",
                    description = "Confirme les changements manuels au-dela de ${uiState.speedGuard.thresholdPercent} %.",
                    checked = uiState.speedGuard.enabled,
                    onCheckedChange = onSpeedGuardEnabledChange
                )
                Spacer(Modifier.height(10.dp))
                SliderGuardRow(
                    title = "Profondeur",
                    description = "Confirme les changements manuels au-dela de ${uiState.depthGuard.thresholdPercent} %.",
                    checked = uiState.depthGuard.enabled,
                    onCheckedChange = onDepthGuardEnabledChange
                )
            }

            Spacer(Modifier.height(80.dp))
        }
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
            onDismiss = onDismissPendingChange
        )
    }
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
    onRangeCommit: (Float, Float) -> Unit
) {
    var sliderWidthPx by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "PROFONDEUR",
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = OssmAccent,
                    activeTrackColor = OssmAccent,
                    inactiveTrackColor = OssmAccent.copy(alpha = 0.25f)
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Min ${(minValue * 100).toInt()}%", color = OssmOnSurface.copy(alpha = 0.7f), fontSize = 12.sp)
            Text("Max ${(maxValue * 100).toInt()}%", color = OssmOnSurface.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
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

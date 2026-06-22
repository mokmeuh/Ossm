package com.ossm.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.Preset
import com.ossm.remote.model.PredefinedPatterns
import com.ossm.remote.ui.components.*
import com.ossm.remote.ui.theme.*
import com.ossm.remote.viewmodel.ControlUiState

@Composable
fun ControlScreen(
    connectionState: BleConnectionState,
    uiState: ControlUiState,
    onSpeed: (Float) -> Unit,
    onDepth: (Float) -> Unit,
    onStroke: (Float) -> Unit,
    onSensation: (Float) -> Unit,
    onPattern: (Int) -> Unit,
    onStop: () -> Unit,
    onSavePreset: (Preset) -> Unit
) {
    val connected = connectionState is BleConnectionState.Connected
    var showSaveDialog by remember { mutableStateOf(false) }

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
            // Status bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BleStatusIndicator(state = connectionState)
                if (connected) {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Default.Save, "Sauvegarder profil", tint = OssmPrimaryLight)
                    }
                }
            }

            // STOP button — always centered
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                StopButton(onClick = onStop)
            }

            if (!connected) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmWarning) {
                    Text(
                        "Connectez-vous à un OSSM pour activer les commandes",
                        color = OssmWarning,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // Sliders
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "CONTRÔLE",
                    color = OssmPrimaryLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                ControlSlider(
                    label = "Vitesse",
                    value = uiState.speed,
                    onValueChange = onSpeed,
                    enabled = connected,
                    activeColor = OssmPrimary
                )
                Spacer(Modifier.height(8.dp))

                ControlSlider(
                    label = "Profondeur",
                    value = uiState.depth,
                    onValueChange = onDepth,
                    enabled = connected,
                    activeColor = OssmAccent
                )
                Spacer(Modifier.height(8.dp))

                ControlSlider(
                    label = "Longueur stroke",
                    value = uiState.strokeLength,
                    onValueChange = onStroke,
                    enabled = connected,
                    activeColor = OssmConnected
                )
                Spacer(Modifier.height(8.dp))

                ControlSlider(
                    label = "Sensation / Intensité",
                    value = uiState.sensation,
                    onValueChange = onSensation,
                    enabled = connected,
                    activeColor = OssmWarning
                )
            }

            // Patterns
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "PATTERNS",
                    color = OssmPrimaryLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PredefinedPatterns.forEachIndexed { i, pattern ->
                        PatternButton(
                            label = pattern.name,
                            color = PatternColors[i],
                            isActive = uiState.activePatternId == pattern.id,
                            onClick = { onPattern(pattern.id) },
                            enabled = connected,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                PredefinedPatterns.firstOrNull { it.id == uiState.activePatternId }?.let { p ->
                    Text(
                        p.description,
                        color = OssmOnSurface.copy(0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(80.dp)) // nav bar clearance
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            currentState = uiState,
            onDismiss = { showSaveDialog = false },
            onSave = { preset ->
                onSavePreset(preset)
                showSaveDialog = false
            }
        )
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
                    onSave(
                        Preset(
                            name = name.ifBlank { "Profil" },
                            speed = currentState.speed,
                            depth = currentState.depth,
                            strokeLength = currentState.strokeLength,
                            sensation = currentState.sensation,
                            patternId = currentState.activePatternId
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary)
            ) { Text("Sauvegarder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = OssmPrimaryLight) }
        }
    )
}

package com.ossm.remote.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.StopButton
import com.ossm.remote.ui.theme.*
import com.ossm.remote.viewmodel.FunscriptUiState

@Composable
fun FunscriptScreen(
    uiState: FunscriptUiState,
    connectionState: BleConnectionState,
    onLoad: (Uri, String) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val connected = connectionState is BleConnectionState.Connected

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val name = context.contentResolver.query(it, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                c.moveToFirst()
                if (col >= 0) c.getString(col) else "funscript"
            } ?: "funscript"
            onLoad(it, name)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OssmBackground, OssmSecondary)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "FUNSCRIPT",
                style = MaterialTheme.typography.titleLarge,
                color = OssmAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // File picker
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fichier Funscript", color = OssmPrimaryLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            uiState.fileName.ifBlank { "Aucun fichier chargé" },
                            color = if (uiState.fileName.isBlank()) OssmOnSurface.copy(0.4f) else OssmOnSurface,
                            fontSize = 14.sp
                        )
                        if (uiState.totalActions > 0) {
                            Text("${uiState.totalActions} actions", color = OssmAccent, fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = { launcher.launch("application/json") }) {
                        Icon(Icons.Default.FolderOpen, "Ouvrir", tint = OssmPrimary, modifier = Modifier.size(28.dp))
                    }
                }
            }

            // Progress
            if (uiState.totalActions > 0) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Action", color = OssmPrimaryLight, fontSize = 11.sp)
                            Text(
                                "${uiState.currentActionIndex} / ${uiState.totalActions}",
                                color = OssmOnSurface,
                                fontSize = 12.sp
                            )
                        }
                        LinearProgressIndicator(
                            progress = { if (uiState.totalActions > 0) uiState.currentActionIndex.toFloat() / uiState.totalActions else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = OssmAccent,
                            trackColor = OssmGlass
                        )
                        val secs = uiState.elapsedMs / 1000
                        Text("${secs / 60}:${"%02d".format(secs % 60)}", color = OssmOnSurface.copy(0.6f), fontSize = 12.sp)
                    }
                }
            }

            // Controls
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause
                    FilledIconButton(
                        onClick = if (uiState.isPlaying) onPause else onPlay,
                        enabled = connected && uiState.fileName.isNotBlank(),
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = OssmPrimary)
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    StopButton(onClick = onStop)
                }

                if (!connected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connectez-vous à un OSSM pour lancer la lecture",
                        color = OssmWarning,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Error
            uiState.error?.let { err ->
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = OssmError)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = OssmError, fontSize = 13.sp)
                    }
                }
            }

            // Status
            if (uiState.isPaused) {
                Text("⏸ En pause — appuyez sur ▶ pour reprendre", color = OssmWarning, fontSize = 12.sp)
            }
            if (uiState.isPreparing) {
                Text("Préparation (homing en cours)...", color = OssmAccent, fontSize = 12.sp)
            }
            if (uiState.isPlaying && !uiState.isPreparing) {
                Text("▶ Streaming en cours...", color = OssmConnected, fontSize = 12.sp)
            }
        }
    }
}

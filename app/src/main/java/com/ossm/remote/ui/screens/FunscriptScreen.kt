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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
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
    onStop: () -> Unit,
    onDepthRangeChange: (Int, Int) -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val connected = connectionState is BleConnectionState.Connected
    val hasFile = uiState.totalActions > 0

    // Accepte tous les types: les .funscript sont souvent signales en
    // application/octet-stream, un filtre application/json les masquerait.
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
                stringResource(R.string.funscript_title),
                style = MaterialTheme.typography.titleLarge,
                color = OssmAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // Selecteur de fichier
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.funscript_file_label),
                            color = OssmPrimaryLight, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            uiState.fileName.ifBlank { stringResource(R.string.funscript_no_file) },
                            color = if (uiState.fileName.isBlank()) OssmOnSurface.copy(0.4f) else OssmOnSurface,
                            fontSize = 14.sp
                        )
                        if (uiState.totalActions > 0) {
                            Text(
                                stringResource(R.string.funscript_actions_count, uiState.totalActions),
                                color = OssmAccent, fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = { launcher.launch("*/*") }) {
                        Icon(
                            Icons.Default.FolderOpen,
                            stringResource(R.string.funscript_open),
                            tint = OssmPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Plage de profondeur (remappe pos brut 0-100 dans [min, max])
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.funscript_depth_range),
                            color = OssmPrimaryLight, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(
                                R.string.funscript_depth_range_value,
                                uiState.depthMin, uiState.depthMax
                            ),
                            color = OssmAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    RangeSlider(
                        value = uiState.depthMin.toFloat()..uiState.depthMax.toFloat(),
                        onValueChange = { r ->
                            onDepthRangeChange(r.start.toInt(), r.endInclusive.toInt())
                        },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = OssmPrimary,
                            activeTrackColor = OssmAccent
                        )
                    )
                }
            }

            // Vitesse de lecture
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.funscript_speed),
                            color = OssmPrimaryLight, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(
                                R.string.funscript_speed_value,
                                "%.2f".format(uiState.speedFactor)
                            ),
                            color = OssmAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = uiState.speedFactor,
                        onValueChange = onSpeedChange,
                        valueRange = 0.5f..2.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = OssmPrimary,
                            activeTrackColor = OssmAccent
                        )
                    )
                }
            }

            // Progression
            if (hasFile) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.funscript_action), color = OssmPrimaryLight, fontSize = 11.sp)
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

            // Controles
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = if (uiState.isPlaying) onPause else onPlay,
                        enabled = connected && hasFile,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = OssmPrimary)
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying)
                                stringResource(R.string.funscript_pause)
                            else stringResource(R.string.funscript_play),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    StopButton(onClick = onStop)
                }

                if (!connected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.funscript_not_connected),
                        color = OssmWarning,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Erreur
            uiState.error?.let { err ->
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = OssmError)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = OssmError, fontSize = 13.sp)
                    }
                }
            }

            // Statut
            if (uiState.isPaused) {
                Text(stringResource(R.string.funscript_paused), color = OssmWarning, fontSize = 12.sp)
            }
            if (uiState.isPreparing) {
                Text(stringResource(R.string.funscript_preparing), color = OssmAccent, fontSize = 12.sp)
            }
            if (uiState.isPlaying && !uiState.isPreparing) {
                Text(stringResource(R.string.funscript_streaming), color = OssmConnected, fontSize = 12.sp)
            }
        }
    }
}

package com.ossm.remote.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ossm.remote.R
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.StopButton
import com.ossm.remote.ui.theme.*
import com.ossm.remote.viewmodel.VideoSyncUiState

@Composable
fun VideoSyncScreen(
    uiState: VideoSyncUiState,
    connectionState: BleConnectionState,
    player: ExoPlayer,
    onVideoUri: (Uri, String) -> Unit,
    onVideoUrl: (String) -> Unit,
    onFunscriptUri: (Uri, String) -> Unit,
    onFunscriptUrl: (String) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onLatencyChange: (Int) -> Unit,
    onDepthRangeChange: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val connected = connectionState is BleConnectionState.Connected
    val hasScript = uiState.totalActions > 0

    var videoUrlField by remember { mutableStateOf("") }
    var funscriptUrlField by remember { mutableStateOf("") }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val name = queryName(context, it) ?: "video"
            onVideoUri(it, name)
        }
    }

    val funscriptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val name = queryName(context, it) ?: "funscript"
            onFunscriptUri(it, name)
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(R.string.videosync_title),
                style = MaterialTheme.typography.titleLarge,
                color = OssmAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // Lecteur vidéo
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black, RoundedCornerShape(12.dp)),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { it.player = player }
            )

            // Source vidéo
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.videosync_video_source),
                    color = OssmPrimaryLight, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    uiState.videoLabel.ifBlank { stringResource(R.string.videosync_no_video) },
                    color = if (uiState.videoLabel.isBlank()) OssmOnSurface.copy(0.4f) else OssmOnSurface,
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = videoUrlField,
                        onValueChange = { videoUrlField = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.videosync_url_hint), fontSize = 11.sp) }
                    )
                    IconButton(onClick = { if (videoUrlField.isNotBlank()) onVideoUrl(videoUrlField) }) {
                        Icon(Icons.Default.Link, "URL", tint = OssmPrimary)
                    }
                    IconButton(onClick = { videoLauncher.launch(arrayOf("video/*")) }) {
                        Icon(Icons.Default.FolderOpen, stringResource(R.string.videosync_pick_video), tint = OssmPrimary)
                    }
                }
            }

            // Source funscript
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.videosync_script_source),
                    color = OssmPrimaryLight, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (hasScript)
                        "${uiState.funscriptName} — ${uiState.totalActions}"
                    else stringResource(R.string.videosync_no_script),
                    color = if (hasScript) OssmAccent else OssmOnSurface.copy(0.4f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = funscriptUrlField,
                        onValueChange = { funscriptUrlField = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.videosync_url_hint), fontSize = 11.sp) }
                    )
                    IconButton(onClick = { if (funscriptUrlField.isNotBlank()) onFunscriptUrl(funscriptUrlField) }) {
                        Icon(Icons.Default.Link, "URL", tint = OssmPrimary)
                    }
                    IconButton(onClick = { funscriptLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FolderOpen, stringResource(R.string.videosync_pick_script), tint = OssmPrimary)
                    }
                }
            }

            // Décalage de latence
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.videosync_latency),
                        color = OssmPrimaryLight, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${uiState.latencyOffsetMs} ms",
                        color = OssmAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = uiState.latencyOffsetMs.toFloat(),
                    onValueChange = { onLatencyChange(it.toInt()) },
                    valueRange = -1000f..1000f,
                    colors = SliderDefaults.colors(
                        thumbColor = OssmPrimary,
                        activeTrackColor = OssmAccent
                    )
                )
                Text(
                    stringResource(R.string.videosync_latency_hint),
                    color = OssmOnSurface.copy(0.5f), fontSize = 10.sp
                )
            }

            // Plage de profondeur
            GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                    onValueChange = { r -> onDepthRangeChange(r.start.toInt(), r.endInclusive.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = OssmPrimary,
                        activeTrackColor = OssmAccent
                    )
                )
            }

            // Contrôles
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = onPlayPause,
                        enabled = uiState.hasVideo,
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

            // Statut
            if (uiState.isPreparing) {
                Text(stringResource(R.string.funscript_preparing), color = OssmAccent, fontSize = 12.sp)
            }
            if (uiState.streamingActive && uiState.isPlaying) {
                Text(stringResource(R.string.funscript_streaming), color = OssmConnected, fontSize = 12.sp)
            }
            uiState.error?.let { err ->
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = OssmError)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = OssmError, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun queryName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        }
    } catch (_: Exception) {
        null
    }
}

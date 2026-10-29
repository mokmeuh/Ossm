package com.ossm.remote.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import com.ossm.remote.usb.FirmwareFlashState
import com.ossm.remote.usb.FirmwareFlashViewModel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.model.Preset
import com.ossm.remote.overlay.OverlayService
import com.ossm.remote.remote.RemoteSessionViewModel
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.updater.UpdateManifest
import com.ossm.remote.updater.UpdaterStatus
import com.ossm.remote.viewmodel.OverlaySettingsViewModel
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ProfilesScreen(
    presets: List<Preset>,
    updaterState: UpdaterStatus,
    updaterUrl: String,
    onApply: (Preset) -> Unit,
    onDelete: (Preset) -> Unit,
    onUpdateUrlSave: (String) -> Unit,
    onUpdateCheck: () -> Unit,
    onUpdateDownload: (UpdateManifest) -> Unit,
    onUpdateInstall: (File) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        // Keep the whole firmware card (and its log) on screen while a flash is running.
        val flashVm: FirmwareFlashViewModel = hiltViewModel()
        val flashState by flashVm.state.collectAsState()
        val flashPhase = when (flashState) {
            FirmwareFlashState.Idle -> 0
            FirmwareFlashState.Preparing, FirmwareFlashState.Erasing, is FirmwareFlashState.Writing -> 1
            else -> 2
        }
        val listState = rememberLazyListState()
        LaunchedEffect(flashPhase) {
            if (flashPhase != 0) listState.scrollToItem((listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.prof_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OssmOnSurface,
                    fontWeight = FontWeight.Bold
                )
            }

            if (presets.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.BookmarkBorder, null, tint = OssmPrimaryLight, modifier = Modifier.size(48.dp))
                            Text(
                                stringResource(R.string.prof_empty),
                                color = OssmOnSurfaceMuted,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(presets, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        onApply = { onApply(preset) },
                        onDelete = { onDelete(preset) }
                    )
                }
            }
            item(key = "remote-session") { RemoteSessionCard() }
            item(key = "overlay") { OverlayCard() }
            item(key = "updater") {
                UpdaterCard(
                    state = updaterState,
                    url = updaterUrl,
                    onUrlSave = onUpdateUrlSave,
                    onCheck = onUpdateCheck,
                    onDownload = onUpdateDownload,
                    onInstall = onUpdateInstall
                )
            }
            item(key = "firmware") { FirmwareFlashCard() }
            item(key = "statistics") { StatisticsSection() }
        }
    }
}

@Composable
private fun RemoteSessionCard() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val viewModel: RemoteSessionViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(stringResource(R.string.remote_session_title), color = OssmOnSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            stringResource(R.string.remote_session_hint),
            color = OssmOnSurfaceMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Text(state.message, color = if (state.connected) OssmConnected else OssmWarning, fontSize = 12.sp, lineHeight = 17.sp)
        Text(state.mediaStatus, color = OssmOnSurfaceMuted, fontSize = 12.sp, lineHeight = 17.sp)
        state.shareUrl?.let { shareUrl ->
            // Le lien ne va dans le presse-papiers que sur action explicite (bouton Copier).
            var copied by remember(shareUrl) { mutableStateOf(false) }
            OutlinedTextField(
                value = shareUrl,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.remote_session_link)) },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(shareUrl))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui2_remote_copy))
                }
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.remote_share)))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_share))
                }
            }
            if (copied) {
                Text(
                    stringResource(R.string.ui2_remote_copied),
                    color = OssmConnected,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
        Button(
            onClick = { if (state.active) viewModel.stop() else viewModel.start() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
        ) {
            Text(stringResource(if (state.active) R.string.remote_stop_session else R.string.remote_session_start))
        }
        Button(
            onClick = viewModel::regenerate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = OssmWarning),
        ) {
            Text(stringResource(R.string.remote_session_regenerate))
        }
        Text(stringResource(R.string.remote_session_regenerate_hint), color = OssmOnSurfaceMuted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun UpdaterCard(
    state: UpdaterStatus,
    url: String,
    onUrlSave: (String) -> Unit,
    onCheck: () -> Unit,
    onDownload: (UpdateManifest) -> Unit,
    onInstall: (File) -> Unit
) {
    var manualUrl by remember(url) { mutableStateOf(url) }
    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, null, tint = OssmPrimaryLight)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.update_title),
                color = OssmOnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCheck, enabled = state !is UpdaterStatus.Checking && state !is UpdaterStatus.Downloading) {
                Text(stringResource(R.string.update_check_now), color = OssmPrimaryLight)
            }
        }
        LocalOtaSection()
        PcUpdateSection()
        Text(
            stringResource(R.string.update_ota_title),
            color = OssmOnSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = manualUrl,
            onValueChange = { manualUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.update_url_label)) },
            placeholder = { Text(stringResource(R.string.update_url_hint)) },
            singleLine = true
        )
        TextButton(onClick = { onUrlSave(manualUrl) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.update_url_save), color = OssmPrimaryLight)
        }
        when (state) {
            UpdaterStatus.Idle -> Unit
            UpdaterStatus.Checking -> Text(stringResource(R.string.update_checking), color = OssmOnSurfaceMuted, fontSize = 12.sp)
            UpdaterStatus.UpToDate -> Text(stringResource(R.string.update_current), color = OssmConnected, fontSize = 12.sp)
            is UpdaterStatus.Available -> {
                Text(
                    stringResource(R.string.update_available, state.manifest.versionName),
                    color = OssmConnected,
                    fontSize = 12.sp
                )
                Button(onClick = { onDownload(state.manifest) }) {
                    Text(stringResource(R.string.update_download))
                }
            }
            UpdaterStatus.Downloading -> Text(stringResource(R.string.update_downloading), color = OssmOnSurfaceMuted, fontSize = 12.sp)
            is UpdaterStatus.ReadyToInstall -> Button(onClick = { onInstall(state.apk) }) {
                Text(stringResource(R.string.update_install))
            }
            is UpdaterStatus.Error -> Text(stringResource(R.string.update_error), color = OssmError, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PresetCard(preset: Preset, onApply: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(preset.name, fontWeight = FontWeight.Bold, color = OssmOnSurface, fontSize = 16.sp)
                Text(preset.patternName, color = OssmPrimaryLight, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip(stringResource(R.string.prof_speed), "${(preset.speed * 100).toInt()}%", OssmPrimary)
                    StatChip(stringResource(R.string.prof_min), "${(preset.depthMin * 100).toInt()}%", OssmAccent)
                    StatChip(stringResource(R.string.prof_max), "${(preset.depthMax * 100).toInt()}%", OssmConnected)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onApply) {
                    Icon(Icons.Default.PlayArrow, stringResource(R.string.prof_apply), tint = OssmConnected)
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.prof_delete), tint = OssmError)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = OssmSurface,
            title = { Text(stringResource(R.string.prof_delete_title), color = OssmOnSurface) },
            text = { Text(stringResource(R.string.prof_delete_msg, preset.name), color = OssmOnSurfaceMuted) },
            confirmButton = {
                Button(
                    onClick = { onDelete(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = OssmError)
                ) {
                    Text(stringResource(R.string.prof_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.ctl_cancel), color = OssmPrimaryLight)
                }
            }
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = OssmPrimaryLight, fontSize = 12.sp)
        Text(
            value,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun OverlayCard() {
    val context = LocalContext.current
    // hiltViewModel() : même instance que celle utilisée ailleurs (scopée à l'Activity).
    val overlayVm: OverlaySettingsViewModel = hiltViewModel()
    val overlayEnabled by overlayVm.overlayEnabled.collectAsState()
    val transparency by overlayVm.transparencyPercent.collectAsState()
    val canDrawOverlays = Settings.canDrawOverlays(context)

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = overlayEnabled,
                    role = Role.Switch,
                    onValueChange = { enabled -> onOverlayToggle(context, overlayVm, enabled) }
                )
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                null,
                tint = OssmPrimaryLight,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.overlay_title),
                color = OssmOnSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = overlayEnabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OssmAccent,
                    checkedTrackColor = OssmAccent.copy(alpha = 0.4f)
                )
            )
        }
        Text(
            stringResource(R.string.overlay_hint),
            color = OssmOnSurfaceMuted,
            fontSize = 12.sp
        )
        if (!canDrawOverlays) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.overlay_permission_hint),
                color = OssmWarning,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.overlay_transparency),
                color = OssmOnSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$transparency%",
                color = OssmPrimaryLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = transparency.toFloat(),
            onValueChange = { overlayVm.setTransparencyPercent(it.roundToInt()) },
            valueRange = 20f..100f,
            steps = 79,
            colors = SliderDefaults.colors(
                thumbColor = OssmPrimary,
                activeTrackColor = OssmPrimary,
                inactiveTrackColor = OssmPrimary.copy(alpha = 0.25f)
            )
        )
    }
}

internal fun onOverlayToggle(context: Context, overlayVm: OverlaySettingsViewModel, enabled: Boolean) {
    if (enabled) {
        if (Settings.canDrawOverlays(context)) {
            OverlayService.manuallyClosed = false
            overlayVm.setOverlayEnabled(true)
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    } else {
        // Fermeture délibérée : posé AVANT l'arrêt async du service et l'écriture DataStore,
        // pour que l'auto-start de onStop (app quittée) ne relance pas l'overlay.
        OverlayService.manuallyClosed = true
        overlayVm.setOverlayEnabled(false)
        context.stopService(Intent(context, OverlayService::class.java))
    }
}

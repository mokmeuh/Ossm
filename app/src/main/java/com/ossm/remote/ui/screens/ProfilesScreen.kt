package com.ossm.remote.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ossm.remote.R
import com.ossm.remote.model.Preset
import com.ossm.remote.overlay.OverlayService
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSecondary
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import com.ossm.remote.updater.UpdateManifest
import com.ossm.remote.updater.UpdaterStatus
import com.ossm.remote.updater.UpdaterViewModel
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
            .background(Brush.verticalGradient(listOf(OssmBackground, OssmSecondary)))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "title") {
                Text(
                    stringResource(R.string.prof_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OssmPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            if (presets.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxSize(0.6f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.BookmarkBorder, null, tint = OssmPrimaryLight, modifier = Modifier.size(48.dp))
                            Text(
                                stringResource(R.string.prof_empty),
                                color = OssmOnSurface.copy(alpha = 0.4f),
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
            item(key = "statistics") { StatisticsSection() }
        }
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
    // hiltViewModel() : meme instance UpdaterViewModel que celle de MainActivity.
    val updaterVm: UpdaterViewModel = hiltViewModel()
    val periodicCheckEnabled by updaterVm.periodicCheckEnabled.collectAsState()
    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.update_periodic_check),
                color = OssmOnSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = periodicCheckEnabled,
                onCheckedChange = updaterVm::setPeriodicCheckEnabled
            )
        }
        when (state) {
            UpdaterStatus.Idle -> Unit
            UpdaterStatus.Checking -> Text(stringResource(R.string.update_checking), color = OssmOnSurface.copy(alpha = 0.7f), fontSize = 12.sp)
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
            UpdaterStatus.Downloading -> Text(stringResource(R.string.update_downloading), color = OssmOnSurface.copy(alpha = 0.7f), fontSize = 12.sp)
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

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
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
            text = { Text(stringResource(R.string.prof_delete_msg, preset.name), color = OssmOnSurface.copy(alpha = 0.7f)) },
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
        Text(label, color = OssmPrimaryLight, fontSize = 10.sp)
        Text(
            value,
            color = color,
            fontSize = 11.sp,
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

    GlassCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                onCheckedChange = { enabled -> onOverlayToggle(context, overlayVm, enabled) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OssmAccent,
                    checkedTrackColor = OssmAccent.copy(alpha = 0.4f)
                )
            )
        }
        Text(
            stringResource(R.string.overlay_hint),
            color = OssmOnSurface.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
        if (!canDrawOverlays) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.overlay_permission_hint),
                color = OssmWarning,
                fontSize = 11.sp
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

private fun onOverlayToggle(context: Context, overlayVm: OverlaySettingsViewModel, enabled: Boolean) {
    if (enabled) {
        if (Settings.canDrawOverlays(context)) {
            overlayVm.setOverlayEnabled(true)
            context.startForegroundService(Intent(context, OverlayService::class.java))
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    } else {
        overlayVm.setOverlayEnabled(false)
        context.stopService(Intent(context, OverlayService::class.java))
    }
}

package com.ossm.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.BuildConfig
import com.ossm.remote.R
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.remote.RemoteSessionState
import com.ossm.remote.ui.components.BleStatusIndicator
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.theme.*

@Composable
fun ScanScreen(
    connectionState: BleConnectionState,
    devices: List<BleDevice>,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onStop: () -> Unit,
    remoteCamera: RemoteCameraSection = RemoteCameraSection.NoSession,
    onShareMyCamera: () -> Unit = {},
    onStartRemoteSession: () -> Unit = {},
    onRegenerateRemoteLink: () -> Unit = {},
) {
    val isScanning = connectionState is BleConnectionState.Scanning
    val isConnecting = connectionState is BleConnectionState.Connecting
    val isConnected = connectionState is BleConnectionState.Connected

    LaunchedEffect(Unit) {
        if (!isConnected && !isConnecting && !isScanning) onScan()
    }

    val autoConnectAddress = scanAutoConnectAddress(devices, connectionState)
    LaunchedEffect(autoConnectAddress) {
        autoConnectAddress?.let(onConnect)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "OSSM Remote",
                            style = MaterialTheme.typography.headlineMedium,
                            color = OssmOnSurface
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            color = OssmOnSurfaceMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                BleStatusIndicator(state = connectionState)
            }

            // Scan button
            Button(
                onClick = if (isScanning) onStop else onScan,
                enabled = !isConnecting && !isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) OssmError else OssmPrimary
                )
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = OssmOnPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_stop), fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Bluetooth, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_devices), fontWeight = FontWeight.Bold)
                }
            }

            // Camera of the remote session (owner side): ALWAYS visible (2.3.16), placed above the
            // device list so a long scan result can never push it off screen.
            RemoteCameraCard(
                section = remoteCamera,
                onShareMyCamera = onShareMyCamera,
                onStartRemoteSession = onStartRemoteSession,
                onRegenerateRemoteLink = onRegenerateRemoteLink,
            )

            // Device list
            AnimatedVisibility(visible = devices.isNotEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth(), padding = 12.dp) {
                    Text(
                        stringResource(R.string.scan_found_count, devices.size),
                        color = OssmPrimaryLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices, key = { it.address }) { device ->
                            DeviceRow(
                                device = device,
                                isConnecting = isConnecting,
                                onClick = { onConnect(device.address) }
                            )
                        }
                    }
                }
            }

            // Connected info
            AnimatedVisibility(visible = isConnected) {
                if (connectionState is BleConnectionState.Connected) {
                    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = OssmConnected, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.scan_connected), color = OssmConnected, fontWeight = FontWeight.Bold)
                                Text(connectionState.deviceName, color = OssmOnSurface, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            if (isConnecting) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = OssmWarning)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.scan_connecting), color = OssmWarning)
                    }
                }
            }

            if (connectionState is BleConnectionState.Error) {
                GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
                    Row {
                        Icon(Icons.Default.Error, null, tint = OssmError)
                        Spacer(Modifier.width(8.dp))
                        Text(connectionState.message, color = OssmError)
                    }
                }
            }

            // Empty states: never leave the screen blank while scanning or after an empty scan.
            if (devices.isEmpty()) {
                val emptyState = when {
                    isScanning -> ScanEmptyState.SEARCHING
                    connectionState is BleConnectionState.Disconnected -> ScanEmptyState.NONE_FOUND
                    connectionState is BleConnectionState.Error -> ScanEmptyState.NONE_FOUND
                    else -> null
                }
                if (emptyState != null) {
                    ScanEmptyStateCard(
                        state = emptyState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    )
                }
            }
        }
    }
}

/** What the Connexion tab shows for the camera of the remote session. */
sealed interface RemoteCameraSection {
    /** No active remote session: the card invites to start one (same action as Réglages). */
    data object NoSession : RemoteCameraSection
    /** Session started but its host link is not known yet. */
    data object Starting : RemoteCameraSection
    /** Active session but the broker gave no camera link (old site or link created before 2.3.15). */
    data object Unavailable : RemoteCameraSection
    /** Camera-only invitation; a secret, so [toString] never prints it. */
    data class Available(val cameraUrl: String) : RemoteCameraSection {
        override fun toString(): String = "Available(cameraUrl=<redacted>)"
    }
}

internal fun remoteCameraSection(state: RemoteSessionState): RemoteCameraSection {
    val cameraUrl = state.cameraUrl
    return when {
        !state.active -> RemoteCameraSection.NoSession
        state.shareUrl == null -> RemoteCameraSection.Starting
        cameraUrl == null -> RemoteCameraSection.Unavailable
        else -> RemoteCameraSection.Available(cameraUrl)
    }
}

/** Explicit copy only; flagged sensitive so Android 13+ does not preview the link. */
private fun copyCameraLink(context: Context, label: String, cameraUrl: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, cameraUrl).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
}

@Composable
private fun RemoteCameraCard(
    section: RemoteCameraSection,
    onShareMyCamera: () -> Unit,
    onStartRemoteSession: () -> Unit,
    onRegenerateRemoteLink: () -> Unit,
) {
    val context = LocalContext.current
    GlassCard(modifier = Modifier.fillMaxWidth().testTag("remote_camera_card"), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Videocam, contentDescription = null, tint = OssmAccent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.ui2_camera_section_title),
                    color = OssmOnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.semantics { heading() },
                )
            }
            when (section) {
                RemoteCameraSection.NoSession -> {
                    Text(
                        stringResource(R.string.ui2_camera_no_session),
                        color = OssmOnSurfaceMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Button(
                        onClick = onStartRemoteSession,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
                    ) {
                        Text(stringResource(R.string.ui2_camera_start_session), fontWeight = FontWeight.Bold)
                    }
                }
                RemoteCameraSection.Starting -> Text(
                    stringResource(R.string.ui2_camera_session_starting),
                    color = OssmOnSurfaceMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                RemoteCameraSection.Unavailable -> {
                    Text(
                        stringResource(R.string.ui2_camera_regenerate_needed),
                        color = OssmWarning,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Button(
                        onClick = onRegenerateRemoteLink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OssmWarning),
                    ) {
                        Text(stringResource(R.string.ui2_camera_regenerate), fontWeight = FontWeight.Bold)
                    }
                }
                is RemoteCameraSection.Available -> {
                    var copied by remember(section.cameraUrl) { mutableStateOf(false) }
                    Text(
                        stringResource(R.string.ui2_camera_section_body),
                        color = OssmOnSurfaceMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Button(
                        onClick = onShareMyCamera,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = OssmPrimary),
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui2_camera_share_mine), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, section.cameraUrl)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.ui2_camera_invite_chooser)))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = OssmPrimaryLight, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui2_camera_invite), color = OssmPrimaryLight)
                    }
                    OutlinedButton(
                        onClick = {
                            copyCameraLink(context, context.getString(R.string.ui2_camera_invite_chooser), section.cameraUrl)
                            copied = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = OssmPrimaryLight, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui2_camera_copy), color = OssmPrimaryLight)
                    }
                    if (copied) {
                        Text(
                            stringResource(R.string.ui2_camera_copied),
                            color = OssmConnected,
                            fontSize = 12.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

private enum class ScanEmptyState { SEARCHING, NONE_FOUND }

@Composable
private fun ScanEmptyStateCard(
    state: ScanEmptyState,
    modifier: Modifier = Modifier
) {
    val title = when (state) {
        ScanEmptyState.SEARCHING -> stringResource(R.string.ui2_scan_searching_title)
        ScanEmptyState.NONE_FOUND -> stringResource(R.string.ui2_scan_none_title)
    }
    val body = when (state) {
        ScanEmptyState.SEARCHING -> stringResource(R.string.ui2_scan_searching_body)
        ScanEmptyState.NONE_FOUND -> stringResource(R.string.ui2_scan_none_body)
    }
    GlassCard(modifier = modifier, padding = 20.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state) {
                ScanEmptyState.SEARCHING -> CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = OssmAccent,
                    strokeWidth = 4.dp
                )
                ScanEmptyState.NONE_FOUND -> Icon(
                    Icons.Default.BluetoothSearching,
                    contentDescription = null,
                    tint = OssmOnSurfaceMuted,
                    modifier = Modifier.size(44.dp)
                )
            }
            Text(
                title,
                color = OssmOnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                body,
                color = OssmOnSurfaceMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            HorizontalDivider(color = OssmGlassBorder)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.ui2_scan_tips_title),
                    color = OssmPrimaryLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                ScanTip(Icons.Default.Power, stringResource(R.string.ui2_scan_tip_power))
                ScanTip(Icons.Default.NearMe, stringResource(R.string.ui2_scan_tip_close))
                ScanTip(Icons.Default.Bluetooth, stringResource(R.string.ui2_scan_tip_bluetooth))
            }
        }
    }
}

@Composable
private fun ScanTip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = OssmAccent, modifier = Modifier.size(22.dp))
        Text(text, color = OssmOnSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

internal fun uniqueOssmAddress(devices: List<BleDevice>): String? =
    devices.singleOrNull { it.isOssm }?.address

internal fun scanAutoConnectAddress(
    devices: List<BleDevice>,
    connectionState: BleConnectionState,
): String? = uniqueOssmAddress(devices).takeIf { connectionState is BleConnectionState.Scanning }

@Composable
private fun DeviceRow(
    device: BleDevice,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isConnecting) { onClick() }
            .background(OssmSurfaceRaised)
            .heightIn(min = 64.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (device.isOssm) Icons.Default.BluetoothConnected else Icons.Default.BluetoothSearching,
            contentDescription = null,
            tint = if (device.isOssm) OssmConnected else OssmPrimaryLight,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.name,
                fontWeight = if (device.isOssm) FontWeight.Bold else FontWeight.Normal,
                color = if (device.isOssm) OssmOnSurface else OssmOnSurface.copy(0.7f)
            )
            Text(device.address, color = OssmOnSurfaceMuted, fontSize = 12.sp)
        }
        Text(
            "${device.rssi} dBm",
            color = rssiColor(device.rssi),
            fontSize = 12.sp
        )
        if (device.isOssm) {
            Spacer(Modifier.width(8.dp))
            Text("OSSM", color = OssmConnected, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun rssiColor(rssi: Int) = when {
    rssi > -60  -> OssmConnected
    rssi > -75  -> OssmWarning
    else        -> OssmError
}

/** An open camera share stays only while the session still offers exactly that camera link. */
internal fun cameraShareStillAllowed(openedCameraUrl: String, section: RemoteCameraSection): Boolean =
    (section as? RemoteCameraSection.Available)?.cameraUrl == openedCameraUrl

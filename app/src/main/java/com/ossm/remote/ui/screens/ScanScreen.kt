package com.ossm.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ossm.remote.BuildConfig
import com.ossm.remote.R
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.BleDevice
import com.ossm.remote.remote.RemoteSessionState
import com.ossm.remote.ui.components.GlassCard
import com.ossm.remote.ui.components.OssmButton
import com.ossm.remote.ui.components.OssmButtonKind
import com.ossm.remote.ui.components.OssmStatusLine
import com.ossm.remote.ui.components.OssmTone
import com.ossm.remote.ui.theme.OssmBackground
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSectionTitle
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.ui.theme.OssmWarning

/** Adresse que le gestionnaire BLE attribue à la liaison filaire USB-C. */
private const val SCAN_USB_LINK_ADDRESS = "USB-C"

/** Opacité du filet du badge de liaison (décoratif : l'état est porté par l'icône et le mot). */
private const val SCAN_LINK_BADGE_BORDER_ALPHA = 0.5f

/** Opacité d'une ligne d'appareil pendant une connexion (lignes inactives). */
private const val SCAN_DEVICE_DISABLED_ALPHA = 0.6f

// Tailles d'icônes et de repères propres à cet écran (la grille générale vient de OssmDimens).
private val ScanLogoSize = 48.dp
private val ScanIconSmall = 20.dp
private val ScanIconMedium = 24.dp
private val ScanIconDevice = 28.dp
private val ScanIconHero = 44.dp
private val ScanSpinnerStroke = 2.dp
private val ScanSpinnerStrokeLarge = 4.dp
private val ScanDeviceRowMinHeight = 64.dp
private val ScanShareButtonMinHeight = 52.dp

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

    // Scan automatique au premier affichage.
    LaunchedEffect(Unit) {
        if (!isConnected && !isConnecting && !isScanning) onScan()
    }

    // Connexion automatique à l'unique OSSM trouvé, uniquement pendant un scan.
    val autoConnectAddress = scanAutoConnectAddress(devices, connectionState)
    LaunchedEffect(autoConnectAddress) {
        autoConnectAddress?.let(onConnect)
    }

    // États vides : jamais d'écran blanc pendant un scan ou après un scan sans résultat.
    val emptyState = if (devices.isEmpty()) {
        when {
            isScanning -> ScanEmptyState.SEARCHING
            connectionState is BleConnectionState.Disconnected -> ScanEmptyState.NONE_FOUND
            connectionState is BleConnectionState.Error -> ScanEmptyState.NONE_FOUND
            else -> null
        }
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OssmBackground)
    ) {
        // Toute la page défile : rien n'est coupé sur petit écran ni à 130 % de taille de police.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("scan_screen"),
            contentPadding = PaddingValues(OssmDimens.ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
        ) {
            item(key = "header") { ScanHeader(connectionState) }

            item(key = "scan_action") {
                ScanActionButton(
                    isScanning = isScanning,
                    enabled = !isConnecting && !isConnected,
                    onScan = onScan,
                    onStop = onStop
                )
            }

            // Détail de la liaison : connecté, connexion en cours ou erreur.
            when (connectionState) {
                is BleConnectionState.Connected ->
                    item(key = "status_connected") { ScanConnectedCard(connectionState) }
                is BleConnectionState.Connecting ->
                    item(key = "status_connecting") { ScanConnectingCard() }
                is BleConnectionState.Error ->
                    item(key = "status_error") { ScanErrorCard(connectionState.message) }
                else -> Unit
            }

            // Caméra de la session distante (côté propriétaire) : TOUJOURS visible (2.3.16),
            // placée au-dessus de la liste pour qu'un long résultat de scan ne la repousse pas.
            item(key = "camera") {
                RemoteCameraCard(
                    section = remoteCamera,
                    onShareMyCamera = onShareMyCamera,
                    onStartRemoteSession = onStartRemoteSession,
                    onRegenerateRemoteLink = onRegenerateRemoteLink,
                )
            }

            if (devices.isNotEmpty()) {
                item(key = "devices_title") {
                    Text(
                        stringResource(R.string.scan_found_count, devices.size),
                        color = OssmOnSurfaceMuted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                items(devices, key = { it.address }) { device ->
                    DeviceRow(
                        device = device,
                        isConnecting = isConnecting,
                        onClick = { onConnect(device.address) }
                    )
                }
            }

            if (emptyState != null) {
                item(key = "empty") { ScanEmptyStateCard(state = emptyState) }
            }
        }
    }
}

@Composable
private fun ScanHeader(connectionState: BleConnectionState) {
    Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(ScanLogoSize)
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Column {
                Text(
                    stringResource(R.string.conn_app_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OssmOnSurface,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    color = OssmOnSurfaceMuted,
                    style = OssmCaption
                )
            }
        }
        ConnectionLinkBadge(state = connectionState)
    }
}

/** Liaison courante : icône, mot (USB, Bluetooth, hors ligne...) et ton d'état. */
internal data class ConnectionLink(val icon: ImageVector, val labelRes: Int, val tone: OssmTone)

internal fun connectionLinkOf(state: BleConnectionState): ConnectionLink = when (state) {
    is BleConnectionState.Connected ->
        if (state.address == SCAN_USB_LINK_ADDRESS) {
            ConnectionLink(Icons.Filled.Usb, R.string.conn_link_usb, OssmTone.Ok)
        } else {
            ConnectionLink(Icons.Filled.BluetoothConnected, R.string.conn_link_bluetooth, OssmTone.Ok)
        }
    is BleConnectionState.Connecting ->
        ConnectionLink(Icons.Filled.BluetoothSearching, R.string.conn_link_connecting, OssmTone.Warn)
    is BleConnectionState.Scanning ->
        ConnectionLink(Icons.Filled.BluetoothSearching, R.string.conn_link_scanning, OssmTone.Warn)
    is BleConnectionState.Error ->
        ConnectionLink(Icons.Filled.Error, R.string.conn_link_error, OssmTone.Error)
    is BleConnectionState.EmergencyStop ->
        ConnectionLink(Icons.Filled.Warning, R.string.conn_link_emergency, OssmTone.Error)
    is BleConnectionState.Disconnected ->
        ConnectionLink(Icons.Filled.BluetoothDisabled, R.string.conn_link_offline, OssmTone.Neutral)
}

private fun linkToneColor(tone: OssmTone): Color = when (tone) {
    OssmTone.Neutral -> OssmOnSurfaceMuted
    OssmTone.Ok -> OssmConnected
    OssmTone.Warn -> OssmWarning
    OssmTone.Error -> OssmError
}

/** Indicateur de liaison (icône + mot), partagé avec l'écran Diagnostic. */
@Composable
internal fun ConnectionLinkBadge(state: BleConnectionState, modifier: Modifier = Modifier) {
    val link = connectionLinkOf(state)
    val label = stringResource(link.labelRes)
    val color = linkToneColor(link.tone)
    val description = stringResource(R.string.conn_link_cd, label)
    val shape = RoundedCornerShape(OssmDimens.FieldRadius)
    Row(
        modifier = modifier
            .testTag("connection_link_badge")
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, color.copy(alpha = SCAN_LINK_BADGE_BORDER_ALPHA), shape)
            .padding(horizontal = OssmDimens.Space12, vertical = OssmDimens.Space8)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
    ) {
        Icon(link.icon, contentDescription = null, tint = color, modifier = Modifier.size(ScanIconSmall))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** Action principale : scanner, ou arrêter le scan en cours. */
@Composable
private fun ScanActionButton(
    isScanning: Boolean,
    enabled: Boolean,
    onScan: () -> Unit,
    onStop: () -> Unit,
) {
    if (isScanning) {
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OssmDimens.TouchTarget)
                .testTag("scan_action"),
            shape = RoundedCornerShape(OssmDimens.ControlRadius),
            border = BorderStroke(OssmDimens.BorderWidth, OssmOutline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = OssmSurfaceRaised,
                contentColor = OssmOnSurface
            )
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(ScanIconSmall),
                color = OssmPrimary,
                strokeWidth = ScanSpinnerStroke
            )
            Spacer(Modifier.width(OssmDimens.Space8))
            Text(stringResource(R.string.scan_stop), fontWeight = FontWeight.Bold)
        }
    } else {
        OssmButton(
            text = stringResource(R.string.scan_devices),
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scan_action"),
            kind = OssmButtonKind.Primary,
            icon = Icons.Filled.Bluetooth,
            enabled = enabled
        )
    }
}

@Composable
private fun ScanConnectedCard(state: BleConnectionState.Connected) {
    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmConnected) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = OssmConnected,
                modifier = Modifier.size(ScanIconMedium)
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Column {
                Text(
                    stringResource(R.string.scan_connected),
                    color = OssmConnected,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    state.deviceName,
                    color = OssmOnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun ScanConnectingCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(ScanIconSmall),
                strokeWidth = ScanSpinnerStroke,
                color = OssmWarning
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Text(
                stringResource(R.string.scan_connecting),
                color = OssmWarning,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun ScanErrorCard(message: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), tint = OssmError) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Filled.Error,
                contentDescription = null,
                tint = OssmError,
                modifier = Modifier.size(ScanIconMedium)
            )
            Spacer(Modifier.width(OssmDimens.Space12))
            Text(
                message,
                color = OssmError,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Ce que l'onglet Connexion montre pour la caméra de la session distante. */
sealed interface RemoteCameraSection {
    /** Pas de session distante active : la carte invite à en démarrer une (comme dans Réglages). */
    data object NoSession : RemoteCameraSection
    /** Session démarrée mais le lien hôte n'est pas encore connu. */
    data object Starting : RemoteCameraSection
    /** Session active mais le serveur n'a donné aucun lien caméra (ancien site ou lien antérieur à 2.3.15). */
    data object Unavailable : RemoteCameraSection
    /** Invitation caméra seule ; c'est un secret, donc [toString] ne l'affiche jamais. */
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

/** Copie explicite uniquement ; marquée sensible pour qu'Android 13+ n'affiche pas le lien. */
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
    GlassCard(modifier = Modifier.fillMaxWidth().testTag("remote_camera_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = OssmOnSurfaceMuted,
                    modifier = Modifier.size(ScanIconMedium)
                )
                Text(
                    stringResource(R.string.ui2_camera_section_title).uppercase(),
                    color = OssmOnSurfaceMuted,
                    style = OssmSectionTitle,
                    modifier = Modifier.semantics { heading() },
                )
            }
            when (section) {
                RemoteCameraSection.NoSession -> {
                    Text(
                        stringResource(R.string.ui2_camera_no_session),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OssmButton(
                        text = stringResource(R.string.ui2_camera_start_session),
                        onClick = onStartRemoteSession,
                        modifier = Modifier.fillMaxWidth(),
                        kind = OssmButtonKind.Primary,
                        icon = Icons.Filled.PlayArrow,
                    )
                }
                RemoteCameraSection.Starting -> OssmStatusLine(
                    text = stringResource(R.string.ui2_camera_session_starting),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    tone = OssmTone.Neutral,
                )
                RemoteCameraSection.Unavailable -> {
                    OssmStatusLine(
                        text = stringResource(R.string.ui2_camera_regenerate_needed),
                        tone = OssmTone.Warn,
                    )
                    OssmButton(
                        text = stringResource(R.string.ui2_camera_regenerate),
                        onClick = onRegenerateRemoteLink,
                        modifier = Modifier.fillMaxWidth(),
                        kind = OssmButtonKind.Secondary,
                        icon = Icons.Filled.Refresh,
                    )
                }
                is RemoteCameraSection.Available -> {
                    var copied by remember(section.cameraUrl) { mutableStateOf(false) }
                    Text(
                        stringResource(R.string.ui2_camera_section_body),
                        color = OssmOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OssmButton(
                        text = stringResource(R.string.ui2_camera_share_mine),
                        onClick = onShareMyCamera,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ScanShareButtonMinHeight),
                        kind = OssmButtonKind.Primary,
                        icon = Icons.Filled.Videocam,
                    )
                    OssmButton(
                        text = stringResource(R.string.ui2_camera_invite),
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, section.cameraUrl)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.ui2_camera_invite_chooser)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        kind = OssmButtonKind.Secondary,
                        icon = Icons.Filled.Share,
                    )
                    OssmButton(
                        text = stringResource(R.string.ui2_camera_copy),
                        onClick = {
                            copyCameraLink(context, context.getString(R.string.ui2_camera_invite_chooser), section.cameraUrl)
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        kind = OssmButtonKind.Secondary,
                        icon = Icons.Filled.ContentCopy,
                    )
                    if (copied) {
                        OssmStatusLine(
                            text = stringResource(R.string.ui2_camera_copied),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            tone = OssmTone.Ok,
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
    GlassCard(modifier = modifier.fillMaxWidth().testTag("scan_empty_state")) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
        ) {
            when (state) {
                ScanEmptyState.SEARCHING -> CircularProgressIndicator(
                    modifier = Modifier.size(ScanIconHero),
                    color = OssmPrimary,
                    strokeWidth = ScanSpinnerStrokeLarge
                )
                ScanEmptyState.NONE_FOUND -> Icon(
                    Icons.Filled.BluetoothSearching,
                    contentDescription = null,
                    tint = OssmOnSurfaceMuted,
                    modifier = Modifier.size(ScanIconHero)
                )
            }
            Text(
                title,
                color = OssmOnSurface,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                body,
                color = OssmOnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            HorizontalDivider(color = OssmGlassBorder)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                Text(
                    stringResource(R.string.ui2_scan_tips_title),
                    color = OssmOnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
                ScanTip(Icons.Filled.Power, stringResource(R.string.ui2_scan_tip_power))
                ScanTip(Icons.Filled.NearMe, stringResource(R.string.ui2_scan_tip_close))
                ScanTip(Icons.Filled.Bluetooth, stringResource(R.string.ui2_scan_tip_bluetooth))
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
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space12)
    ) {
        Icon(icon, contentDescription = null, tint = OssmOnSurfaceMuted, modifier = Modifier.size(ScanIconMedium))
        Text(text, color = OssmOnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    val quality = scanSignalQuality(device.rssi)
    val qualityColor = scanSignalColor(quality)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scan_device_${device.address}")
            .alpha(if (isConnecting) SCAN_DEVICE_DISABLED_ALPHA else 1f)
            .clip(shape)
            .background(OssmSurfaceRaised)
            .border(OssmDimens.BorderWidth, if (device.isOssm) OssmPrimary else OssmOutline, shape)
            .clickable(
                enabled = !isConnecting,
                onClickLabel = stringResource(R.string.conn_device_connect_action),
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = ScanDeviceRowMinHeight)
            .padding(OssmDimens.Space12),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (device.isOssm) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothSearching,
            contentDescription = null,
            tint = if (device.isOssm) OssmPrimary else OssmOnSurfaceMuted,
            modifier = Modifier.size(ScanIconDevice)
        )
        Spacer(Modifier.width(OssmDimens.Space12))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
            ) {
                Text(
                    device.name,
                    color = OssmOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (device.isOssm) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (device.isOssm) ScanOssmBadge()
            }
            Text(device.address, color = OssmOnSurfaceMuted, style = OssmCaption)
        }
        Spacer(Modifier.width(OssmDimens.Space8))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.conn_device_rssi, device.rssi),
                color = qualityColor,
                style = OssmValue
            )
            Text(stringResource(quality.labelRes), color = qualityColor, style = OssmCaption)
        }
    }
}

/** Pastille « OSSM » : repère les machines OSSM parmi les appareils Bluetooth. */
@Composable
private fun ScanOssmBadge() {
    Text(
        stringResource(R.string.conn_badge_ossm),
        color = OssmPrimary,
        style = OssmCaption,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(OssmDimens.BorderWidth, OssmPrimary, RoundedCornerShape(OssmDimens.FieldRadius))
            .padding(horizontal = OssmDimens.Space8, vertical = OssmDimens.Space4)
    )
}

/** Qualité du signal : un mot et un nombre en plus de la couleur. */
private enum class ScanSignalQuality(val labelRes: Int) {
    STRONG(R.string.conn_device_signal_strong),
    MEDIUM(R.string.conn_device_signal_medium),
    WEAK(R.string.conn_device_signal_weak),
}

private fun scanSignalQuality(rssi: Int) = when {
    rssi > -60 -> ScanSignalQuality.STRONG
    rssi > -75 -> ScanSignalQuality.MEDIUM
    else -> ScanSignalQuality.WEAK
}

private fun scanSignalColor(quality: ScanSignalQuality): Color = when (quality) {
    ScanSignalQuality.STRONG -> OssmConnected
    ScanSignalQuality.MEDIUM -> OssmWarning
    ScanSignalQuality.WEAK -> OssmError
}

/** Un partage de caméra ouvert ne reste permis que tant que la session offre exactement ce lien caméra. */
internal fun cameraShareStillAllowed(openedCameraUrl: String, section: RemoteCameraSection): Boolean =
    (section as? RemoteCameraSection.Available)?.cameraUrl == openedCameraUrl

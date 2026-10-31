package com.ossm.remote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.ossm.remote.R
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmWarning

/** Adresse que la liaison USB-C publie dans l'état Connected (même valeur que côté BleManager). */
private const val USB_LINK_ADDRESS = "USB-C"

/** Alpha le plus bas de la pulsation d'activité (scan, connexion). */
private const val PULSE_LOW_ALPHA = 0.45f

/** Icône, couleur et libellé d'un état de connexion : l'état se lit sans la couleur. */
private class BleAppearance(val icon: ImageVector, val color: Color, val label: String)

@Composable
private fun bleAppearance(state: BleConnectionState): BleAppearance = when (state) {
    is BleConnectionState.Connected ->
        if (state.address == USB_LINK_ADDRESS) {
            BleAppearance(Icons.Filled.Usb, OssmConnected, stringResource(R.string.shell_ble_usb))
        } else {
            BleAppearance(
                Icons.Filled.BluetoothConnected,
                OssmConnected,
                stringResource(R.string.shell_ble_connected, state.deviceName)
            )
        }
    is BleConnectionState.Scanning ->
        BleAppearance(Icons.Filled.BluetoothSearching, OssmWarning, stringResource(R.string.shell_ble_scanning))
    is BleConnectionState.Connecting ->
        BleAppearance(
            Icons.Filled.Bluetooth,
            OssmWarning,
            stringResource(R.string.shell_ble_connecting, state.deviceName)
        )
    is BleConnectionState.Error ->
        BleAppearance(Icons.Filled.ErrorOutline, OssmError, stringResource(R.string.shell_ble_error, state.message))
    is BleConnectionState.EmergencyStop ->
        BleAppearance(Icons.Filled.StopCircle, OssmError, stringResource(R.string.shell_ble_emergency))
    is BleConnectionState.Disconnected ->
        BleAppearance(Icons.Filled.BluetoothDisabled, OssmOnSurfaceMuted, stringResource(R.string.shell_ble_disconnected))
}

/**
 * Indicateur de connexion : icône + mot, coloré (vert connecté, ambre en cours, rouge erreur,
 * gris hors ligne). La pulsation de l'icône ne signale que l'activité (scan, connexion).
 */
@Composable
fun BleStatusIndicator(
    state: BleConnectionState,
    modifier: Modifier = Modifier
) {
    val appearance = bleAppearance(state)
    val tint by animateColorAsState(
        targetValue = appearance.color,
        animationSpec = tween(200),
        label = "ble_status_color"
    )

    val pulse = rememberInfiniteTransition(label = "ble_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_LOW_ALPHA,
        animationSpec = infiniteRepeatable(
            tween(700, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "ble_pulse_alpha"
    )
    val shouldPulse = state is BleConnectionState.Scanning || state is BleConnectionState.Connecting

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8),
        modifier = modifier
    ) {
        Icon(
            imageVector = appearance.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(OssmDimens.Space24)
                .graphicsLayer { alpha = if (shouldPulse) pulseAlpha else 1f }
        )
        Text(
            text = appearance.label,
            color = tint,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

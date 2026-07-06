package com.ossm.remote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.model.BleConnectionState
import com.ossm.remote.model.label
import com.ossm.remote.ui.theme.*

@Composable
fun BleStatusIndicator(
    state: BleConnectionState,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = when (state) {
            is BleConnectionState.Connected   -> OssmConnected
            is BleConnectionState.Scanning    -> OssmWarning
            is BleConnectionState.Connecting  -> OssmWarning
            is BleConnectionState.Error       -> OssmError
            is BleConnectionState.EmergencyStop -> OssmStop
            else -> Color.Gray
        },
        animationSpec = tween(400),
        label = "ble_dot_color"
    )

    val pulse = rememberInfiniteTransition(label = "ble_pulse")
    val dotScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = EaseInOutSine),
            RepeatMode.Reverse
        ),
        label = "ble_dot_scale"
    )
    val shouldPulse = state is BleConnectionState.Scanning || state is BleConnectionState.Connecting

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(if (shouldPulse) dotScale else 1f)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = state.label(),
            color = dotColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

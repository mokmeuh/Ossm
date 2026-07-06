package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight

/**
 * Speed bar for Progressif mode.
 * - Purple fill = current (auto-ramping) speed — read only.
 * - Red bar = the maximum the ramp climbs to — draggable by the user.
 */
@Composable
fun ProgressiveSpeedBar(
    currentValue: Float,   // 0..1, the live ramping speed (purple)
    maxValue: Float,       // 0..1, the ceiling (red)
    enabled: Boolean,
    onMaxChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    fun fractionFromX(x: Float): Float =
        if (widthPx <= 0) 0f else (x / widthPx).coerceIn(0f, 1f)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "VITESSE (auto)",
                    color = OssmPrimaryLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    "${(currentValue * 100).toInt()}%  •  max ${(maxValue * 100).toInt()}%",
                    color = if (enabled) OssmOnSurface else OssmOnSurface.copy(0.4f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(vertical = 12.dp)
                    .onSizeChanged { widthPx = it.width }
                    .pointerInput(enabled, widthPx) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { offset -> onMaxChange(fractionFromX(offset.x)) }
                    }
                    .pointerInput(enabled, widthPx) {
                        if (!enabled) return@pointerInput
                        detectDragGestures { change, _ -> onMaxChange(fractionFromX(change.position.x)) }
                    }
            ) {
                // Inactive track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(OssmPrimary.copy(alpha = 0.18f))
                )
                // Purple fill = current speed
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentValue.coerceIn(0f, 1f))
                        .height(8.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (enabled) OssmPrimary else OssmPrimary.copy(0.4f))
                )
                // Red ceiling bar (draggable)
                val redX = with(density) { (widthPx * maxValue.coerceIn(0f, 1f)).toDp() }
                Box(
                    modifier = Modifier
                        .offset(x = redX - 3.dp)
                        .width(6.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (enabled) OssmError else OssmError.copy(0.4f))
                )
            }
        }
    }
}

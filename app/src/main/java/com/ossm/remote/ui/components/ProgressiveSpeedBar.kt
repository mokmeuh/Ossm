package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmPrimary
import kotlin.math.abs

/**
 * Speed bar for Progressif mode.
 * - Fill = current (auto-ramping) speed, read only.
 * - Red bar = the maximum the ramp climbs to, draggable. A press within the grab radius
 *   takes the red bar where it is; farther away it jumps to the finger.
 */
@Composable
fun ProgressiveSpeedBar(
    currentValue: Float,   // 0..1, the live ramping speed (fill)
    maxValue: Float,       // 0..1, the ceiling (red)
    enabled: Boolean,
    onMaxChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "VITESSE (auto)",
    fillColor: Color = OssmPrimary
) {
    val metrics = LocalControlSliderMetrics.current
    val latestMax = rememberUpdatedState(maxValue)
    val onChange = rememberUpdatedState(onMaxChange)
    val valueText = "${(currentValue * 100).toInt()}% · max ${(maxValue * 100).toInt()}%"
    SliderFrame(
        label = label,
        displayValue = valueText,
        enabled = enabled,
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(metrics.sliderHeight)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    if (!enabled) disabled()
                }
                .pointerInput(enabled, metrics) {
                    if (!enabled) return@pointerInput
                    val insetPx = metrics.trackInset.toPx()
                    val grabPx = metrics.grabRadius.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val usable = (size.width - 2f * insetPx).coerceAtLeast(1f)
                        val barX = insetPx + usable * latestMax.value.coerceIn(0f, 1f)
                        val base = if (abs(down.position.x - barX) <= grabPx) {
                            latestMax.value
                        } else {
                            ((down.position.x - insetPx) / usable).coerceIn(0f, 1f).also { onChange.value(it) }
                        }
                        val originX = down.position.x
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.position.x != change.previousPosition.x) {
                                onChange.value((base + (change.position.x - originX) / usable).coerceIn(0f, 1f))
                            }
                            change.consume()
                        }
                    }
                }
        ) {
            val centerY = size.height / 2f
            val inset = metrics.trackInset.toPx()
            val trackWidth = (size.width - 2f * inset).coerceAtLeast(0f)
            val trackHeight = metrics.trackHeight.toPx()
            val radius = trackHeight / 2f
            drawRoundRect(
                color = fillColor.copy(alpha = if (enabled) 0.22f else 0.12f),
                topLeft = Offset(inset, centerY - radius),
                size = Size(trackWidth, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            drawRoundRect(
                color = if (enabled) fillColor else fillColor.copy(alpha = 0.4f),
                topLeft = Offset(inset, centerY - radius),
                size = Size(trackWidth * currentValue.coerceIn(0f, 1f), trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            val barX = inset + trackWidth * maxValue.coerceIn(0f, 1f)
            val thumbWidth = metrics.thumbWidth.toPx()
            val thumbHeight = metrics.thumbHeight.toPx()
            drawRoundRect(
                color = if (enabled) OssmError else OssmError.copy(alpha = 0.4f),
                topLeft = Offset(barX - thumbWidth / 2f, centerY - thumbHeight / 2f),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbWidth / 2f)
            )
        }
    }
}

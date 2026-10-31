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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import com.ossm.remote.R
import com.ossm.remote.ui.modes.LocalModeContext
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmWarning
import kotlin.math.abs

/**
 * Speed bar for Progressif mode.
 * - Fill = current (auto-ramping) speed, read only.
 * - Round handle = the maximum the ramp climbs to, draggable. A press within the grab radius
 *   takes the handle where it is; farther away it jumps to the finger. The tinted part of the
 *   track (0 to the handle) is the headroom the ramp may use.
 *
 * [limited] marks a ceiling imposed by a remote session: padlock, word « limité » and an amber
 * handle, never the color alone. It does not change what the gesture does.
 */
@Composable
fun ProgressiveSpeedBar(
    currentValue: Float,   // 0..1, the live ramping speed (fill)
    maxValue: Float,       // 0..1, the ceiling (handle)
    enabled: Boolean,
    onMaxChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.sld_speed_auto),
    fillColor: Color = OssmPrimary,
    limited: Boolean = LocalModeContext.current.remoteLimited
) {
    val metrics = LocalControlSliderMetrics.current
    val latestMax = rememberUpdatedState(maxValue)
    val onChange = rememberUpdatedState(onMaxChange)
    val currentPercent = (currentValue * 100).toInt()
    val maxPercent = (maxValue * 100).toInt()
    val valueText = stringResource(R.string.sld_progressive_value, currentPercent, maxPercent)
    val baseState = stringResource(R.string.sld_progressive_state, currentPercent, maxPercent)
    val stateText = if (limited) "$baseState, ${stringResource(R.string.sld_limited)}" else baseState
    val raiseLabel = stringResource(R.string.sld_progressive_max_up)
    val lowerLabel = stringResource(R.string.sld_progressive_max_down)
    SliderFrame(
        label = label,
        displayValue = valueText,
        enabled = enabled,
        modifier = modifier,
        channelColor = fillColor,
        limited = limited
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(metrics.sliderHeight)
                .semantics {
                    contentDescription = label
                    stateDescription = stateText
                    // Le réglable est le maximum (la poignée) ; la vitesse courante est lue dans l'état.
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        (maxValue * 100f).coerceIn(0f, 100f),
                        0f..100f
                    )
                    if (!enabled) disabled()
                    if (enabled) {
                        // Même rappel que le glissé : le maximum est transmis à chaque changement.
                        setProgress { target ->
                            onChange.value((target / 100f).coerceIn(0f, 1f))
                            true
                        }
                        // Pas fins de 1 % (le geste TalkBack standard avance par paliers plus larges).
                        customActions = listOf(
                            CustomAccessibilityAction(raiseLabel) {
                                onChange.value((latestMax.value + 0.01f).coerceIn(0f, 1f))
                                true
                            },
                            CustomAccessibilityAction(lowerLabel) {
                                onChange.value((latestMax.value - 0.01f).coerceIn(0f, 1f))
                                true
                            }
                        )
                    }
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
            val alpha = if (enabled) 1f else 0.4f
            drawSliderTrack(inset, centerY, trackWidth, trackHeight, enabled)
            // Marge de manoeuvre : de 0 jusqu'au maximum, en teinte du canal.
            val maxFraction = maxValue.coerceIn(0f, 1f)
            drawRoundRect(
                color = fillColor.copy(alpha = 0.30f * alpha),
                topLeft = Offset(inset, centerY - radius),
                size = Size(trackWidth * maxFraction, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            // Vitesse courante (lecture seule).
            drawRoundRect(
                color = fillColor.copy(alpha = alpha),
                topLeft = Offset(inset, centerY - radius),
                size = Size(trackWidth * currentValue.coerceIn(0f, 1f), trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            // Poignée du maximum : ambre quand le plafond est imposé par la session distante.
            drawSliderThumb(
                center = Offset(inset + trackWidth * maxFraction, centerY),
                radiusPx = metrics.thumbWidth.toPx() / 2f,
                color = if (limited) OssmWarning else fillColor,
                alpha = alpha
            )
        }
    }
}

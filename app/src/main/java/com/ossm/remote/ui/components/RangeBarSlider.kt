package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ossm.remote.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** A thin vertical line drawn over the track (live position, random value in use, ...). */
data class RangeBarMarker(val fraction: Float, val color: Color)

/**
 * Two-bar range control: "-" button, track with a low and a high bar, "+" button.
 * The -/+ buttons move the bar touched last (the high bar by default).
 */
@Composable
fun RangeBarControl(
    start: Float,
    end: Float,
    enabled: Boolean,
    activeColor: Color,
    label: String,
    onRangeChange: (Float, Float) -> Unit,
    onRangeCommit: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    markers: List<RangeBarMarker> = emptyList(),
    snapValue: Float? = null,
    stepFraction: Float = 0.01f,
    valueText: (Float) -> String = { "${(it * 100f).roundToInt()}%" },
    gestureCancellationKey: Long = 0L,
    bipolar: BipolarStyle? = null
) {
    val metrics = LocalControlSliderMetrics.current
    var selectedIsEnd by remember { mutableStateOf(true) }
    val decrease = stringResource(R.string.control_slider_decrease, label)
    val increase = stringResource(R.string.control_slider_increase, label)
    fun step(sign: Int) {
        if (selectedIsEnd) {
            onRangeCommit(start, (end + sign * stepFraction).coerceIn(start, 1f))
        } else {
            onRangeCommit((start + sign * stepFraction).coerceIn(0f, end), end)
        }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RangeStepButton("−", decrease, enabled, activeColor) { step(-1) }
        RangeBarSlider(
            start = start,
            end = end,
            enabled = enabled,
            activeColor = activeColor,
            label = label,
            selectedIsEnd = selectedIsEnd,
            onSelectEnd = { selectedIsEnd = it },
            onRangeChange = onRangeChange,
            onRangeCommit = onRangeCommit,
            modifier = Modifier.weight(1f),
            markers = markers,
            snapValue = snapValue,
            valueText = valueText,
            gestureCancellationKey = gestureCancellationKey,
            bipolar = bipolar
        )
        RangeStepButton("+", increase, enabled, activeColor) { step(1) }
    }
}

@Composable
private fun RangeStepButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val metrics = LocalControlSliderMetrics.current
    Box(
        modifier = Modifier
            .size(metrics.buttonSize)
            .clip(CircleShape)
            .background(activeColor.copy(alpha = if (enabled) 0.18f else 0.06f))
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled() else onClick { onClick(); true }
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = if (enabled) activeColor else activeColor.copy(alpha = 0.3f),
            fontSize = metrics.symbolSize,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun RangeBarSlider(
    start: Float,
    end: Float,
    enabled: Boolean,
    activeColor: Color,
    label: String,
    selectedIsEnd: Boolean,
    onSelectEnd: (Boolean) -> Unit,
    onRangeChange: (Float, Float) -> Unit,
    onRangeCommit: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    markers: List<RangeBarMarker> = emptyList(),
    snapValue: Float? = null,
    valueText: (Float) -> String = { "${(it * 100f).roundToInt()}%" },
    gestureCancellationKey: Long = 0L,
    bipolar: BipolarStyle? = null
) {
    val metrics = LocalControlSliderMetrics.current
    val haptics = LocalHapticFeedback.current
    val latestRange = rememberUpdatedState(start..end)
    val onChange = rememberUpdatedState(onRangeChange)
    val onCommit = rememberUpdatedState(onRangeCommit)
    val onSelect = rememberUpdatedState(onSelectEnd)
    val latestSnap = rememberUpdatedState(snapValue)
    var draft by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val shown = draft ?: (start..end)
    val stateText = "${valueText(shown.start)} – ${valueText(shown.endInclusive)}"

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.sliderHeight)
            .semantics {
                contentDescription = label
                stateDescription = stateText
                if (!enabled) disabled()
            }
            .pointerInput(enabled, metrics, gestureCancellationKey) {
                if (!enabled) return@pointerInput
                val insetPx = metrics.trackInset.toPx()
                val grabPx = metrics.grabRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val usable = (size.width - 2f * insetPx).coerceAtLeast(1f)
                    fun xOf(fraction: Float) = insetPx + usable * fraction
                    fun fractionOf(x: Float) = ((x - insetPx) / usable).coerceIn(0f, 1f)
                    val initial = latestRange.value
                    val distStart = abs(down.position.x - xOf(initial.start))
                    val distEnd = abs(down.position.x - xOf(initial.endInclusive))
                    val dragEnd = if (initial.endInclusive - initial.start < 0.001f) {
                        down.position.x >= xOf(initial.endInclusive)
                    } else {
                        distEnd <= distStart
                    }
                    // Within the grab radius the bar is taken where it is (no jump).
                    val grabbed = minOf(distStart, distEnd) <= grabPx
                    onSelect.value(dragEnd)
                    var current = initial
                    var snapped = false
                    fun applyFraction(raw: Float) {
                        val target = latestSnap.value
                        val inside = target != null && abs(raw - target) <= SNAP_RADIUS_PERCENT / 100f
                        if (inside && !snapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        snapped = inside
                        val fraction = if (inside) target!! else raw
                        current = if (dragEnd) {
                            current.start..fraction.coerceAtLeast(current.start)
                        } else {
                            fraction.coerceAtMost(current.endInclusive)..current.endInclusive
                        }
                        draft = current
                        onChange.value(current.start, current.endInclusive)
                    }
                    val originX = down.position.x
                    val baseValue = if (grabbed) {
                        if (dragEnd) initial.endInclusive else initial.start
                    } else {
                        fractionOf(originX)
                    }
                    var moved = false
                    if (!grabbed) {
                        applyFraction(baseValue)
                        moved = true
                    }
                    down.consume()
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                released = true
                                break
                            }
                            if (change.position.x != change.previousPosition.x) {
                                applyFraction((baseValue + (change.position.x - originX) / usable).coerceIn(0f, 1f))
                                moved = true
                            }
                            change.consume()
                        }
                    } finally {
                        // A cancelled gesture (STOP, disable, recomposition) never commits.
                        val committed = current
                        draft = null
                        if (released && moved) onCommit.value(committed.start, committed.endInclusive)
                    }
                }
            }
    ) {
        val centerY = size.height / 2f
        val inset = metrics.trackInset.toPx()
        val trackWidth = (size.width - 2f * inset).coerceAtLeast(0f)
        val trackHeight = metrics.trackHeight.toPx()
        val radius = trackHeight / 2f
        fun xOf(fraction: Float) = inset + trackWidth * fraction.coerceIn(0f, 1f)
        drawRoundRect(
            color = activeColor.copy(alpha = if (enabled) 0.28f else 0.16f),
            topLeft = Offset(inset, centerY - radius),
            size = Size(trackWidth, trackHeight),
            cornerRadius = CornerRadius(radius)
        )
        val activeStart = xOf(shown.start)
        val activeEnd = xOf(shown.endInclusive)
        val alpha = if (enabled) 1f else 0.4f
        if (bipolar == null) {
            drawRoundRect(
                color = activeColor.copy(alpha = alpha),
                topLeft = Offset(activeStart, centerY - radius),
                size = Size((activeEnd - activeStart).coerceAtLeast(0f), trackHeight),
                cornerRadius = CornerRadius(radius)
            )
        } else {
            // Signed range: the part below the center is red, the part above is green.
            val centerX = xOf(bipolar.centerPercent / 100f)
            val negEnd = minOf(activeEnd, centerX)
            if (negEnd > activeStart) drawRoundRect(
                color = bipolar.negativeColor.copy(alpha = alpha),
                topLeft = Offset(activeStart, centerY - radius),
                size = Size(negEnd - activeStart, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            val posStart = maxOf(activeStart, centerX)
            if (activeEnd > posStart) drawRoundRect(
                color = bipolar.positiveColor.copy(alpha = alpha),
                topLeft = Offset(posStart, centerY - radius),
                size = Size(activeEnd - posStart, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            val tickHalf = metrics.thumbHeight.toPx() * 0.28f
            drawLine(
                color = bipolar.centerTickColor.copy(alpha = alpha),
                start = Offset(centerX, centerY - tickHalf),
                end = Offset(centerX, centerY + tickHalf),
                strokeWidth = 2.dp.toPx()
            )
        }
        // Discreet halo on the bar the -/+ buttons move.
        drawCircle(
            color = activeColor.copy(alpha = 0.22f),
            radius = metrics.thumbHeight.toPx() * 0.34f,
            center = Offset(if (selectedIsEnd) activeEnd else activeStart, centerY)
        )
        markers.forEach { marker ->
            val x = xOf(marker.fraction)
            drawLine(
                color = marker.color.copy(alpha = if (enabled) 0.95f else 0.4f),
                start = Offset(x, centerY - metrics.thumbHeight.toPx() * 0.4f),
                end = Offset(x, centerY + metrics.thumbHeight.toPx() * 0.4f),
                strokeWidth = 3.dp.toPx()
            )
        }
        val thumbWidth = metrics.thumbWidth.toPx()
        val thumbHeight = metrics.thumbHeight.toPx()
        listOf(shown.start to activeStart, shown.endInclusive to activeEnd).forEach { (value, x) ->
            val barColor = when {
                bipolar == null -> activeColor
                value * 100f > bipolar.centerPercent + 0.05f -> bipolar.positiveColor
                value * 100f < bipolar.centerPercent - 0.05f -> bipolar.negativeColor
                else -> activeColor
            }
            drawRoundRect(
                color = barColor.copy(alpha = if (enabled) 1f else 0.35f),
                topLeft = Offset(x - thumbWidth / 2f, centerY - thumbHeight / 2f),
                size = Size(thumbWidth, thumbHeight),
                cornerRadius = CornerRadius(thumbWidth / 2f)
            )
        }
    }
}

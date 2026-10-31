package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmSurfaceRaised
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
    bipolar: BipolarStyle? = null,
    /** Mot ajouté à l'état lu par TalkBack (ex. « limité »). Sans effet visuel ni sur le geste. */
    stateSuffix: String? = null
) {
    val metrics = LocalControlSliderMetrics.current
    // Couleur du canal : la sensation (plage signée) porte la sienne.
    val channelColor = bipolar?.channelColor ?: activeColor
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
        horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)
    ) {
        RangeStepButton(Icons.Filled.Remove, decrease, enabled, channelColor) { step(-1) }
        RangeBarSlider(
            start = start,
            end = end,
            enabled = enabled,
            activeColor = channelColor,
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
            bipolar = bipolar,
            stateSuffix = stateSuffix
        )
        RangeStepButton(Icons.Filled.Add, increase, enabled, channelColor) { step(1) }
    }
}

@Composable
private fun RangeStepButton(
    icon: ImageVector,
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
            // Bouton rond secondaire : fond relevé et filet de contour, glyphe à la couleur du canal.
            .background(OssmSurfaceRaised)
            .border(
                width = OssmDimens.BorderWidth,
                color = if (enabled) OssmOutline else OssmGlassBorder,
                shape = CircleShape
            )
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled() else onClick { onClick(); true }
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) activeColor else activeColor.copy(alpha = 0.4f),
            modifier = Modifier.size(metrics.buttonSize * 0.45f)
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
    bipolar: BipolarStyle? = null,
    /** Mot ajouté à l'état lu par TalkBack (ex. « limité »). Sans effet visuel ni sur le geste. */
    stateSuffix: String? = null
) {
    val metrics = LocalControlSliderMetrics.current
    val haptics = LocalHapticFeedback.current
    val latestRange = rememberUpdatedState(start..end)
    val onChange = rememberUpdatedState(onRangeChange)
    val onCommit = rememberUpdatedState(onRangeCommit)
    val onSelect = rememberUpdatedState(onSelectEnd)
    val latestSnap = rememberUpdatedState(snapValue)
    val latestSelectedIsEnd = rememberUpdatedState(selectedIsEnd)
    var draft by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val shown = draft ?: (start..end)
    // Couleur du canal : la sensation (plage signée) porte la sienne.
    val channelColor = bipolar?.channelColor ?: activeColor
    // TalkBack : valeurs des deux bornes, borne réglée par setProgress et par les boutons −/+.
    val selectedBoundName = stringResource(if (selectedIsEnd) R.string.sld_bound_high else R.string.sld_bound_low)
    val baseStateText = stringResource(
        R.string.sld_range_state,
        valueText(shown.start),
        valueText(shown.endInclusive),
        selectedBoundName
    )
    val stateText = if (stateSuffix != null) "$baseStateText, $stateSuffix" else baseStateText
    val selectLowLabel = stringResource(R.string.sld_select_low)
    val selectHighLabel = stringResource(R.string.sld_select_high)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.sliderHeight)
            .semantics {
                contentDescription = label
                stateDescription = stateText
                // La borne sélectionnée est celle que setProgress déplace.
                progressBarRangeInfo = ProgressBarRangeInfo(
                    ((if (selectedIsEnd) end else start) * 100f).coerceIn(0f, 100f),
                    0f..100f
                )
                if (!enabled) disabled()
                if (enabled) {
                    // Mêmes rappels que le glissé : on propose la plage puis on la valide, comme
                    // un doigt qui se déplace puis se relève.
                    setProgress { target ->
                        val fraction = (target / 100f).coerceIn(0f, 1f)
                        val range = latestRange.value
                        val next = if (latestSelectedIsEnd.value) {
                            range.start..fraction.coerceAtLeast(range.start)
                        } else {
                            fraction.coerceAtMost(range.endInclusive)..range.endInclusive
                        }
                        onChange.value(next.start, next.endInclusive)
                        onCommit.value(next.start, next.endInclusive)
                        true
                    }
                    // Choix de la borne à régler (équivalent d'un appui sur la barre concernée).
                    customActions = listOf(
                        CustomAccessibilityAction(selectLowLabel) {
                            onSelect.value(false)
                            true
                        },
                        CustomAccessibilityAction(selectHighLabel) {
                            onSelect.value(true)
                            true
                        }
                    )
                }
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
        drawSliderTrack(inset, centerY, trackWidth, trackHeight, enabled)
        val activeStart = xOf(shown.start)
        val activeEnd = xOf(shown.endInclusive)
        val alpha = if (enabled) 1f else 0.4f
        if (bipolar == null) {
            drawRoundRect(
                color = channelColor.copy(alpha = alpha),
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
            val tickHalf = metrics.thumbHeight.toPx() * 0.36f
            drawLine(
                color = bipolar.centerTickColor.copy(alpha = alpha),
                start = Offset(centerX, centerY - tickHalf),
                end = Offset(centerX, centerY + tickHalf),
                strokeWidth = 2.dp.toPx()
            )
        }
        markers.forEach { marker ->
            val x = xOf(marker.fraction)
            drawLine(
                color = marker.color.copy(alpha = if (enabled) 0.95f else 0.4f),
                start = Offset(x, centerY - metrics.thumbHeight.toPx() * 0.45f),
                end = Offset(x, centerY + metrics.thumbHeight.toPx() * 0.45f),
                strokeWidth = 3.dp.toPx()
            )
        }
        // Deux poignées rondes de 28 dp. Celle que les boutons −/+ déplacent est pleine, entourée
        // d'un halo et dessinée par-dessus ; l'autre est creuse (petit point de couleur) : la
        // différence tient à la forme, pas seulement à la teinte.
        val thumbRadius = metrics.thumbWidth.toPx() / 2f
        val thumbs = listOf(
            Triple(false, shown.start, activeStart),
            Triple(true, shown.endInclusive, activeEnd)
        ).sortedBy { (isEnd, _, _) -> isEnd == selectedIsEnd }
        thumbs.forEach { (isEnd, value, x) ->
            val barColor = when {
                bipolar == null -> channelColor
                value * 100f > bipolar.centerPercent + 0.05f -> bipolar.positiveColor
                value * 100f < bipolar.centerPercent - 0.05f -> bipolar.negativeColor
                else -> channelColor
            }
            val selected = isEnd == selectedIsEnd
            drawSliderThumb(
                center = Offset(x, centerY),
                radiusPx = thumbRadius,
                color = barColor,
                alpha = alpha,
                filled = selected,
                haloColor = if (selected) channelColor else null
            )
        }
    }
}

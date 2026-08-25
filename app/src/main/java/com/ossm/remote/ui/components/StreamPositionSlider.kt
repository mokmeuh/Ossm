package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal fun liveControlMinimumHeight(compact: Boolean): Dp = if (compact) 180.dp else 420.dp

internal fun liveControlMinimumWidth(compact: Boolean): Dp = 280.dp

internal fun liveSliderTrackThickness(compact: Boolean): Dp = if (compact) 52.dp else 72.dp

internal fun liveSliderThumbRadius(compact: Boolean): Dp = if (compact) 28.dp else 36.dp

internal data class LiveEndpointLabels(val top: Int, val bottom: Int)

internal fun liveEndpointLabels(): LiveEndpointLabels = LiveEndpointLabels(
    top = R.string.ctl_live_deep,
    bottom = R.string.ctl_live_home
)

@Composable
fun StreamPositionSlider(
    positionPercent: Int,
    enabled: Boolean,
    onPositionDrag: (Int) -> Unit,
    onTouchEnd: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillAvailable: Boolean = true
) {
    var draft by remember { mutableFloatStateOf(positionPercent.toFloat()) }
    val precisionEnabled = LocalControlPrecisionEnabled.current

    LaunchedEffect(positionPercent) {
        draft = positionPercent.toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fillAvailable) {
                    Modifier
                        .fillMaxHeight()
                        .heightIn(min = liveControlMinimumHeight(compact))
                        .widthIn(min = liveControlMinimumWidth(compact))
                } else {
                    Modifier.height(if (compact) 180.dp else 240.dp)
                }
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        OssmPrimary.copy(alpha = if (enabled) 0.16f else 0.06f),
                        OssmAccent.copy(alpha = if (enabled) 0.10f else 0.04f),
                        OssmGlass.copy(alpha = 0.36f)
                    )
                )
            )
            .border(
                1.dp,
                OssmPrimaryLight.copy(alpha = if (enabled) 0.5f else 0.15f),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(liveEndpointLabels().top),
                color = OssmPrimaryLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${draft.roundToInt()}%",
                color = if (enabled) OssmOnSurface else OssmOnSurface.copy(alpha = 0.35f),
                fontSize = if (compact) 20.sp else 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        LivePositionTrack(
            valuePercent = draft,
            enabled = enabled,
            activeColor = OssmAccent,
            onValueChange = {
                draft = it.coerceIn(0f, 100f)
                onPositionDrag(draft.roundToInt())
            },
            onTouchEnd = onTouchEnd,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            precisionEnabled = precisionEnabled,
            trackThickness = liveSliderTrackThickness(compact),
            thumbRadius = liveSliderThumbRadius(compact)
        )

        Text(
            stringResource(liveEndpointLabels().bottom),
            color = OssmOnSurface.copy(alpha = 0.72f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LivePositionTrack(
    valuePercent: Float,
    enabled: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    onValueChange: (Float) -> Unit,
    onTouchEnd: () -> Unit,
    modifier: Modifier = Modifier,
    precisionEnabled: Boolean,
    trackThickness: Dp,
    thumbRadius: Dp
) {
    val haptics = LocalHapticFeedback.current
    val gestureState = remember(precisionEnabled) {
        LiveSliderState(precisionEnabled = precisionEnabled)
    }
    val onChange = rememberUpdatedState(onValueChange)
    val onEnd = rememberUpdatedState(onTouchEnd)
    var draftPercent by remember { mutableFloatStateOf(valuePercent.coerceIn(0f, 100f)) }
    var dragging by remember { mutableStateOf(false) }
    var visibleRange by remember { mutableStateOf(0f..100f) }

    LaunchedEffect(valuePercent, dragging) {
        if (!dragging) draftPercent = valuePercent.coerceIn(0f, 100f)
    }
    LaunchedEffect(precisionEnabled) {
        if (!precisionEnabled) visibleRange = 0f..100f
    }
    LaunchedEffect(dragging, precisionEnabled) {
        while (dragging && precisionEnabled) {
            delay(25)
            if (gestureState.advanceTime(SystemClock.uptimeMillis())) {
                visibleRange = gestureState.visibleRange
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    val rangeLabel = if (visibleRange == 0f..100f) {
        stringResource(R.string.live_range_global)
    } else {
        stringResource(
            R.string.live_range_narrowed,
            visibleRange.start.roundToInt(),
            visibleRange.endInclusive.roundToInt()
        )
    }
    val stateLabel = stringResource(
        R.string.live_state_description,
        rangeLabel,
        draftPercent.roundToInt()
    )
    val span = visibleRange.endInclusive - visibleRange.start
    val displayedFraction = ((draftPercent - visibleRange.start) / span).coerceIn(0f, 1f)

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    stateDescription = stateLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(draftPercent, 0f..100f)
                    if (enabled) {
                        setProgress { target ->
                            val next = target.coerceIn(0f, 100f)
                            draftPercent = next
                            onChange.value(next)
                            true
                        }
                    }
                }
                .pointerInput(enabled, precisionEnabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val trackInsetPx = thumbRadius.toPx() + 6.dp.toPx()
                        val thumbY = liveThumbCenterY(
                            valuePercent = displayedFraction * 100f,
                            trackHeightPx = size.height.toFloat(),
                            trackInsetPx = trackInsetPx
                        )
                        if (!isLiveThumbHit(
                                pointerY = down.position.y,
                                thumbY = thumbY,
                                hitRadiusPx = thumbRadius.toPx() + 12.dp.toPx()
                            )
                        ) return@awaitEachGesture
                        down.consume()
                        draftPercent = gestureState.onDown(
                            valuePercent = draftPercent,
                            pointerY = down.position.y,
                            trackHeightPx = (size.height - 2f * trackInsetPx).coerceAtLeast(1f),
                            uptimeMillis = down.uptimeMillis
                        )
                        dragging = true
                        visibleRange = gestureState.visibleRange
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                gestureState.onMove(change.position.y, change.uptimeMillis)?.let { next ->
                                    draftPercent = next
                                    onChange.value(draftPercent)
                                }
                                change.consume()
                            }
                        } finally {
                            finishLiveTouchGesture(
                                gestureState = gestureState,
                                onTouchEnd = onEnd.value
                            )
                            dragging = false
                            visibleRange = gestureState.visibleRange
                        }
                    }
                }
        ) {
            val trackInset = thumbRadius.toPx() + 6.dp.toPx()
            val trackTop = trackInset
            val trackBottom = (size.height - trackInset).coerceAtLeast(trackTop)
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(0f)
            val thumbY = trackBottom - trackHeight * displayedFraction
            val horizontalInset = 8.dp.toPx()
            val trackLeft = horizontalInset
            val trackWidth = (size.width - horizontalInset * 2f).coerceAtLeast(0f)
            val trackRadius = 18.dp.toPx()
            drawRoundRect(
                color = activeColor.copy(alpha = if (enabled) 0.24f else 0.10f),
                topLeft = androidx.compose.ui.geometry.Offset(trackLeft, trackTop),
                size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadius)
            )
            drawRoundRect(
                color = activeColor.copy(alpha = if (enabled) 0.62f else 0.18f),
                topLeft = androidx.compose.ui.geometry.Offset(trackLeft, thumbY),
                size = androidx.compose.ui.geometry.Size(trackWidth, trackBottom - thumbY),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackRadius)
            )
            val thumbHeight = trackThickness.toPx()
            drawRoundRect(
                color = activeColor.copy(alpha = if (enabled) 1f else 0.35f),
                topLeft = androidx.compose.ui.geometry.Offset(trackLeft, thumbY - thumbHeight / 2f),
                size = androidx.compose.ui.geometry.Size(trackWidth, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbHeight / 2f)
            )
            drawRoundRect(
                color = OssmOnSurface.copy(alpha = if (enabled) 0.7f else 0.2f),
                topLeft = androidx.compose.ui.geometry.Offset(trackLeft, thumbY - thumbHeight / 2f),
                size = androidx.compose.ui.geometry.Size(trackWidth, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbHeight / 2f),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        Text(
            text = rangeLabel,
            color = if (visibleRange == 0f..100f) {
                OssmOnSurface.copy(alpha = 0.55f)
            } else {
                OssmAccent
            },
            fontSize = 11.sp,
            fontWeight = if (visibleRange == 0f..100f) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1
        )
    }
}

internal fun finishLiveTouchGesture(
    gestureState: LiveSliderState,
    onTouchEnd: () -> Unit
) {
    try {
        gestureState.endTouch()
    } finally {
        onTouchEnd()
    }
}

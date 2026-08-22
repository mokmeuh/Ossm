package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun StreamPositionSlider(
    positionPercent: Int,
    enabled: Boolean,
    onPositionDrag: (Int) -> Unit,
    onPositionCommit: (Int) -> Unit,
    onTouchEnd: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var draft by remember { mutableFloatStateOf(positionPercent.toFloat()) }

    LaunchedEffect(positionPercent) {
        draft = positionPercent.toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
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
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LIVE", color = OssmPrimaryLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            onValueCommit = { onPositionCommit(it.roundToInt()) },
            onTouchEnd = onTouchEnd,
            modifier = Modifier.fillMaxWidth()
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ctl_live_home), color = OssmOnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(
                stringResource(if (enabled) R.string.ctl_live_release_once else R.string.ctl_live_preparing_short),
                color = OssmOnSurface.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            Text(stringResource(R.string.ctl_live_deep), color = OssmOnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun LivePositionTrack(
    valuePercent: Float,
    enabled: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit,
    onTouchEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val gestureState = remember { LiveSliderState() }
    val onChange = rememberUpdatedState(onValueChange)
    val onCommit = rememberUpdatedState(onValueCommit)
    val onEnd = rememberUpdatedState(onTouchEnd)
    var draftPercent by remember { mutableFloatStateOf(valuePercent.coerceIn(0f, 100f)) }
    var dragging by remember { mutableStateOf(false) }
    var visibleRange by remember { mutableStateOf(0f..100f) }

    LaunchedEffect(valuePercent, dragging) {
        if (!dragging) draftPercent = valuePercent.coerceIn(0f, 100f)
    }
    LaunchedEffect(enabled) {
        if (!enabled && dragging) {
            gestureState.cancel()
            dragging = false
            draftPercent = valuePercent.coerceIn(0f, 100f)
            visibleRange = gestureState.visibleRange
        }
    }
    LaunchedEffect(dragging) {
        while (dragging) {
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
                .height(56.dp)
                .semantics {
                    stateDescription = stateLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(draftPercent, 0f..100f)
                    if (enabled) {
                        setProgress { target ->
                            val next = target.coerceIn(0f, 100f)
                            draftPercent = next
                            onChange.value(next)
                            onCommit.value(next)
                            true
                        }
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        gestureState.onDown(
                            valuePercent = draftPercent,
                            pointerX = down.position.x,
                            trackWidthPx = size.width.toFloat(),
                            uptimeMillis = down.uptimeMillis
                        )
                        dragging = true
                        visibleRange = gestureState.visibleRange
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    released = true
                                    change.consume()
                                    break
                                }
                                if (!change.pressed) break
                                if (gestureState.onMove(change.position.x, change.uptimeMillis)) {
                                    draftPercent = gestureState.valuePercent
                                    onChange.value(draftPercent)
                                }
                                change.consume()
                            }
                        } finally {
                            finishLiveTouchGesture(
                                released = released,
                                gestureState = gestureState,
                                onCommit = onCommit.value,
                                onTouchEnd = onEnd.value
                            )
                            dragging = false
                            if (!released) {
                                draftPercent = valuePercent.coerceIn(0f, 100f)
                            }
                            visibleRange = gestureState.visibleRange
                        }
                    }
                }
        ) {
            val centerY = size.height / 2f
            val trackStart = 10.dp.toPx()
            val trackWidth = (size.width - trackStart * 2f).coerceAtLeast(0f)
            val trackHeight = 6.dp.toPx()
            val radius = trackHeight / 2f
            drawRoundRect(
                color = activeColor.copy(alpha = if (enabled) 0.24f else 0.10f),
                topLeft = androidx.compose.ui.geometry.Offset(trackStart, centerY - radius),
                size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
            )
            drawRoundRect(
                color = activeColor.copy(alpha = if (enabled) 1f else 0.28f),
                topLeft = androidx.compose.ui.geometry.Offset(trackStart, centerY - radius),
                size = androidx.compose.ui.geometry.Size(trackWidth * displayedFraction, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius)
            )
            val thumbX = trackStart + trackWidth * displayedFraction
            drawCircle(
                color = activeColor.copy(alpha = if (enabled) 1f else 0.35f),
                radius = 9.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(thumbX, centerY)
            )
            drawCircle(
                color = OssmOnSurface.copy(alpha = if (enabled) 0.7f else 0.2f),
                radius = 11.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(thumbX, centerY),
                style = Stroke(width = 1.dp.toPx())
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
    released: Boolean,
    gestureState: LiveSliderState,
    onCommit: (Float) -> Unit,
    onTouchEnd: () -> Unit
) {
    try {
        if (released) {
            gestureState.release()?.let(onCommit)
        } else {
            gestureState.cancel()
        }
    } finally {
        onTouchEnd()
    }
}

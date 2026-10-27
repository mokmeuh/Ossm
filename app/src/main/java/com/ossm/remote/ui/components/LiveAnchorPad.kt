package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Anchored Live control (2.3.24, upper half-disc). The whole surface is interactive: the first
 * pointer-down, anywhere, becomes the anchor (= current logical position, 0 at rest). Above the
 * anchor's horizontal line the radial anchor-finger distance, relative to the farthest top corner,
 * drives the target; on or below that line the target stays at the anchor value.
 */
@Composable
fun LiveAnchorPad(
    positionPercent: Int,
    enabled: Boolean,
    onPositionDrag: (Int) -> Unit,
    onTouchEnd: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    fillAvailable: Boolean = true,
    onTouchCancel: () -> Unit = onTouchEnd,
    gestureCancellationKey: Long = 0L,
    limits: LiveLimitsState? = null,
) {
    var draft by remember { mutableFloatStateOf(positionPercent.toFloat()) }
    var gestureActive by remember { mutableStateOf(false) }

    LaunchedEffect(positionPercent) {
        // A stale state snapshot must not pull the value back while the finger owns the pad.
        if (!gestureActive) draft = positionPercent.toFloat().coerceIn(0f, 100f)
    }

    val latestGestureActive = rememberUpdatedState(gestureActive)
    val latestTouchCancel = rememberUpdatedState(onTouchCancel)
    DisposableEffect(Unit) {
        onDispose {
            if (latestGestureActive.value) latestTouchCancel.value()
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled && latestGestureActive.value) latestTouchCancel.value()
    }
    LaunchedEffect(gestureCancellationKey) {
        // The Store has already performed the explicit cancellation; only release the local
        // ownership flag so the next snapshot may update the visual draft.
        gestureActive = false
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
                        OssmSurfaceRaised,
                    )
                )
            )
            .border(
                1.dp,
                if (enabled) OssmOutline else OssmOutline.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.live_radial_legend),
                color = OssmPrimaryLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                lineHeight = 14.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${draft.roundToInt()}%",
                color = if (enabled) OssmOnSurface else OssmOnSurface.copy(alpha = 0.35f),
                fontSize = if (compact) 20.sp else 24.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        LiveAnchorCanvas(
            valuePercent = draft,
            enabled = enabled,
            compact = compact,
            precisionEnabled = LocalControlPrecisionEnabled.current,
            onTouchStart = { gestureActive = true },
            onValueChange = {
                draft = it.coerceIn(0f, 100f)
                gestureActive = true
                onPositionDrag(draft.roundToInt())
            },
            onTouchEnd = {
                gestureActive = false
                onTouchEnd()
            },
            onTouchCancel = {
                gestureActive = false
                onTouchCancel()
            },
            gestureCancellationKey = gestureCancellationKey,
            limits = limits,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun LiveAnchorCanvas(
    valuePercent: Float,
    enabled: Boolean,
    compact: Boolean,
    precisionEnabled: Boolean,
    onTouchStart: () -> Unit,
    onValueChange: (Float) -> Unit,
    onTouchEnd: () -> Unit,
    onTouchCancel: () -> Unit,
    gestureCancellationKey: Long,
    limits: LiveLimitsState?,
    modifier: Modifier,
) {
    val gestureState = remember(precisionEnabled) { LiveAnchorState() }
    val latestLimits = rememberUpdatedState(limits)
    val onStart = rememberUpdatedState(onTouchStart)
    val onChange = rememberUpdatedState(onValueChange)
    val onEnd = rememberUpdatedState(onTouchEnd)
    val onCancel = rememberUpdatedState(onTouchCancel)
    var draft by remember { mutableFloatStateOf(valuePercent.coerceIn(0f, 100f)) }
    var anchor by remember { mutableStateOf<LiveAnchorGeometry?>(null) }
    var baseAtDown by remember { mutableFloatStateOf(0f) }
    val textMeasurer = rememberTextMeasurer()
    var finger by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val latestDraft = rememberUpdatedState(draft)

    LaunchedEffect(valuePercent, dragging) {
        if (!dragging) draft = valuePercent.coerceIn(0f, 100f)
    }

    val description = stringResource(R.string.live_slider_description)
    val belowLabel = stringResource(R.string.live_anchor_below, baseAtDown.roundToInt())
    val increaseLabel = stringResource(R.string.live_radial_increase)
    val decreaseLabel = stringResource(R.string.live_radial_decrease)
    val stateDescription = stringResource(
        R.string.live_state_description,
        stringResource(R.string.live_range_global),
        draft.roundToInt(),
    )

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = description
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(draft, 0f..100f)
                if (!enabled) disabled()
                if (enabled) {
                    setProgress { target ->
                        draft = commitLiveAccessibilityPosition(
                            target = target,
                            onPosition = onChange.value,
                            onTouchEnd = onEnd.value,
                        )
                        true
                    }
                    // TalkBack steps: one bounded target, then the same safe slow return to 0
                    // as a finger release (never a held position without a finger).
                    customActions = listOf(
                        CustomAccessibilityAction(increaseLabel) {
                            draft = commitLiveAccessibilityPosition(
                                target = liveAnchorAccessibilityStep(draft, +1),
                                onPosition = onChange.value,
                                onTouchEnd = onEnd.value,
                            )
                            true
                        },
                        CustomAccessibilityAction(decreaseLabel) {
                            draft = commitLiveAccessibilityPosition(
                                target = liveAnchorAccessibilityStep(draft, -1),
                                onPosition = onChange.value,
                                onTouchEnd = onEnd.value,
                            )
                            true
                        },
                    )
                }
            }
            .pointerInput(enabled, precisionEnabled, gestureCancellationKey) {
                val pointerInputJob = currentCoroutineContext()[Job]
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val limitState = latestLimits.value
                    val movingSide = limitState?.editing
                    if (limitState != null && movingSide != null) {
                        // A limit line is being placed: the finger drags it, the machine is untouched.
                        val heightPx = size.height.toFloat().coerceAtLeast(1f)
                        limitState.moveTo(movingSide, down.position.y / heightPx)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            limitState.moveTo(movingSide, change.position.y / heightPx)
                            change.consume()
                        }
                        return@awaitEachGesture
                    }
                    if (!enabled) return@awaitEachGesture
                    // The zero is fixed at the bottom of the pad (or on the lower limit line).
                    // The first touch emits the value under the finger; the session then walks the
                    // actuator up to it slowly instead of jumping.
                    val heightForLimits = size.height.toFloat()
                    val zeroY = latestLimits.value?.lower?.let { it * heightForLimits } ?: heightForLimits
                    gestureState.onDown(
                        currentPercent = 0f,
                        pointerX = size.width / 2f,
                        pointerY = zeroY,
                        widthPx = size.width.toFloat(),
                        heightPx = size.height.toFloat(),
                        minReachPx = LIVE_ANCHOR_MIN_REACH_DP.dp.toPx(),
                    )
                    val atDown = gestureState.onMove(
                        down.position.x,
                        limitState?.clampY(down.position.y, heightForLimits) ?: down.position.y,
                    ) ?: 0f
                    anchor = gestureState.geometry
                    baseAtDown = gestureState.basePercent
                    finger = Offset(gestureState.pointerX, gestureState.pointerY)
                    dragging = true
                    onStart.value()
                    draft = atDown
                    onChange.value(atDown)
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
                            val next = gestureState.onMove(
                                change.position.x,
                                latestLimits.value?.clampY(change.position.y, size.height.toFloat())
                                    ?: change.position.y,
                            )
                            finger = Offset(gestureState.pointerX, gestureState.pointerY)
                            if (next != null) {
                                draft = next
                                onChange.value(next)
                            }
                            change.consume()
                        }
                    } finally {
                        gestureState.endTouch()
                        anchor = null
                        dragging = false
                        // A key-driven cancellation is already handled by the Store's explicit
                        // STOP/mode/loss path. Do not call onCancel from this coroutine teardown,
                        // or the callback would advance the key again and loop recomposition.
                        if (released) {
                            onEnd.value()
                        } else if (pointerInputJob?.isActive == true) {
                            onCancel.value()
                        }
                    }
                }
            },
    ) {
        limits?.let { state ->
            for (side in LiveLimitSide.entries) {
                val fraction = state.fractionOf(side) ?: continue
                val y = fraction * size.height
                val moving = state.isMoving(side)
                val lineColor = if (moving) OssmAccent else OssmPrimaryLight
                // Out-of-bounds zone, where the finger can no longer go.
                val (zoneTop, zoneHeight) = if (side == LiveLimitSide.UPPER) 0f to y else y to (size.height - y)
                drawRect(
                    color = OssmOutline.copy(alpha = 0.28f),
                    topLeft = Offset(0f, zoneTop),
                    size = Size(size.width, zoneHeight.coerceAtLeast(0f)),
                )
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = (if (moving) 3.5.dp else 2.5.dp).toPx(),
                    pathEffect = if (moving) PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx())) else null,
                )
            }
        }
        val currentAnchor = anchor ?: return@Canvas
        val anchorPoint = Offset(currentAnchor.anchorX, currentAnchor.anchorY)
        val thumbRadius = liveSliderThumbRadius(compact).toPx()
        val trackWidth = liveSliderTrackThickness(compact).toPx()
        val reach = currentAnchor.reach
        val fingerAbove = finger.y < anchorPoint.y
        clipRect {
            // Lower half (on/below the anchor line): no depth, the target stays at the base.
            drawRect(
                color = OssmOutline.copy(alpha = 0.22f),
                topLeft = Offset(0f, anchorPoint.y),
                size = Size(size.width, (size.height - anchorPoint.y).coerceAtLeast(0f)),
            )
            drawLine(
                color = OssmOnSurface.copy(alpha = 0.55f),
                start = Offset(0f, anchorPoint.y),
                end = Offset(size.width, anchorPoint.y),
                strokeWidth = 1.5.dp.toPx(),
            )
            val belowLayout = textMeasurer.measure(
                belowLabel,
                TextStyle(color = OssmOnSurface.copy(alpha = 0.6f), fontSize = 11.sp),
            )
            if (size.height - anchorPoint.y > belowLayout.size.height + 8.dp.toPx()) {
                drawText(
                    belowLayout,
                    topLeft = Offset(
                        (anchorPoint.x - belowLayout.size.width / 2f)
                            .coerceIn(0f, (size.width - belowLayout.size.width).coerceAtLeast(0f)),
                        anchorPoint.y + 6.dp.toPx(),
                    ),
                )
            }
            // Upper half-circles of reach: 25 / 50 / 75 % and the dashed 100 % (farthest top corner).
            for (ring in 1..4) {
                val radius = reach * ring / 4f
                val isMax = ring == 4
                drawArc(
                    color = if (isMax) OssmAccent.copy(alpha = 0.55f) else OssmOnSurface.copy(alpha = 0.14f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(anchorPoint.x - radius, anchorPoint.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = if (isMax) {
                        Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())),
                        )
                    } else {
                        Stroke(width = 1.dp.toPx())
                    },
                )
                val labelValue = liveAnchorTargetPercent(baseAtDown, ring / 4f).roundToInt()
                val label = textMeasurer.measure(
                    "$labelValue",
                    TextStyle(color = OssmOnSurface.copy(alpha = 0.55f), fontSize = 11.sp),
                )
                val labelY = anchorPoint.y - radius - label.size.height - 2.dp.toPx()
                if (labelY >= 0f) {
                    drawText(
                        label,
                        topLeft = Offset(anchorPoint.x - label.size.width / 2f, labelY),
                    )
                }
            }
            if (fingerAbove) {
                drawLine(
                    color = OssmAccent.copy(alpha = 0.75f),
                    start = anchorPoint,
                    end = finger,
                    strokeWidth = trackWidth.coerceAtLeast(2.dp.toPx()),
                )
            }
            // Anchor marker = base value of this gesture.
            drawCircle(
                color = OssmOnSurface.copy(alpha = 0.9f),
                radius = 8.dp.toPx(),
                center = anchorPoint,
            )
            drawCircle(
                color = OssmAccent,
                radius = 14.dp.toPx(),
                center = anchorPoint,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = if (fingerAbove) OssmAccent else OssmOutline,
                radius = thumbRadius,
                center = finger,
            )
            drawCircle(
                color = OssmOnSurface.copy(alpha = 0.72f),
                radius = thumbRadius,
                center = finger,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** TalkBack step size for the Live pad (percentage points of the Live range). */
internal const val LIVE_ANCHOR_ACCESSIBILITY_STEP_PERCENT = 10f

internal fun liveAnchorAccessibilityStep(currentPercent: Float, direction: Int): Float =
    (currentPercent + direction.coerceIn(-1, 1) * LIVE_ANCHOR_ACCESSIBILITY_STEP_PERCENT)
        .coerceIn(0f, 100f)

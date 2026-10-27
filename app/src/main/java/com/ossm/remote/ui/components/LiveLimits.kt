package com.ossm.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Which horizontal limit line of the Live pad. */
enum class LiveLimitSide { UPPER, LOWER }

/** Smallest fraction of the pad height kept between the two lines. */
internal const val LIVE_LIMIT_MIN_GAP = 0.06f

private const val LIVE_LIMIT_DEFAULT_UPPER = 0.2f
private const val LIVE_LIMIT_DEFAULT_LOWER = 0.8f

/**
 * Optional horizontal limits of the Live pad, as fractions (0 = top, 1 = bottom) of its height.
 * A button cycles one side through absent -> being moved -> locked -> absent. While a line is
 * being moved the pad drags it instead of driving the machine; once locked, the finger drives the
 * machine again but can never go above the upper line or below the lower line.
 */
class LiveLimitsState {
    var upper by mutableStateOf<Float?>(null)
        private set
    var lower by mutableStateOf<Float?>(null)
        private set

    /** The side whose line currently follows the finger, or null when everything is locked. */
    var editing by mutableStateOf<LiveLimitSide?>(null)
        private set

    fun fractionOf(side: LiveLimitSide): Float? = if (side == LiveLimitSide.UPPER) upper else lower

    fun isMoving(side: LiveLimitSide): Boolean = editing == side

    fun toggle(side: LiveLimitSide) {
        val current = fractionOf(side)
        when {
            current == null -> {
                // A new line replaces any other line that was still being moved: it stays put.
                editing = side
                set(
                    side,
                    if (side == LiveLimitSide.UPPER) LIVE_LIMIT_DEFAULT_UPPER else LIVE_LIMIT_DEFAULT_LOWER,
                )
            }
            editing == side -> editing = null
            else -> {
                set(side, null)
                if (editing == side) editing = null
            }
        }
    }

    fun moveTo(side: LiveLimitSide, fraction: Float) {
        val value = fraction.coerceIn(0f, 1f)
        val clamped = when (side) {
            LiveLimitSide.UPPER -> lower?.let { value.coerceAtMost(it - LIVE_LIMIT_MIN_GAP) } ?: value
            LiveLimitSide.LOWER -> upper?.let { value.coerceAtLeast(it + LIVE_LIMIT_MIN_GAP) } ?: value
        }
        set(side, clamped.coerceIn(0f, 1f))
    }

    fun clear() {
        upper = null
        lower = null
        editing = null
    }

    /** Finger ordinate kept between the two lines. */
    fun clampY(y: Float, heightPx: Float): Float {
        val top = upper?.let { it * heightPx } ?: 0f
        val bottom = lower?.let { it * heightPx } ?: heightPx
        return y.coerceIn(top, bottom.coerceAtLeast(top))
    }

    private fun set(side: LiveLimitSide, value: Float?) {
        if (side == LiveLimitSide.UPPER) upper = value else lower = value
    }
}

/** Arrow pointing at a bar: up + bar above (upper limit) or down + bar below (lower limit). */
@androidx.compose.runtime.Composable
fun LiveLimitIcon(side: LiveLimitSide, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        val up = side == LiveLimitSide.UPPER
        val barY = if (up) h * 0.14f else h * 0.86f
        val tipY = if (up) h * 0.34f else h * 0.66f
        val tailY = if (up) h * 0.84f else h * 0.16f
        val head = w * 0.22f
        val dir = if (up) 1f else -1f
        drawLine(color, Offset(w * 0.12f, barY), Offset(w * 0.88f, barY), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, tailY), Offset(w / 2f, tipY), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, tipY), Offset(w / 2f - head, tipY + head * dir), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w / 2f, tipY), Offset(w / 2f + head, tipY + head * dir), stroke.width, StrokeCap.Round)
    }
}

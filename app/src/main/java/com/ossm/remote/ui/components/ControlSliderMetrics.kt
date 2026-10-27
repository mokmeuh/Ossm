package com.ossm.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimaryLight

/**
 * Shared geometry of the bar sliders (scalar and range). Every slider draws a thick track
 * and vertical bar thumbs; a press within [grabRadius] of a bar takes that bar where it is
 * (relative drag), farther away the closest bar jumps to the finger.
 *
 * [inlineHeader] puts the label, the random toggle and the value on the same line as the
 * track (landscape), so every slider stays visible without scrolling and the track keeps
 * nearly the whole card width.
 */
@Immutable
data class ControlSliderMetrics(
    val sliderHeight: Dp,
    val trackHeight: Dp,
    val trackInset: Dp,
    val thumbWidth: Dp,
    val thumbHeight: Dp,
    val buttonSize: Dp,
    val labelSize: TextUnit,
    val valueSize: TextUnit,
    val symbolSize: TextUnit,
    val grabRadius: Dp,
    val inlineHeader: Boolean = false,
    val labelColumnWidth: Dp = 0.dp,
    val valueColumnWidth: Dp = 0.dp,
    val showScaleLegends: Boolean = true,
    val toggleMinSize: Dp = 48.dp
)

val RegularControlSliderMetrics = ControlSliderMetrics(
    sliderHeight = 56.dp,
    trackHeight = 10.dp,
    trackInset = 10.dp,
    thumbWidth = 4.dp,
    thumbHeight = 44.dp,
    buttonSize = 56.dp,
    labelSize = 13.sp,
    valueSize = 28.sp,
    symbolSize = 30.sp,
    grabRadius = 28.dp
)

/** Larger controls used when the control card has the whole screen height to itself. */
val LargeControlSliderMetrics = ControlSliderMetrics(
    sliderHeight = 88.dp,
    trackHeight = 16.dp,
    trackInset = 12.dp,
    thumbWidth = 6.dp,
    thumbHeight = 70.dp,
    buttonSize = 72.dp,
    labelSize = 16.sp,
    valueSize = 36.sp,
    symbolSize = 38.sp,
    grabRadius = 40.dp
)

/** One line per slider, for landscape or short cards. */
val InlineControlSliderMetrics = ControlSliderMetrics(
    sliderHeight = 52.dp,
    trackHeight = 12.dp,
    trackInset = 10.dp,
    thumbWidth = 5.dp,
    thumbHeight = 42.dp,
    buttonSize = 48.dp,
    labelSize = 12.sp,
    valueSize = 20.sp,
    symbolSize = 26.sp,
    grabRadius = 30.dp,
    inlineHeader = true,
    labelColumnWidth = 118.dp,
    valueColumnWidth = 84.dp,
    showScaleLegends = false,
    toggleMinSize = 36.dp
)

val LocalControlSliderMetrics = staticCompositionLocalOf { RegularControlSliderMetrics }

/**
 * Lays out one slider: header (label, optional accessory, value) above the controls, or
 * everything on one line when [ControlSliderMetrics.inlineHeader] is set. [controls] are the
 * -/+ buttons and the track, placed in a Row (use weight(1f) on the track).
 */
@Composable
fun SliderFrame(
    label: String,
    displayValue: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    displayValueColor: Color? = null,
    headerAccessory: (@Composable RowScope.() -> Unit)? = null,
    controls: @Composable RowScope.() -> Unit
) {
    val metrics = LocalControlSliderMetrics.current
    val valueColor = displayValueColor ?: if (enabled) OssmOnSurface else OssmOnSurface.copy(0.4f)
    val labelText: @Composable () -> Unit = {
        Text(
            text = label.uppercase(),
            color = OssmPrimaryLight,
            fontSize = metrics.labelSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    val valueText: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = displayValue,
            color = valueColor,
            fontSize = metrics.valueSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = textModifier
        )
    }
    if (metrics.inlineHeader) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.width(metrics.labelColumnWidth)) {
                labelText()
                headerAccessory?.let { accessory ->
                    Row(verticalAlignment = Alignment.CenterVertically) { accessory() }
                }
            }
            controls()
            valueText(Modifier.width(metrics.valueColumnWidth))
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    labelText()
                    headerAccessory?.invoke(this)
                }
                valueText(Modifier)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                controls()
            }
        }
    }
}

/**
 * Signed slider look (sensation): the fill grows from [centerPercent], green on the positive
 * side, red on the negative side, and a thin blue tick stays on the center (the magnetic 0).
 */
@Immutable
data class BipolarStyle(
    val centerPercent: Float = 50f,
    val positiveColor: Color = SignedPositiveColor,
    val negativeColor: Color = SignedNegativeColor,
    val centerTickColor: Color = CenterTickBlue
)

val SignedPositiveColor = Color(0xFF34C759)
val SignedNegativeColor = Color(0xFFFF453A)
val CenterTickBlue = Color(0xFF3D8BFF)

/** Color of a signed value (0..1, 0.5 = neutral): green above, red below, null at neutral. */
fun signedValueColor(value: Float): Color? = when {
    value > 0.5005f -> SignedPositiveColor
    value < 0.4995f -> SignedNegativeColor
    else -> null
}

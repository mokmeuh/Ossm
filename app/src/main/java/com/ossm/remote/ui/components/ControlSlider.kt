package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ControlSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit = onValueChange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    activeColor: Color = OssmPrimary,
    displayValue: String = "${(value * 100).toInt()}%"
) {
    val step = (valueRange.endInclusive - valueRange.start) / 100f
    var sliderWidthPx by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                color = OssmPrimaryLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = displayValue,
                color = if (enabled) OssmOnSurface else OssmOnSurface.copy(0.4f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RampButton(
                symbol = "−",
                sign = -1,
                enabled = enabled,
                activeColor = activeColor,
                value = value,
                step = step,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueCommit = onValueCommit
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { sliderWidthPx = it.width }
                    .pointerInput(enabled, valueRange, sliderWidthPx) {
                        detectTapGestures { offset ->
                            if (!enabled || sliderWidthPx <= 0) return@detectTapGestures
                            val fraction = (offset.x / sliderWidthPx.toFloat()).coerceIn(0f, 1f)
                            val tappedValue =
                                valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                            onValueChange(tappedValue)
                            onValueCommit(tappedValue)
                        }
                    }
            ) {
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    onValueChangeFinished = { onValueCommit(value) },
                    valueRange = valueRange,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = activeColor,
                        activeTrackColor = activeColor,
                        inactiveTrackColor = activeColor.copy(alpha = 0.25f),
                        disabledThumbColor = activeColor.copy(0.3f),
                        disabledActiveTrackColor = activeColor.copy(0.2f)
                    )
                )
            }

            RampButton(
                symbol = "+",
                sign = 1,
                enabled = enabled,
                activeColor = activeColor,
                value = value,
                step = step,
                valueRange = valueRange,
                onValueChange = onValueChange,
                onValueCommit = onValueCommit
            )
        }
    }
}

/**
 * Bouton +/- à MAINTIEN progressif. Un simple appui = ±1 % (appliqué SYNCHRONEMENT
 * dès `onPress` → aucun tap perdu). Maintenu, la valeur s'emballe : ~100 % en ~5 s
 * (douce 0-2 s, puis de plus en plus vite). S'applique à TOUS les sliders.
 */
@Composable
private fun RampButton(
    symbol: String,
    sign: Int,
    enabled: Boolean,
    activeColor: Color,
    value: Float,
    step: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit
) {
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                if (enabled) activeColor.copy(alpha = 0.18f)
                else activeColor.copy(alpha = 0.06f)
            )
            .pointerInput(enabled, value, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        // Pas immédiat SYNCHRONE : le tap est toujours pris en compte.
                        var current = (value + sign * step)
                            .coerceIn(valueRange.start, valueRange.endInclusive)
                        onValueChange(current)
                        // Rampe tant que le doigt reste appuyé (accélération).
                        val rampJob = scope.launch {
                            var heldMs = 0L
                            while (isActive) {
                                delay(50)
                                heldMs += 50
                                val heldSec = heldMs / 1000f
                                // %/s : douce au début, très rapide après quelques secondes
                                // (~100 % atteint vers 5 s de maintien continu).
                                val ratePctPerSec = 1.5f + 0.6f * heldSec * heldSec * heldSec
                                current = (current + sign * ratePctPerSec * 0.05f * step)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(current)
                                if ((sign > 0 && current >= valueRange.endInclusive) ||
                                    (sign < 0 && current <= valueRange.start)
                                ) break
                            }
                        }
                        // Suspend jusqu'au relâchement, puis stoppe la rampe et envoie 1× au moteur.
                        tryAwaitRelease()
                        rampJob.cancel()
                        onValueCommit(current)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            color = if (enabled) activeColor else activeColor.copy(alpha = 0.3f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp
        )
    }
}

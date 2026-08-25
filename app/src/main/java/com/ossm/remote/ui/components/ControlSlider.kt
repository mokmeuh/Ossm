package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalControlPrecisionEnabled = staticCompositionLocalOf { true }

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
    displayValue: String = "${(value * 100).toInt()}%",
    currentMarker: Float? = null,
    markerColor: Color = OssmError,
    displayValueColor: Color? = null
) {
    val step = (valueRange.endInclusive - valueRange.start) / 100f
    val rangeSize = valueRange.endInclusive - valueRange.start
    fun toPercent(raw: Float): Float = if (rangeSize <= 0f) {
        0f
    } else {
        ((raw - valueRange.start) / rangeSize * 100f).coerceIn(0f, 100f)
    }
    fun fromPercent(percent: Float): Float =
        valueRange.start + percent.coerceIn(0f, 100f) / 100f * rangeSize

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
                color = displayValueColor ?: if (enabled) OssmOnSurface else OssmOnSurface.copy(0.4f),
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

            PrecisionScalarSlider(
                valuePercent = toPercent(value),
                enabled = enabled,
                precisionEnabled = LocalControlPrecisionEnabled.current,
                activeColor = activeColor,
                currentMarkerPercent = currentMarker?.let(::toPercent),
                onValueChange = { onValueChange(fromPercent(it)) },
                onValueCommit = { onValueCommit(fromPercent(it)) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                markerColor = markerColor
            )

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

@Composable
fun PrecisionScalarSlider(
    valuePercent: Float,
    enabled: Boolean,
    precisionEnabled: Boolean,
    activeColor: Color,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    currentMarkerPercent: Float? = null,
    markerColor: Color = OssmError
) {
    val haptics = LocalHapticFeedback.current
    val gestureState = remember { PrecisionSliderState() }
    val onChange = rememberUpdatedState(onValueChange)
    val onCommit = rememberUpdatedState(onValueCommit)
    var draftPercent by remember { mutableFloatStateOf(valuePercent.coerceIn(0f, 100f)) }
    var dragging by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(PrecisionLevel.Global) }
    var visibleRange by remember { mutableStateOf(0f..100f) }

    fun syncVisualState() {
        level = gestureState.level
        visibleRange = gestureState.visibleRange
    }

    LaunchedEffect(valuePercent, dragging) {
        if (!dragging) draftPercent = valuePercent.coerceIn(0f, 100f)
    }
    LaunchedEffect(enabled) {
        if (!enabled && dragging) {
            gestureState.cancel()
            dragging = false
            draftPercent = valuePercent.coerceIn(0f, 100f)
            syncVisualState()
        }
    }
    LaunchedEffect(dragging, precisionEnabled) {
        while (dragging && precisionEnabled) {
            delay(25)
            if (gestureState.advanceTime(SystemClock.uptimeMillis())) {
                syncVisualState()
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    val rangeLabel = when (level) {
        PrecisionLevel.Global -> stringResource(R.string.precision_range_global)
        else -> stringResource(
            R.string.precision_range_level,
            checkNotNull(level.halfSpan).roundToInt(),
            visibleRange.start.roundToInt(),
            visibleRange.endInclusive.roundToInt()
        )
    }
    val stateLabel = stringResource(
        R.string.precision_state_description,
        rangeLabel,
        draftPercent.roundToInt()
    )
    val rangeSpan = visibleRange.endInclusive - visibleRange.start
    val displayedFraction = if (rangeSpan <= 0f) 0f else {
        ((draftPercent - visibleRange.start) / rangeSpan).coerceIn(0f, 1f)
    }
    val markerFraction = currentMarkerPercent?.let {
        ((it - visibleRange.start) / rangeSpan).coerceIn(0f, 1f)
    }

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
                        syncVisualState()
                        var released = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    released = true
                                    change.consume()
                                    gestureState.release()?.let(onCommit.value)
                                    dragging = false
                                    syncVisualState()
                                    break
                                }
                                if (!change.pressed) break
                                if (gestureState.onMove(change.position.x, change.uptimeMillis)) {
                                    draftPercent = gestureState.valuePercent
                                    onChange.value(draftPercent)
                                    syncVisualState()
                                }
                                change.consume()
                            }
                        } finally {
                            if (!released) {
                                gestureState.cancel()
                                dragging = false
                                draftPercent = valuePercent.coerceIn(0f, 100f)
                                syncVisualState()
                            }
                        }
                    }
                }
        ) {
            val centerY = size.height / 2f
            val trackStart = 10.dp.toPx()
            val trackEnd = size.width - trackStart
            val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
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
            markerFraction?.let { fraction ->
                val markerX = trackStart + trackWidth * fraction
                drawLine(
                    color = markerColor.copy(alpha = if (enabled) 0.95f else 0.4f),
                    start = androidx.compose.ui.geometry.Offset(markerX, centerY - 17.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(markerX, centerY + 17.dp.toPx()),
                    strokeWidth = 3.dp.toPx()
                )
            }
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
            color = when (level) {
                PrecisionLevel.Global -> OssmOnSurface.copy(alpha = 0.55f)
                PrecisionLevel.Fine30, PrecisionLevel.Fine20 -> OssmPrimaryLight
                PrecisionLevel.Fine10, PrecisionLevel.Fine5 -> activeColor
            },
            fontSize = 11.sp,
            fontWeight = if (level == PrecisionLevel.Global) FontWeight.Normal else FontWeight.Bold,
            maxLines = 1
        )
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
    // Lit TOUJOURS la valeur/callbacks A JOUR sans re-keyer le pointerInput : sinon soit la
    // rampe file seule (si on keye sur value), soit +/- repart de la valeur initiale = 1 %
    // (si la lambda capture un `value` fige). rememberUpdatedState met a jour la reference
    // a chaque recomposition sans redemarrer le geste.
    val valueState = rememberUpdatedState(value)
    val rangeState = rememberUpdatedState(valueRange)
    val stepState = rememberUpdatedState(step)
    val onChange = rememberUpdatedState(onValueChange)
    val onCommit = rememberUpdatedState(onValueCommit)

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (enabled) activeColor.copy(alpha = 0.18f)
                else activeColor.copy(alpha = 0.06f)
            )
            // IMPORTANT : ne PAS indexer sur `value` — sinon chaque incrément de la rampe
            // redémarre le détecteur de geste et annule tryAwaitRelease() → le relâchement
            // n'est jamais vu et la rampe file toute seule jusqu'à 100 %. `value` est lu au
            // moment de l'appui (capture initiale), l'accumulateur `current` fait le reste.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        val range = rangeState.value
                        val st = stepState.value
                        // Pas immédiat SYNCHRONE, à partir de la valeur A JOUR : le tap est
                        // toujours pris et repart de la position réelle du slider.
                        var current = (valueState.value + sign * st)
                            .coerceIn(range.start, range.endInclusive)
                        onChange.value(current)
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
                                current = (current + sign * ratePctPerSec * 0.05f * st)
                                    .coerceIn(range.start, range.endInclusive)
                                onChange.value(current)
                                if ((sign > 0 && current >= range.endInclusive) ||
                                    (sign < 0 && current <= range.start)
                                ) break
                            }
                        }
                        // Suspend jusqu'au relâchement, puis stoppe la rampe et envoie 1× au moteur.
                        tryAwaitRelease()
                        rampJob.cancel()
                        onCommit.value(current)
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

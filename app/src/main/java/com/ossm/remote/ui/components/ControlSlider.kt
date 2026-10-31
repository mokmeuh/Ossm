package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.ossm.remote.ui.modes.LocalModeContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmTextFaint
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LocalControlPrecisionEnabled = staticCompositionLocalOf { false }

/** Half-width (percent of the scale) of the invisible detent that pulls the thumb in. */
internal const val SNAP_RADIUS_PERCENT = 4f

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
    displayValueColor: Color? = null,
    headerAccessory: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Monotonic host-owned cancellation key for a held scalar gesture. The default keeps
     * existing callers source-compatible; Live callers should advance it when STOP/loss/mode
     * exit invalidates the gesture's pending release.
     */
    gestureCancellationKey: Long = 0L,
    /** Optional invisible detent (in [valueRange] units) the thumb is pulled onto when close. */
    snapValue: Float? = null,
    /** Signed slider (sensation): fill from the center, colored by sign, fixed center tick. */
    bipolar: BipolarStyle? = null,
    /**
     * Plafond imposé (session distante) : cadenas, mot « limité » et valeur en ambre dans
     * l'en-tête, et « limité » lu par TalkBack. N'a aucun effet sur le réglage lui-même.
     */
    limited: Boolean = LocalModeContext.current.remoteLimited
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

    // Couleur du canal : la sensation (curseur signé) porte la sienne, les autres celle qu'on leur donne.
    val channelColor = bipolar?.channelColor ?: activeColor
    val limitedText = stringResource(R.string.sld_limited)

    SliderFrame(
        label = label,
        displayValue = displayValue,
        enabled = enabled,
        modifier = modifier,
        displayValueColor = displayValueColor,
        headerAccessory = headerAccessory,
        channelColor = channelColor,
        limited = limited
    ) {
        RampButton(
            icon = Icons.Filled.Remove,
            sign = -1,
            description = stringResource(R.string.control_slider_decrease, label),
            enabled = enabled,
            activeColor = channelColor,
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
            activeColor = channelColor,
            currentMarkerPercent = currentMarker?.let(::toPercent),
            label = label,
            onValueChange = { onValueChange(fromPercent(it)) },
            onValueCommit = { onValueCommit(fromPercent(it)) },
            gestureCancellationKey = gestureCancellationKey,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            markerColor = markerColor,
            snapPercent = snapValue?.let(::toPercent),
            bipolar = bipolar,
            stateSuffix = if (limited) limitedText else null
        )

        RampButton(
            icon = Icons.Filled.Add,
            sign = 1,
            description = stringResource(R.string.control_slider_increase, label),
            enabled = enabled,
            activeColor = channelColor,
            value = value,
            step = step,
            valueRange = valueRange,
            onValueChange = onValueChange,
            onValueCommit = onValueCommit
        )
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
    markerColor: Color = OssmError,
    label: String? = null,
    /** Monotonic key that cancels the current pointer gesture without affecting the next one. */
    gestureCancellationKey: Long = 0L,
    /** Invisible detent in percent (0..100); a drag ending near it lands exactly on it. */
    snapPercent: Float? = null,
    snapRadiusPercent: Float = SNAP_RADIUS_PERCENT,
    bipolar: BipolarStyle? = null,
    /** Mot ajouté à l'état lu par TalkBack (ex. « limité »). Sans effet visuel ni sur le geste. */
    stateSuffix: String? = null
) {
    val haptics = LocalHapticFeedback.current
    val metrics = LocalControlSliderMetrics.current
    var snapped by remember { mutableStateOf(false) }
    fun applySnap(raw: Float): Float {
        val target = snapPercent ?: return raw
        val inside = kotlin.math.abs(raw - target) <= snapRadiusPercent
        if (inside && !snapped) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        snapped = inside
        return if (inside) target else raw
    }
    val gestureState = remember { PrecisionSliderState() }
    val onChange = rememberUpdatedState(onValueChange)
    val onCommit = rememberUpdatedState(onValueCommit)
    val latestValuePercent = rememberUpdatedState(valuePercent)
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
    LaunchedEffect(gestureCancellationKey) {
        // Re-keying pointerInput below cancels the old awaitPointerEvent loop. Keep this
        // explicit state reset as well so a STOP/loss/mode-exit epoch cannot leave the scalar
        // visually owned by the cancelled gesture or allow its release to commit.
        gestureState.cancel()
        dragging = false
        draftPercent = latestValuePercent.value.coerceIn(0f, 100f)
        syncVisualState()
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
    val baseStateLabel = stringResource(
        R.string.precision_state_description,
        rangeLabel,
        draftPercent.roundToInt()
    )
    val stateLabel = if (stateSuffix != null) "$baseStateLabel, $stateSuffix" else baseStateLabel
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
                .height(metrics.sliderHeight)
                .semantics {
                    label?.let { contentDescription = it }
                    stateDescription = stateLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(draftPercent, 0f..100f)
                    if (!enabled) disabled()
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
                .pointerInput(enabled, gestureCancellationKey, metrics) {
                    if (!enabled) return@pointerInput
                    val insetPx = metrics.trackInset.toPx()
                    val grabPx = metrics.grabRadius.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        // Grab zone: near the bar, the bar is taken where it is (relative
                        // drag). Farther away, the bar first jumps under the finger.
                        val usable = (size.width - 2f * insetPx).coerceAtLeast(1f)
                        val thumbX = insetPx + usable * (draftPercent / 100f).coerceIn(0f, 1f)
                        if (kotlin.math.abs(down.position.x - thumbX) > grabPx) {
                            val jumped = applySnap(
                                ((down.position.x - insetPx) / usable * 100f).coerceIn(0f, 100f)
                            )
                            draftPercent = jumped
                            onChange.value(jumped)
                        }
                        gestureState.onDown(
                            valuePercent = draftPercent,
                            pointerX = down.position.x,
                            trackWidthPx = usable,
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
                                    gestureState.release()?.let { onCommit.value(applySnap(it)) }
                                    snapped = false
                                    dragging = false
                                    syncVisualState()
                                    break
                                }
                                if (!change.pressed) break
                                if (gestureState.onMove(change.position.x, change.uptimeMillis)) {
                                    draftPercent = applySnap(gestureState.valuePercent)
                                    onChange.value(draftPercent)
                                    syncVisualState()
                                }
                                change.consume()
                            }
                        } finally {
                            if (!released) {
                                gestureState.cancel()
                                dragging = false
                                // Ordinary recomposition does not restart this coroutine, so
                                // read the latest parent value on cancellation instead of the
                                // value captured when this gesture started. Epoch changes do
                                // restart it, preventing a stale release from committing.
                                draftPercent = latestValuePercent.value.coerceIn(0f, 100f)
                                syncVisualState()
                            }
                        }
                    }
                }
        ) {
            val centerY = size.height / 2f
            val trackStart = metrics.trackInset.toPx()
            val trackEnd = size.width - trackStart
            val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
            val trackHeight = metrics.trackHeight.toPx()
            val radius = trackHeight / 2f
            drawSliderTrack(trackStart, centerY, trackWidth, trackHeight, enabled)
            // Bipolar: the fill starts at the center and takes the color of the sign.
            val centerFraction = bipolar?.let {
                ((it.centerPercent - visibleRange.start) / rangeSpan).coerceIn(0f, 1f)
            }
            val valueColor = when {
                bipolar == null -> activeColor
                draftPercent > bipolar.centerPercent + 0.01f -> bipolar.positiveColor
                draftPercent < bipolar.centerPercent - 0.01f -> bipolar.negativeColor
                else -> activeColor
            }
            val fillFrom = minOf(centerFraction ?: 0f, displayedFraction)
            val fillTo = maxOf(centerFraction ?: 0f, displayedFraction)
            drawRoundRect(
                color = valueColor.copy(alpha = if (enabled) 1f else 0.40f),
                topLeft = Offset(trackStart + trackWidth * fillFrom, centerY - radius),
                size = Size(trackWidth * (fillTo - fillFrom), trackHeight),
                cornerRadius = CornerRadius(radius)
            )
            // Fixed thin blue tick on the center (the magnetic zero), always visible.
            if (bipolar != null && bipolar.centerPercent in visibleRange) {
                val tickX = trackStart + trackWidth * (centerFraction ?: 0f)
                val tickHalf = metrics.thumbHeight.toPx() * 0.36f
                drawLine(
                    color = bipolar.centerTickColor.copy(alpha = if (enabled) 1f else 0.4f),
                    start = Offset(tickX, centerY - tickHalf),
                    end = Offset(tickX, centerY + tickHalf),
                    strokeWidth = 2.dp.toPx()
                )
            }
            markerFraction?.let { fraction ->
                val markerX = trackStart + trackWidth * fraction
                drawLine(
                    color = markerColor.copy(alpha = if (enabled) 0.95f else 0.4f),
                    start = Offset(markerX, centerY - metrics.thumbHeight.toPx() * 0.45f),
                    end = Offset(markerX, centerY + metrics.thumbHeight.toPx() * 0.45f),
                    strokeWidth = 3.dp.toPx()
                )
            }
            // Poignée ronde de 28 dp (anneau clair, centre à la couleur de la valeur). Son rayon
            // tient dans la marge de piste : elle ne recouvre jamais les boutons −/+ à 0 et 100 %.
            drawSliderThumb(
                center = Offset(trackStart + trackWidth * displayedFraction, centerY),
                radiusPx = metrics.thumbWidth.toPx() / 2f,
                color = valueColor,
                alpha = if (enabled) 1f else 0.4f
            )
        }
        // The global "0-100" range is only informative when precision narrowing is on; with it
        // off the label is permanent noise, so hide it (state stays in the semantics above).
        if (precisionEnabled || level != PrecisionLevel.Global) Text(
            text = rangeLabel,
            color = when (level) {
                PrecisionLevel.Global -> OssmTextFaint
                PrecisionLevel.Fine30, PrecisionLevel.Fine20 -> OssmPrimaryLight
                PrecisionLevel.Fine10, PrecisionLevel.Fine5 -> activeColor
            },
            style = OssmCaption,
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
    icon: ImageVector,
    sign: Int,
    description: String,
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
            // TalkBack: the glyph is not a usable name, and the touch gesture below is not an
            // accessibility action. Expose a named button that applies one step and commits it.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) {
                    disabled()
                } else {
                    onClick {
                        val range = rangeState.value
                        val stepped = (valueState.value + sign * stepState.value)
                            .coerceIn(range.start, range.endInclusive)
                        onChange.value(stepped)
                        onCommit.value(stepped)
                        true
                    }
                }
            }
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
        // Glyphe décoratif : la sémantique du bouton (nom, rôle, clic) est posée plus haut.
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) activeColor else activeColor.copy(alpha = 0.4f),
            modifier = Modifier.size(metrics.buttonSize * 0.45f)
        )
    }
}

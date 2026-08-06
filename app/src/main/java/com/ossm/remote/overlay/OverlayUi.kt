package com.ossm.remote.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmStop
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmWarning
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Taille de la zone tactile de la bulle (légèrement plus grande que la bulle visuelle). */
const val OVERLAY_BUBBLE_TOUCH_SIZE_DP = 64
/** Taille visuelle de la bulle. */
const val OVERLAY_BUBBLE_VISUAL_SIZE_DP = 56
/** Taille de l'icône complète de l'app rendue dans la bulle (fond adaptatif + foreground). */
const val OVERLAY_BUBBLE_ICON_SIZE_DP = 40
/** Marge horizontale du panneau déplié par rapport aux bords de l'écran. */
const val OVERLAY_PANEL_MARGIN_DP = 10
/** Appui long avant d'entrer en mode glisser. */
const val OVERLAY_DRAG_LONG_PRESS_MS = 1000L
/** Throttling des envois live du panneau (au plus 1 envoi / 100 ms pendant le glisser). */
const val OVERLAY_LIVE_THROTTLE_MS = 100L

/** Plus petit écart min/max de profondeur conservé par le panneau. */
private const val MIN_DEPTH_SPAN = 0.05f

/** État affiché par l'overlay, dérivé du ControlSessionStore par le service. */
data class OverlayViewState(
    val speed: Float = 0f,
    val depthMin: Float = 0f,
    val depthMax: Float = 1f,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val patternName: String = "",
    val isConnected: Boolean = false,
    val hasError: Boolean = false,
    val hasPendingManualChange: Boolean = false
)

@Composable
fun OverlayRoot(
    state: OverlayViewState,
    transparencyPercent: Int,
    expanded: Boolean,
    dragging: Boolean,
    onBubbleTap: () -> Unit,
    onBubbleDragStart: () -> Unit,
    onBubbleDragDelta: (Offset) -> Unit,
    onBubbleDragEnd: () -> Unit,
    onCollapse: () -> Unit,
    onOverlayClose: () -> Unit,
    onEmergencyStop: () -> Unit,
    onSpeedLive: (Float) -> Unit,
    onSpeedCommit: (Float) -> Unit,
    onDepthLive: (Float, Float) -> Unit,
    onDepthCommit: (Float, Float) -> Unit
) {
    val alpha = (transparencyPercent / 100f).coerceIn(0.2f, 1f)
    if (expanded) {
        OverlayPanel(
            state = state,
            alpha = alpha,
            onCollapse = onCollapse,
            onOverlayClose = onOverlayClose,
            onEmergencyStop = onEmergencyStop,
            onSpeedLive = onSpeedLive,
            onSpeedCommit = onSpeedCommit,
            onDepthLive = onDepthLive,
            onDepthCommit = onDepthCommit
        )
    } else {
        OverlayBubble(
            dotColor = bubbleDotColor(state),
            dragging = dragging,
            alpha = alpha,
            onTap = onBubbleTap,
            onDragStart = onBubbleDragStart,
            onDragDelta = onBubbleDragDelta,
            onDragEnd = onBubbleDragEnd
        )
    }
}

private fun bubbleDotColor(state: OverlayViewState): Color = when {
    state.hasError -> OssmError
    state.isConnected -> OssmConnected
    else -> Color(0xFF9E9E9E)
}

@Composable
private fun OverlayBubble(
    dotColor: Color,
    dragging: Boolean,
    alpha: Float,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.15f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "overlayBubbleScale"
    )
    // Callbacks toujours à jour sans redémarrer le gesture detector.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = Modifier
            .size(OVERLAY_BUBBLE_TOUCH_SIZE_DP.dp)
            .pointerInput(Unit) {
                detectOverlayDragOrTap(
                    longPressTimeout = OVERLAY_DRAG_LONG_PRESS_MS,
                    onTap = { currentOnTap() },
                    onDragStart = { currentOnDragStart() },
                    onDragDelta = { currentOnDragDelta(it) },
                    onDragEnd = { currentOnDragEnd() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(OVERLAY_BUBBLE_VISUAL_SIZE_DP.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(OssmSurface)
                .border(1.dp, OssmGlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Icône complète de l'app : fond adaptatif (ic_launcher_background) + foreground.
            Box(
                modifier = Modifier
                    .size(OVERLAY_BUBBLE_ICON_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.overlay_bubble_logo),
                    modifier = Modifier.size(OVERLAY_BUBBLE_ICON_SIZE_DP.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(1.dp, OssmSurface, CircleShape)
            )
        }
    }
}

/**
 * Détecteur de geste unique pour la bulle : appui simple → [onTap],
 * appui long (≥ [longPressTimeout]) → mode glisser ([onDragStart]/[onDragDelta]/[onDragEnd]).
 * Un déplacement au-delà du slop avant la fin de l'appui long annule le tap.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectOverlayDragOrTap(
    longPressTimeout: Long,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: () -> Unit
) = coroutineScope {
    // Le coroutineScope extérieur fournit le CoroutineScope nécessaire à launch :
    // le scope restreint de awaitEachGesture n'expose que AwaitPointerEventScope.
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        var longPressFired = false
        var movedBeforeLongPress = false
        val longPressJob = launch {
            delay(longPressTimeout)
            longPressFired = true
            onDragStart()
        }
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                if (change.pressed) {
                    if (longPressFired) {
                        val drag = change.positionChange()
                        if (drag != Offset.Zero) {
                            change.consume()
                            onDragDelta(drag)
                        }
                    } else if (!movedBeforeLongPress &&
                        (change.position - down.position).getDistance() > slop
                    ) {
                        movedBeforeLongPress = true
                        longPressJob.cancel()
                    }
                } else {
                    longPressJob.cancel()
                    when {
                        longPressFired -> onDragEnd()
                        !movedBeforeLongPress -> onTap()
                    }
                    break
                }
            }
        } finally {
            longPressJob.cancel()
        }
    }
}

@Composable
private fun OverlayPanel(
    state: OverlayViewState,
    alpha: Float,
    onCollapse: () -> Unit,
    onOverlayClose: () -> Unit,
    onEmergencyStop: () -> Unit,
    onSpeedLive: (Float) -> Unit,
    onSpeedCommit: (Float) -> Unit,
    onDepthLive: (Float, Float) -> Unit,
    onDepthCommit: (Float, Float) -> Unit
) {
    var speedDraft by remember { mutableFloatStateOf(state.speed) }
    var depthMinDraft by remember { mutableFloatStateOf(state.depthMin) }
    var depthMaxDraft by remember { mutableFloatStateOf(state.depthMax) }

    // Resynchronise les brouillons quand l'état du store change (l'app met à jour le panneau
    // en direct). Pendant le glisser le store renvoie la même valeur envoyée → pas de conflit.
    LaunchedEffect(state.speed, state.depthMin, state.depthMax) {
        speedDraft = state.speed
        depthMinDraft = state.depthMin
        depthMaxDraft = state.depthMax
    }

    var lastSpeedSendMs by remember { mutableLongStateOf(0L) }
    var lastDepthSendMs by remember { mutableLongStateOf(0L) }

    // Fond solide (OssmSurface) au lieu du dégradé glass : à 100 % d'opacité le panneau
    // est totalement opaque (on ne voit pas à travers), en dessous l'alpha global réduit
    // le tout de façon graduée jusqu'à 20 % (très transparent).
    val panelShape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OVERLAY_PANEL_MARGIN_DP.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(panelShape)
            .background(OssmSurface)
            .border(1.dp, OssmGlassBorder, panelShape)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icône app : ferme l'overlay seul (sans arrêter la machine).
            IconButton(onClick = onOverlayClose, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.overlay_close),
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.patternName.ifBlank { stringResource(R.string.overlay_title) },
                    color = OssmPrimaryLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = statusLabel(state),
                    color = statusColor(state),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            // Chevron bas : replie le panneau vers la bulle.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(OssmSurface)
                    .border(1.dp, OssmGlassBorder, CircleShape)
                    .pointerInput(Unit) { detectTapGestures { onCollapse() } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.overlay_collapse),
                    tint = OssmPrimaryLight,
                    modifier = Modifier.size(20.dp)
                )
            }
            // X rouge : arrêt d'urgence.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(OssmStop)
                    .pointerInput(Unit) { detectTapGestures { onEmergencyStop() } },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.overlay_emergency_stop),
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        if (state.hasPendingManualChange) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.overlay_pending_guard),
                color = OssmWarning,
                fontSize = 10.sp,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(4.dp))
        OverlaySlider(
            label = stringResource(R.string.ctl_speed),
            value = speedDraft,
            displayValue = "${(speedDraft * 100).roundToInt()}%",
            activeColor = OssmPrimary,
            onValueChange = { newValue ->
                speedDraft = newValue
                val now = System.currentTimeMillis()
                if (now - lastSpeedSendMs >= OVERLAY_LIVE_THROTTLE_MS) {
                    lastSpeedSendMs = now
                    onSpeedLive(newValue)
                }
            },
            onValueCommit = { onSpeedCommit(speedDraft) }
        )

        Spacer(Modifier.height(4.dp))
        OverlaySlider(
            label = stringResource(R.string.prof_min),
            value = depthMinDraft,
            displayValue = "${(depthMinDraft * 100).roundToInt()}%",
            activeColor = OssmAccent,
            onValueChange = { newValue ->
                val clamped = newValue.coerceAtMost(depthMaxDraft - MIN_DEPTH_SPAN)
                depthMinDraft = clamped
                val now = System.currentTimeMillis()
                if (now - lastDepthSendMs >= OVERLAY_LIVE_THROTTLE_MS) {
                    lastDepthSendMs = now
                    onDepthLive(clamped, depthMaxDraft)
                }
            },
            onValueCommit = { onDepthCommit(depthMinDraft, depthMaxDraft) }
        )

        Spacer(Modifier.height(10.dp))
        OverlaySlider(
            label = stringResource(R.string.prof_max),
            value = depthMaxDraft,
            displayValue = "${(depthMaxDraft * 100).roundToInt()}%",
            activeColor = OssmConnected,
            onValueChange = { newValue ->
                val clamped = newValue.coerceAtLeast(depthMinDraft + MIN_DEPTH_SPAN)
                depthMaxDraft = clamped
                val now = System.currentTimeMillis()
                if (now - lastDepthSendMs >= OVERLAY_LIVE_THROTTLE_MS) {
                    lastDepthSendMs = now
                    onDepthLive(depthMinDraft, clamped)
                }
            },
            onValueCommit = { onDepthCommit(depthMinDraft, depthMaxDraft) }
        )
    }
}

@Composable
private fun OverlaySlider(
    label: String,
    value: Float,
    displayValue: String,
    activeColor: Color,
    onValueChange: (Float) -> Unit,
    onValueCommit: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                color = OssmPrimaryLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = displayValue,
                color = OssmOnSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { /* consume tap — no value jump */ } }
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = { onValueCommit(value) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = activeColor,
                    activeTrackColor = activeColor,
                    inactiveTrackColor = activeColor.copy(alpha = 0.25f)
                )
            )
        }
    }
}

@Composable
private fun statusLabel(state: OverlayViewState): String = when {
    state.hasError -> stringResource(R.string.overlay_status_error)
    !state.isConnected -> stringResource(R.string.overlay_status_disconnected)
    state.isPaused -> stringResource(R.string.overlay_status_paused)
    state.isRunning -> stringResource(R.string.overlay_status_running)
    else -> stringResource(R.string.overlay_status_ready)
}

@Composable
private fun statusColor(state: OverlayViewState): Color = when {
    state.hasError -> OssmError
    !state.isConnected -> Color(0xFF9E9E9E)
    state.isPaused -> OssmWarning
    state.isRunning -> OssmConnected
    else -> OssmPrimaryLight
}

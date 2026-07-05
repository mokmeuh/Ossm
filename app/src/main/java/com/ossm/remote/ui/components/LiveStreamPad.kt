package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight

/**
 * RELATIVE live-control pad.
 *
 * The whole pad is the control surface. On finger-down the touch point is
 * anchored to the actuator's CURRENT logical position (no jump). Dragging the
 * finger UP raises the position toward 100%, dragging back DOWN past the anchor
 * lowers it toward 0%. When the finger lifts, the position is held; the next
 * touch re-anchors from that held position.
 *
 * Full pad height == 100% of range, so a drag of the whole pad spans the whole
 * range; a short drag is a proportional change.
 *
 * onPositionChange(positionPercent, elapsedMs): elapsedMs is time since the last
 * emit, passed to OSSM as travel time so actuator speed follows finger speed.
 */
@Composable
fun LiveStreamPad(
    enabled: Boolean,
    onTarget: (positionPercent: Int) -> Unit,
    onActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var padHeightPx by remember { mutableFloatStateOf(0f) }
    var logicalPos by remember { mutableFloatStateOf(0f) }   // 0..100, persists across touches
    var anchorY by remember { mutableFloatStateOf(0f) }
    var anchorPos by remember { mutableFloatStateOf(0f) }

    // Quand le mode (re)devient prêt, la machine vient d'être replacée au home (0 %) :
    // on repart du même point pour que l'affichage reflète la position réelle.
    LaunchedEffect(enabled) {
        if (enabled) logicalPos = 0f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        OssmPrimary.copy(alpha = if (enabled) 0.18f else 0.06f),
                        OssmAccent.copy(alpha = if (enabled) 0.10f else 0.04f),
                        OssmGlass.copy(alpha = 0.4f)
                    )
                )
            )
            .border(
                1.dp,
                OssmPrimaryLight.copy(alpha = if (enabled) 0.5f else 0.15f),
                RoundedCornerShape(20.dp)
            )
            .onSizeChanged { padHeightPx = it.height.toFloat() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        // Anchor: this touch point maps to the actuator's current position.
                        anchorY = offset.y
                        anchorPos = logicalPos
                        onActive(true)   // start the cadence ticker; no jump on touch-down
                    },
                    onDrag = { change, _ ->
                        // Consomme le geste pour que le scroll vertical de la page
                        // ne vole JAMAIS le drag du pad.
                        change.consume()
                        if (padHeightPx <= 0f) return@detectDragGestures
                        // Up (smaller y) increases; full pad height == full 0..100 range.
                        val deltaPct = (anchorY - change.position.y) / padHeightPx * 100f
                        logicalPos = (anchorPos + deltaPct).coerceIn(0f, 100f)
                        onTarget(logicalPos.toInt())
                    },
                    onDragEnd = { onActive(false) },
                    onDragCancel = { onActive(false) }
                )
            }
    ) {
        // Fill indicator rising from the bottom = current logical position.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((300 * (logicalPos / 100f)).dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            OssmAccent.copy(alpha = 0.55f),
                            OssmPrimary.copy(alpha = 0.75f)
                        )
                    )
                )
        )
        Text(
            text = "${logicalPos.toInt()}%",
            color = if (enabled) Color.White else OssmOnSurface.copy(alpha = 0.3f),
            fontSize = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.align(Alignment.Center)
        )
        Text(
            text = if (enabled) "Touche puis glisse — relatif" else "Préparation…",
            color = OssmOnSurface.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        )
    }
}

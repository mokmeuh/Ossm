package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import kotlin.math.roundToInt

@Composable
fun StreamPositionSlider(
    positionPercent: Int,
    enabled: Boolean,
    onPositionCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = draft,
                onValueChange = { draft = it.coerceIn(0f, 100f) },
                onValueChangeFinished = { onPositionCommit(draft.roundToInt()) },
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = SliderDefaults.colors(
                    thumbColor = OssmAccent,
                    activeTrackColor = OssmAccent,
                    inactiveTrackColor = OssmAccent.copy(alpha = 0.24f),
                    disabledThumbColor = OssmAccent.copy(alpha = 0.3f),
                    disabledActiveTrackColor = OssmAccent.copy(alpha = 0.16f),
                    disabledInactiveTrackColor = OssmAccent.copy(alpha = 0.08f)
                )
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Home", color = OssmOnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(
                if (enabled) "Relache pour envoyer" else "Preparation...",
                color = OssmOnSurface.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            Text("Fond", color = OssmOnSurface.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

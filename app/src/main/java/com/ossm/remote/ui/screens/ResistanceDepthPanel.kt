package com.ossm.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ble.DepthByLoadProtocol
import com.ossm.remote.ble.DepthByLoadState
import com.ossm.remote.session.DepthByLoadSettings
import com.ossm.remote.ui.theme.OssmAccent
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnPrimary
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmWarning

/** Tout ce dont le panneau a besoin : états lus et actions, sans logique de sécurité. */
data class ResistanceDepthPanelModel(
    val state: DepthByLoadState,
    val settings: DepthByLoadSettings,
    /** Connecté et sur un pattern Stroke Engine : le seul cas où l'activation a un sens. */
    val canEnable: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val onSensitivityCommit: (Int) -> Unit,
    val onFloorCommit: (Int) -> Unit,
)

/** Le panneau n'existe que si la machine publie la caractéristique (sinon : rien, pas d'erreur). */
internal fun shouldShowResistanceDepthPanel(state: DepthByLoadState): Boolean = state.present

/** L'interrupteur se coupe toujours ; il ne s'allume que si la machine est prête. */
internal fun canToggleResistanceDepth(model: ResistanceDepthPanelModel): Boolean =
    model.state.enabled || (model.state.usable && model.canEnable)

internal fun permilleToPercent(permille: Int): Int = (permille.coerceIn(0, 1_000) + 5) / 10

@Composable
fun ResistanceDepthPanel(model: ResistanceDepthPanelModel, modifier: Modifier = Modifier) {
    val state = model.state
    if (!shouldShowResistanceDepthPanel(state)) return
    val toggleable = canToggleResistanceDepth(model)
    val title = stringResource(R.string.resistance_depth_title)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = state.enabled,
                    enabled = toggleable,
                    role = Role.Switch,
                    onValueChange = model.onEnabledChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = OssmOnSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = null,
                enabled = toggleable,
                modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OssmOnPrimary,
                    checkedTrackColor = OssmPrimary,
                ),
            )
        }
        Text(
            text = stringResource(R.string.resistance_depth_hint),
            color = OssmOnSurfaceMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        when {
            state.unavailable -> Text(
                text = stringResource(R.string.resistance_depth_unavailable),
                color = OssmWarning,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            !model.canEnable && !state.enabled -> Text(
                text = stringResource(R.string.resistance_depth_needs_stroke_engine),
                color = OssmOnSurfaceMuted,
                fontSize = 12.sp,
            )
        }

        PercentSlider(
            label = stringResource(R.string.resistance_depth_sensitivity),
            value = model.settings.sensitivityPercent,
            range = 0..DepthByLoadProtocol.MAX_SENSITIVITY_PERCENT,
            onCommit = model.onSensitivityCommit,
        )
        PercentSlider(
            label = stringResource(R.string.resistance_depth_floor),
            value = model.settings.floorPercent,
            range = DepthByLoadProtocol.MIN_FLOOR_PERCENT..DepthByLoadProtocol.MAX_FLOOR_PERCENT,
            onCommit = model.onFloorCommit,
        )

        if (state.enabled) {
            LiveGauge(
                label = stringResource(R.string.resistance_depth_load),
                permille = state.loadPermille,
            )
            LiveGauge(
                label = stringResource(R.string.resistance_depth_applied),
                permille = state.depthFactorPermille,
            )
        }
    }
}

@Composable
private fun PercentSlider(
    label: String,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    var draft by remember { mutableFloatStateOf(value.toFloat()) }
    LaunchedEffect(value) { draft = value.toFloat() }
    val shown = draft.toInt().coerceIn(range.first, range.last)
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = OssmOnSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(text = "$shown %", color = OssmAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            // Une seule écriture à la fin du geste : pas de rafale vers la machine.
            onValueChangeFinished = { onCommit(draft.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "$label $shown %" },
            colors = SliderDefaults.colors(thumbColor = OssmAccent, activeTrackColor = OssmAccent),
        )
    }
}

@Composable
private fun LiveGauge(label: String, permille: Int) {
    val percent = permilleToPercent(permille)
    Column(modifier = Modifier.semantics { contentDescription = "$label $percent %" }) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, color = OssmOnSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(text = "$percent %", color = OssmAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { permille.coerceIn(0, 1_000) / 1_000f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = OssmAccent,
            trackColor = OssmGlassBorder,
        )
    }
}

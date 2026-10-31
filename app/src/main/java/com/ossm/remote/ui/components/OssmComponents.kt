package com.ossm.remote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnPrimary
import com.ossm.remote.ui.theme.OssmOnStop
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmPrimaryLight
import com.ossm.remote.ui.theme.OssmSectionTitle
import com.ossm.remote.ui.theme.OssmStop
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised
import com.ossm.remote.ui.theme.OssmWarning

/*
 * Composants partagés du design « Instrument » (voir design-system/ossm-remote/MASTER.md).
 * Tous respectent la cible tactile de 48 dp et ne portent aucune logique de contrôle.
 */

/** Carte avec titre optionnel en majuscules (style `section`). */
@Composable
fun OssmCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = OssmSectionTitle,
                color = OssmOnSurfaceMuted,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(OssmDimens.Space12))
        }
        Column(verticalArrangement = Arrangement.spacedBy(OssmDimens.Space12), content = content)
    }
}

enum class OssmButtonKind { Primary, Secondary, Danger, Text }

/** Bouton à hauteur minimale de 48 dp, avec icône facultative. */
@Composable
fun OssmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: OssmButtonKind = OssmButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(OssmDimens.ControlRadius)
    val minSize = Modifier.heightIn(min = OssmDimens.TouchTarget)
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(OssmDimens.Space8))
            }
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val padding = PaddingValues(horizontal = OssmDimens.Space16, vertical = OssmDimens.Space8)
    when (kind) {
        OssmButtonKind.Primary -> Button(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = OssmPrimary, contentColor = OssmOnPrimary,
                disabledContainerColor = OssmSurfaceHigh, disabledContentColor = OssmOnSurfaceMuted
            ),
            modifier = modifier.then(minSize), content = { label() }
        )
        OssmButtonKind.Danger -> Button(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = OssmStop, contentColor = OssmOnStop,
                disabledContainerColor = OssmSurfaceHigh, disabledContentColor = OssmOnSurfaceMuted
            ),
            modifier = modifier.then(minSize), content = { label() }
        )
        OssmButtonKind.Secondary -> OutlinedButton(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            border = BorderStroke(OssmDimens.BorderWidth, if (enabled) OssmOutline else OssmGlassBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = OssmSurfaceRaised, contentColor = OssmOnSurface,
                disabledContainerColor = OssmSurfaceRaised, disabledContentColor = OssmOnSurfaceMuted
            ),
            modifier = modifier.then(minSize), content = { label() }
        )
        OssmButtonKind.Text -> TextButton(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = OssmPrimaryLight, disabledContentColor = OssmOnSurfaceMuted
            ),
            modifier = modifier.then(minSize), content = { label() }
        )
    }
}

/** Rangée entière cliquable : libellé (et aide) à gauche, interrupteur à droite. */
@Composable
fun OssmSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OssmDimens.TouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = if (enabled) OssmOnSurface else OssmOnSurfaceMuted, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, color = OssmOnSurfaceMuted, style = OssmCaption)
            }
        }
        Spacer(Modifier.width(OssmDimens.Space12))
        // La rangée porte l'interaction et la sémantique : l'interrupteur reste décoratif.
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OssmOnPrimary, checkedTrackColor = OssmPrimary,
                uncheckedThumbColor = OssmOnSurfaceMuted, uncheckedTrackColor = OssmSurfaceRaised,
                uncheckedBorderColor = OssmOutline
            )
        )
    }
}

enum class OssmTone { Neutral, Ok, Warn, Error }

/** Message d'état : icône + texte, jamais la couleur seule. */
@Composable
fun OssmStatusLine(
    text: String,
    modifier: Modifier = Modifier,
    tone: OssmTone = OssmTone.Neutral
) {
    val (icon, color) = when (tone) {
        OssmTone.Neutral -> Icons.Filled.Info to OssmOnSurfaceMuted
        OssmTone.Ok -> Icons.Filled.CheckCircle to OssmConnected
        OssmTone.Warn -> Icons.Filled.WarningAmber to OssmWarning
        OssmTone.Error -> Icons.Filled.ErrorOutline to OssmError
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(OssmDimens.Space8))
        Text(text, color = color, style = OssmCaption.copy(fontSize = 13.sp))
    }
}


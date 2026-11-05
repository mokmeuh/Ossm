package com.ossm.remote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmSurfaceRaised

/**
 * Puce de mode de la grille de patterns. Repos : surface surélevée + contour. Actif : surface
 * « haute », contour `OssmPrimary` de 2 dp, coche devant le nom et état « Actif » lu par TalkBack
 * (l'état ne repose donc pas sur la couleur). Hauteur minimale 48 dp ; le parent impose la hauteur
 * réelle. Le nom passe sur deux lignes si besoin (grande taille de police).
 *
 * [color] est conservé pour ne pas changer l'appel : la couleur n'est plus décorative, elle n'est
 * plus utilisée (une couleur = un sens).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun PatternButton(
    label: String,
    color: Color,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val shape = RoundedCornerShape(OssmDimens.ControlRadius)
    val container by animateColorAsState(
        targetValue = if (isActive) OssmSurfaceHigh else OssmSurfaceRaised,
        animationSpec = tween(150),
        label = "pattern_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) OssmPrimary else OssmOutline,
        animationSpec = tween(150),
        label = "pattern_border"
    )
    val textColor = if (enabled) OssmOnSurface else OssmOnSurfaceMuted
    val activeDescription = stringResource(R.string.shell_pattern_active)

    Box(
        modifier = modifier
            .heightIn(min = OssmDimens.TouchTarget)
            .clip(shape)
            .background(container)
            .border(
                width = if (isActive) OssmDimens.BorderWidth * 2 else OssmDimens.BorderWidth,
                color = borderColor,
                shape = shape
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .semantics {
                selected = isActive
                if (isActive) stateDescription = activeDescription
            }
            .padding(horizontal = OssmDimens.Space12),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = OssmPrimary,
                    modifier = Modifier.size(OssmDimens.Space16)
                )
                Spacer(Modifier.width(OssmDimens.Space8))
            }
            Text(
                text = label,
                color = textColor,
                fontSize = fontSize,
                // Interligne laissé à la police : il suit la taille réelle (13 ou 16 sp, échelle système).
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = TextUnit.Unspecified
                ),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

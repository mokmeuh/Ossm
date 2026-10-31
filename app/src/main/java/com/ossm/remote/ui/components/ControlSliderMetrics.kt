package com.ossm.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.ossm.remote.ui.modes.LocalModeContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmCaption
import com.ossm.remote.ui.theme.OssmConnected
import com.ossm.remote.ui.theme.OssmDepth
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmError
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmOnSurface
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmOutline
import com.ossm.remote.ui.theme.OssmSensation
import com.ossm.remote.ui.theme.OssmSurface
import com.ossm.remote.ui.theme.OssmSurfaceHigh
import com.ossm.remote.ui.theme.OssmTextFaint
import com.ossm.remote.ui.theme.OssmValue
import com.ossm.remote.ui.theme.OssmWarning

/**
 * Géométrie commune des curseurs à barre (scalaire et plage). Piste de 8 dp, poignée ronde de
 * 28 dp dans une zone tactile d'au moins 48 dp de haut ; un appui à moins de [grabRadius] d'une
 * poignée la prend là où elle est (glissé relatif), plus loin la poignée la plus proche saute
 * sous le doigt.
 *
 * [inlineHeader] met le libellé, les cases et la valeur sur la même ligne que la piste
 * (paysage), pour que tous les curseurs restent visibles sans défilement.
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
    /** Conservé pour compatibilité : les symboles −/+ sont désormais des icônes. */
    val symbolSize: TextUnit,
    val grabRadius: Dp,
    val inlineHeader: Boolean = false,
    val labelColumnWidth: Dp = 0.dp,
    val valueColumnWidth: Dp = 0.dp,
    val showScaleLegends: Boolean = true,
    val toggleMinSize: Dp = OssmDimens.TouchTarget,
    /** Écart entre les boutons −/+ et la piste (8 dp, 4 dp en ligne unique). */
    val controlGap: Dp = OssmDimens.TouchGap
)

val RegularControlSliderMetrics = ControlSliderMetrics(
    sliderHeight = 56.dp,
    trackHeight = OssmDimens.SliderTrack,
    // La poignée (rayon 14 dp) reste entière dans la zone de la piste : elle ne déborde pas
    // sur les boutons −/+ à 0 % et à 100 %.
    trackInset = OssmDimens.SliderThumb / 2,
    thumbWidth = OssmDimens.SliderThumb,
    thumbHeight = OssmDimens.SliderThumb,
    buttonSize = 56.dp,
    labelSize = 13.sp,
    valueSize = 16.sp,
    symbolSize = 30.sp,
    grabRadius = 28.dp
)

/** Commandes plus grandes quand la carte de contrôle dispose de toute la hauteur de l'écran. */
val LargeControlSliderMetrics = ControlSliderMetrics(
    sliderHeight = 88.dp,
    trackHeight = OssmDimens.SliderTrack,
    trackInset = OssmDimens.SliderThumb / 2,
    thumbWidth = OssmDimens.SliderThumb,
    thumbHeight = OssmDimens.SliderThumb,
    buttonSize = 72.dp,
    labelSize = 16.sp,
    valueSize = 20.sp,
    symbolSize = 38.sp,
    grabRadius = 40.dp
)

/** Une ligne par curseur, pour le paysage ou les cartes courtes. */
val InlineControlSliderMetrics = ControlSliderMetrics(
    sliderHeight = 52.dp,
    trackHeight = OssmDimens.SliderTrack,
    trackInset = OssmDimens.SliderThumb / 2,
    thumbWidth = OssmDimens.SliderThumb,
    thumbHeight = OssmDimens.SliderThumb,
    buttonSize = 48.dp,
    labelSize = 12.sp,
    valueSize = 16.sp,
    symbolSize = 26.sp,
    grabRadius = 30.dp,
    inlineHeader = true,
    labelColumnWidth = 118.dp,
    valueColumnWidth = 84.dp,
    showScaleLegends = false,
    toggleMinSize = OssmDimens.TouchTarget,
    controlGap = OssmDimens.Space4
)

val LocalControlSliderMetrics = staticCompositionLocalOf { RegularControlSliderMetrics }

/**
 * Compose un curseur : en-tête (nom du canal, mot « limité » éventuel, accessoire, valeur) au-dessus
 * des commandes, ou tout sur une ligne quand [ControlSliderMetrics.inlineHeader] est actif.
 * [controls] sont les boutons −/+ et la piste, posés dans une Row (mettre weight(1f) sur la piste).
 *
 * [channelColor] colore le nom du canal (vitesse, profondeur, sensation). [limited] signale un
 * plafond imposé (session distante) : cadenas, mot « limité » et valeur en ambre, jamais la
 * couleur seule.
 */
@Composable
fun SliderFrame(
    label: String,
    displayValue: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    displayValueColor: Color? = null,
    headerAccessory: (@Composable RowScope.() -> Unit)? = null,
    channelColor: Color = OssmOnSurfaceMuted,
    limited: Boolean = LocalModeContext.current.remoteLimited,
    controls: @Composable RowScope.() -> Unit
) {
    val metrics = LocalControlSliderMetrics.current
    val valueColor = displayValueColor ?: when {
        !enabled -> OssmTextFaint
        limited -> OssmWarning
        else -> OssmOnSurface
    }
    val labelText: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = label.uppercase(),
            color = if (enabled) channelColor else channelColor.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = metrics.labelSize,
                lineHeight = metrics.labelSize * 1.3f,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier
        )
    }
    val valueText: @Composable (Modifier) -> Unit = { textModifier ->
        Text(
            text = displayValue,
            color = valueColor,
            style = OssmValue.copy(
                fontSize = metrics.valueSize,
                lineHeight = metrics.valueSize * 1.25f
            ),
            maxLines = if (metrics.inlineHeader) 2 else 1,
            textAlign = TextAlign.End,
            modifier = textModifier
        )
    }
    val limitBadge: @Composable () -> Unit = {
        if (limited) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = OssmWarning,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.sld_limited),
                    color = OssmWarning,
                    style = OssmCaption,
                    maxLines = 1
                )
            }
        }
    }
    if (metrics.inlineHeader) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)
        ) {
            Column(modifier = Modifier.width(metrics.labelColumnWidth)) {
                labelText(Modifier)
                headerAccessory?.let { accessory ->
                    Row(verticalAlignment = Alignment.CenterVertically) { accessory() }
                }
            }
            controls()
            Column(
                modifier = Modifier.width(metrics.valueColumnWidth),
                horizontalAlignment = Alignment.End
            ) {
                valueText(Modifier)
                limitBadge()
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(OssmDimens.Space4)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OssmDimens.Space8)
                ) {
                    // Le nom cède la place avant les cases : il passe sur deux lignes au besoin.
                    labelText(Modifier.weight(1f, fill = false))
                    limitBadge()
                    headerAccessory?.invoke(this)
                }
                valueText(Modifier.padding(start = OssmDimens.Space8))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.controlGap)
            ) {
                controls()
            }
        }
    }
}

/**
 * Curseur signé (sensation) : le remplissage part du centre, vert côté positif, rouge côté négatif,
 * et un fin repère bleu reste sur le centre (le zéro magnétique). [channelColor] est la couleur du
 * canal sensation (nom, boutons −/+, poignée au neutre).
 */
@Immutable
data class BipolarStyle(
    val centerPercent: Float = 50f,
    val positiveColor: Color = SignedPositiveColor,
    val negativeColor: Color = SignedNegativeColor,
    val centerTickColor: Color = CenterTickBlue,
    val channelColor: Color = OssmSensation
)

val SignedPositiveColor = OssmConnected
val SignedNegativeColor = OssmError
val CenterTickBlue = OssmDepth

/** Couleur d'une valeur signée (0..1, 0,5 = neutre) : vert au-dessus, rouge en dessous, null au neutre. */
fun signedValueColor(value: Float): Color? = when {
    value > 0.5005f -> SignedPositiveColor
    value < 0.4995f -> SignedNegativeColor
    else -> null
}

/**
 * Piste vide : fond sombre cerné d'un filet `outline` (contraste 4:1), arrondie sur toute sa
 * hauteur. Désactivée, le filet s'efface.
 */
internal fun DrawScope.drawSliderTrack(
    left: Float,
    centerY: Float,
    width: Float,
    heightPx: Float,
    enabled: Boolean
) {
    val corner = CornerRadius(heightPx / 2f)
    val topLeft = Offset(left, centerY - heightPx / 2f)
    drawRoundRect(
        color = if (enabled) OssmSurfaceHigh else OssmSurface,
        topLeft = topLeft,
        size = Size(width, heightPx),
        cornerRadius = corner
    )
    drawRoundRect(
        color = if (enabled) OssmOutline else OssmGlassBorder,
        topLeft = topLeft,
        size = Size(width, heightPx),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx())
    )
}

/**
 * Poignée ronde : anneau clair de 4 dp. Pleine (centre à la couleur du canal) quand elle est
 * celle que les boutons −/+ déplacent ; creuse (petit point de couleur) sinon. [haloColor] ajoute
 * un halo discret autour de la poignée sélectionnée.
 */
internal fun DrawScope.drawSliderThumb(
    center: Offset,
    radiusPx: Float,
    color: Color,
    alpha: Float,
    filled: Boolean = true,
    haloColor: Color? = null
) {
    val ringPx = 4.dp.toPx()
    if (haloColor != null) {
        drawCircle(
            color = haloColor.copy(alpha = 0.30f * alpha),
            radius = radiusPx + 6.dp.toPx(),
            center = center
        )
    }
    drawCircle(color = OssmOnSurface.copy(alpha = alpha), radius = radiusPx, center = center)
    if (filled) {
        drawCircle(color = color.copy(alpha = alpha), radius = radiusPx - ringPx, center = center)
    } else {
        drawCircle(color = OssmSurfaceHigh, radius = radiusPx - ringPx, center = center)
        drawCircle(color = color.copy(alpha = alpha), radius = ringPx, center = center)
    }
}

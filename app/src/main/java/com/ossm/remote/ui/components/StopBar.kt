package com.ossm.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmOnStop
import com.ossm.remote.ui.theme.OssmStop

/**
 * Hauteur de la barre STOP (64 dp minimum, voir le design « Instrument »). La rangée de pause
 * et le verrou de rotation se calent sur cette valeur : ne pas la réduire.
 */
val StopBarHeight = OssmDimens.StopHeight

/** Hauteur à partir de laquelle la barre debout affiche aussi l'icône au-dessus des lettres. */
private val StackedIconMinHeight = 200.dp

/**
 * STOP compact de l'écran Contrôle : grand bouton rouge `OssmStop`, icône et mot « STOP » en blanc
 * (contraste 5,3), au moins [StopBarHeight] de haut. Jamais désactivé. L'icône et le mot sont
 * décoratifs pour TalkBack : seule la description « STOP, arrêt immédiat » est annoncée.
 */
@Composable
fun StopBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Barre debout (paysage) : S, T, O, P écrits les uns sous les autres. */
    vertical: Boolean = false
) {
    val description = stringResource(R.string.control_stop_bar_cd)
    val word = stringResource(R.string.shell_stop_word)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = StopBarHeight)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(OssmDimens.ControlRadius),
        contentPadding = if (vertical) PaddingValues(0.dp) else PaddingValues(horizontal = OssmDimens.Space16),
        colors = ButtonDefaults.buttonColors(
            containerColor = OssmStop,
            contentColor = OssmOnStop
        )
    ) {
        if (vertical) StopBarStackedContent(word) else StopBarRowContent(word)
    }
}

/** Style du mot STOP : gras, lettres espacées, taille du titre de l'échelle typographique. */
@Composable
private fun stopWordStyle(): TextStyle =
    MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)

/** Barre couchée : icône puis mot, centrés. */
@Composable
private fun StopBarRowContent(word: String) {
    Row(
        modifier = Modifier.clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.StopCircle,
            contentDescription = null,
            modifier = Modifier.size(OssmDimens.Space24)
        )
        Spacer(Modifier.width(OssmDimens.Space8))
        Text(text = word, style = stopWordStyle(), textAlign = TextAlign.Center)
    }
}

/** Barre debout : lettres empilées, icône ajoutée seulement s'il reste assez de hauteur. */
@Composable
private fun StopBarStackedContent(word: String) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        val showIcon = maxHeight >= StackedIconMinHeight
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showIcon) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = null,
                    modifier = Modifier.size(OssmDimens.Space24)
                )
                Spacer(Modifier.size(OssmDimens.Space8))
            }
            Text(
                text = word.toCharArray().joinToString("\n"),
                style = stopWordStyle().copy(letterSpacing = 0.sp),
                textAlign = TextAlign.Center
            )
        }
    }
}

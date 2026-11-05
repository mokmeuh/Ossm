package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ossm.remote.R
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmOnPrimary
import com.ossm.remote.ui.theme.OssmOnSurfaceMuted
import com.ossm.remote.ui.theme.OssmPrimary
import com.ossm.remote.ui.theme.OssmSurfaceHigh

/** Diamètre du bouton HOME rond. */
private val HomeButtonDiameter = OssmDimens.StopHeight + OssmDimens.Space8

/**
 * Bouton rond HOME : fond plein `OssmPrimary` (action principale), mot en `OssmOnPrimary`.
 * Désactivé, il passe au niveau de surface « haut » avec un texte atténué. Rôle bouton et
 * retour visuel d'appui fournis par `clickable` (lu par TalkBack).
 */
@Composable
fun HomeButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(HomeButtonDiameter)
            .clip(CircleShape)
            .background(if (enabled) OssmPrimary else OssmSurfaceHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        Text(
            text = stringResource(R.string.shell_home_word),
            color = if (enabled) OssmOnPrimary else OssmOnSurfaceMuted,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        )
    }
}

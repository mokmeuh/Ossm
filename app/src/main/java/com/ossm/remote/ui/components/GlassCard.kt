package com.ossm.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import com.ossm.remote.ui.theme.OssmDimens
import com.ossm.remote.ui.theme.OssmGlass
import com.ossm.remote.ui.theme.OssmGlassBorder
import com.ossm.remote.ui.theme.OssmSurface

/**
 * Carte « Instrument » : surface unie, filet de 1 dp, coins de 16 dp, sans flou ni lueur.
 * Le nom historique est conservé pour ne pas changer les appels existants.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = OssmDimens.CardRadius,
    tint: Color = OssmGlass,
    padding: Dp = OssmDimens.Space16,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.06f).compositeOver(OssmSurface))
            .border(
                width = OssmDimens.BorderWidth,
                color = OssmGlassBorder,
                shape = shape
            )
            .padding(padding),
        content = content
    )
}

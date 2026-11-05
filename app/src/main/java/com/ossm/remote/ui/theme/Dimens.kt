package com.ossm.remote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Grille de 4 dp, cibles tactiles et formes de l'interface « Instrument ». */
object OssmDimens {
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space24 = 24.dp
    val Space32 = 32.dp

    /** Gouttière horizontale des écrans. */
    val ScreenGutter = 16.dp

    /** Cible tactile minimale (Android) et écart minimal entre deux cibles. */
    val TouchTarget = 48.dp
    val TouchGap = 8.dp

    /** Hauteur minimale du bouton STOP. */
    val StopHeight = 64.dp

    val CardRadius = 16.dp
    val ControlRadius = 12.dp
    val FieldRadius = 8.dp
    val SheetRadius = 24.dp

    val BorderWidth = 1.dp
    val SliderTrack = 8.dp
    val SliderThumb = 28.dp
}

val OssmShapes = Shapes(
    extraSmall = RoundedCornerShape(OssmDimens.FieldRadius),
    small = RoundedCornerShape(OssmDimens.FieldRadius),
    medium = RoundedCornerShape(OssmDimens.ControlRadius),
    large = RoundedCornerShape(OssmDimens.CardRadius),
    extraLarge = RoundedCornerShape(OssmDimens.SheetRadius)
)

package com.ossm.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OssmColorScheme = darkColorScheme(
    primary          = OssmPrimary,
    onPrimary        = OssmOnPrimary,
    primaryContainer = OssmSurfaceRaised,
    onPrimaryContainer = OssmPrimaryLight,
    secondary        = OssmAccent,
    onSecondary      = OssmBackground,
    background       = OssmBackground,
    surface          = OssmSurface,
    onSurface        = OssmOnSurface,
    onBackground     = OssmOnSurface,
    surfaceVariant   = OssmSurfaceRaised,
    onSurfaceVariant = OssmOnSurfaceMuted,
    outline          = OssmOutline,
    outlineVariant   = OssmGlassBorder,
    error            = OssmError,
    onError          = OssmBackground
)

@Composable
fun OssmRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OssmColorScheme,
        typography  = OssmTypography,
        content     = content
    )
}

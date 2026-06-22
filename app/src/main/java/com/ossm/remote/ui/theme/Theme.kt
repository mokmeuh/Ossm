package com.ossm.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val OssmColorScheme = darkColorScheme(
    primary          = OssmPrimary,
    onPrimary        = OssmOnSurface,
    primaryContainer = OssmGlass,
    secondary        = OssmAccent,
    onSecondary      = OssmBackground,
    background       = OssmBackground,
    surface          = OssmSurface,
    onSurface        = OssmOnSurface,
    onBackground     = OssmOnSurface,
    error            = OssmError
)

@Composable
fun OssmRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OssmColorScheme,
        typography  = OssmTypography,
        content     = content
    )
}

package com.ossm.remote.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Scan        : Screen("scan",        "Scanner",      Icons.Default.Bluetooth)
    object Control     : Screen("control",     "Contrôle",     Icons.Default.Tune)
    object Diagnostics : Screen("diagnostics", "Diagnostics",  Icons.Default.BugReport)
    object Profiles    : Screen("profiles",    "Préréglages",  Icons.Default.Bookmark)
    object Funscript   : Screen("funscript",   "Funscript",    Icons.Default.PlayCircle)
}

val BottomNavScreens = listOf(
    Screen.Scan,
    Screen.Control,
    Screen.Funscript,
    Screen.Profiles,
    Screen.Diagnostics
)

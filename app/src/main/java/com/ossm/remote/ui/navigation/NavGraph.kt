package com.ossm.remote.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Scan : Screen("scan", "Scanner", Icons.Default.Bluetooth)
    object Control : Screen("control", "Control", Icons.Default.Tune)
    object Diagnostics : Screen("diagnostics", "Diag", Icons.Default.BugReport)
    object Profiles : Screen("profiles", "Reglages", Icons.Default.Bookmark)
    object Funscript : Screen("funscript", "Funscript", Icons.Default.PlayCircle)
    object VideoSync : Screen("videosync", "Video", Icons.Default.Movie)
}

val BottomNavScreens = listOf(
    Screen.Scan,
    Screen.Control,
    Screen.Funscript,
    Screen.VideoSync,
    Screen.Profiles,
    Screen.Diagnostics
)

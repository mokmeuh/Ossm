package com.ossm.remote.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.ossm.remote.R

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Scan : Screen("scan", "Scanner", Icons.Default.Bluetooth)
    object Control : Screen("control", "Control", Icons.Default.Tune)
    object Diagnostics : Screen("diagnostics", "Diag", Icons.Default.BugReport)
    object Profiles : Screen("profiles", "Reglages", Icons.Default.Bookmark)
    /** Full-screen "share my camera" route opened from the Connexion tab; not a bottom-bar page. */
    object CameraShare : Screen("camera_share", "Camera", Icons.Default.Videocam)
}

val BottomNavScreens = listOf(
    Screen.Scan,
    Screen.Control,
    Screen.Profiles,
    Screen.Diagnostics
)

val Screen.navigationLabel: Int
    get() = when (this) {
        Screen.Scan -> R.string.nav_scan
        Screen.Control -> R.string.nav_control
        Screen.Profiles -> R.string.nav_profiles
        Screen.Diagnostics -> R.string.nav_diagnostics
        Screen.CameraShare -> R.string.ui2_camera_screen_title
    }

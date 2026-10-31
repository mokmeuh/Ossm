package com.ossm.remote.ui.theme

import androidx.compose.ui.graphics.Color

// Jetons « Instrument » : voir design-system/ossm-remote/MASTER.md.
// Niveaux de surface : fond -> surface -> surfaceRaised -> surfaceHigh.
val OssmBackground    = Color(0xFF0D1014)
val OssmSurface       = Color(0xFF151A20)
val OssmSurfaceRaised = Color(0xFF1D242C)
val OssmSurfaceHigh   = Color(0xFF26303A)
val OssmGlass         = OssmSurface
val OssmGlassBorder   = Color(0xFF2E3945)
val OssmOutline       = Color(0xFF6B7A89)

// Action principale et canal vitesse.
val OssmPrimary       = Color(0xFF3DD6C3)
val OssmPrimaryLight  = Color(0xFF7FE6D8)
val OssmOnPrimary     = Color(0xFF04201C)

// Canaux de réglage : profondeur (Accent) et sensation.
val OssmAccent        = Color(0xFF6EA8FF)
val OssmDepth         = OssmAccent
val OssmSensation     = Color(0xFFC79BFF)

// Arrêt : seule grande surface rouge de l'appli (texte blanc, contraste 5,3).
val OssmStop          = Color(0xFFC93036)
val OssmStopDark      = Color(0xFF7A1D21)
val OssmOnStop        = Color(0xFFFFFFFF)

// États.
val OssmConnected     = Color(0xFF5BDB95)
val OssmWarning       = Color(0xFFFFB84D)
val OssmError         = Color(0xFFFF8A8A)

// Texte.
val OssmOnSurface      = Color(0xFFEAF0F5)
val OssmOnSurfaceMuted = Color(0xFFA3B1BF)
val OssmTextFaint      = Color(0xFF7F8D9B)
val OssmSecondary      = OssmSurfaceRaised

// Pattern button colors
val PatternColors = listOf(
    OssmPrimary,
    OssmAccent,
    OssmWarning
)

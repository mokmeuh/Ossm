package com.ossm.remote.data.repository

/** Statistiques d'un pattern sur toutes les sessions. */
data class PatternStat(
    val patternKey: String,
    val patternName: String,
    val sessionCount: Int,
    val totalDurationMillis: Long
)

/** Un jour de l'historique (pour le graphique), zéro si aucun usage ce jour-là. */
data class DailyStat(
    val dateEpochDay: Long,
    val durationMillis: Long,
    val sessionCount: Int
)

/**
 * État complet des statistiques de sessions, recomputé à chaque changement en base.
 * Un snapshot "vide" n'a que des valeurs neutres (0), jamais de null : l'UI peut
 * afficher directement sans cas particulier.
 */
data class StatsSnapshot(
    val totalDurationMillis: Long = 0,
    val todayDurationMillis: Long = 0,
    val last7DaysDurationMillis: Long = 0,
    val last30DaysDurationMillis: Long = 0,
    val sessionCount: Int = 0,
    val avgSessionDurationMillis: Long = 0,
    val maxSessionDurationMillis: Long = 0,
    val perPattern: List<PatternStat> = emptyList(),
    val avgSpeedPercent: Float = 0f,
    val maxSpeedPercent: Float = 0f,
    val dailyHistory: List<DailyStat> = emptyList()
)

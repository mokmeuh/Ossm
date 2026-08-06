package com.ossm.remote.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Une session de contrôle enregistrée : une période où la machine était en mouvement
 * (isRunning), clôturée quand elle s'arrête. La durée exclut les pauses.
 *
 * Table unique volontairement : la répartition par pattern (pattern_usage) et
 * l'historique quotidien sont toujours dérivables par GROUP BY sur `sessions` ; une
 * table secondaire imposerait une synchronisation à chaque insert/import/reset sans
 * rien apporter.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationMillis: Long,
    val patternKey: String,
    val patternName: String,
    val avgSpeedPercent: Float,
    val maxSpeedPercent: Float,
    val depthMin: Float,
    val depthMax: Float,
    val avgSensationPercent: Float,
    val complete: Boolean,
    val dateEpochDay: Long
)

/** Jour calendaire local (epochDay) d'un horodatage, utilisé pour l'historique quotidien. */
internal fun epochDayOf(millis: Long): Long =
    LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).toEpochDay()

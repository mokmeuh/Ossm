package com.ossm.remote.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Agrégats globaux calculés en SQL sur toute la table `sessions`. */
data class SessionAggregate(
    val sessionCount: Int,
    val totalDurationMillis: Long,
    val avgDurationMillis: Long,
    val maxDurationMillis: Long,
    val avgSpeedPercent: Float,
    val maxSpeedPercent: Float
)

/** Durées cumulées sur les fenêtres calendaires (aujourd'hui / 7 / 30 jours). */
data class DurationWindow(
    val todayDurationMillis: Long,
    val last7DaysDurationMillis: Long,
    val last30DaysDurationMillis: Long
)

/** Répartition de durée par pattern (une ligne par patternKey). */
data class PatternUsageRow(
    val patternKey: String,
    val patternName: String,
    val sessionCount: Int,
    val totalDurationMillis: Long
)

/** Un jour calendaire de l'historique des sessions. */
data class DailyUsageRow(
    val dateEpochDay: Long,
    val durationMillis: Long,
    val sessionCount: Int
)

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY startTimeMillis DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM sessions")
    suspend fun getAllSessions(): List<SessionEntity>

    /** Remplace toutes les sessions par [sessions] de façon atomique (import). */
    @Transaction
    suspend fun replaceAll(sessions: List<SessionEntity>) {
        deleteAll()
        sessions.forEach { insert(it) }
    }

    @Query(
        """
        SELECT COUNT(*) AS sessionCount,
               COALESCE(SUM(durationMillis), 0) AS totalDurationMillis,
               COALESCE(CAST(AVG(durationMillis) AS INTEGER), 0) AS avgDurationMillis,
               COALESCE(MAX(durationMillis), 0) AS maxDurationMillis,
               COALESCE(AVG(avgSpeedPercent), 0) AS avgSpeedPercent,
               COALESCE(MAX(maxSpeedPercent), 0) AS maxSpeedPercent
        FROM sessions
        """
    )
    fun observeAggregates(): Flow<SessionAggregate>

    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN startTimeMillis >= :sinceToday THEN durationMillis END), 0) AS todayDurationMillis,
               COALESCE(SUM(CASE WHEN startTimeMillis >= :sinceLast7 THEN durationMillis END), 0) AS last7DaysDurationMillis,
               COALESCE(SUM(CASE WHEN startTimeMillis >= :sinceLast30 THEN durationMillis END), 0) AS last30DaysDurationMillis
        FROM sessions
        """
    )
    fun observeDurationWindow(
        sinceToday: Long,
        sinceLast7: Long,
        sinceLast30: Long
    ): Flow<DurationWindow>

    @Query(
        """
        SELECT patternKey, patternName, COUNT(*) AS sessionCount,
               COALESCE(SUM(durationMillis), 0) AS totalDurationMillis
        FROM sessions
        GROUP BY patternKey, patternName
        ORDER BY totalDurationMillis DESC
        """
    )
    fun observePerPattern(): Flow<List<PatternUsageRow>>

    @Query(
        """
        SELECT dateEpochDay, COALESCE(SUM(durationMillis), 0) AS durationMillis, COUNT(*) AS sessionCount
        FROM sessions
        WHERE dateEpochDay >= :fromEpochDay
        GROUP BY dateEpochDay
        ORDER BY dateEpochDay ASC
        """
    )
    fun observeDailyHistory(fromEpochDay: Long): Flow<List<DailyUsageRow>>
}

package com.ossm.remote.data.repository

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.ossm.remote.data.db.DailyUsageRow
import com.ossm.remote.data.db.SessionDao
import com.ossm.remote.data.db.SessionEntity
import com.ossm.remote.data.db.epochDayOf
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Format de l'export/import JSON : {"version":1,"exportedAtMillis":...,"sessions":[...]}. */
private data class SessionsExport(
    val version: Int,
    val exportedAtMillis: Long,
    val sessions: List<SessionEntity>
)

/**
 * Source unique de lecture/écriture des statistiques de sessions.
 *
 * Expose un [snapshot] recomputé à chaque changement en base, ainsi que
 * [resetAll], [exportSessions] et [importSessions]. Les fenêtres calendaires
 * (aujourd'hui / 7 / 30 jours) sont bornées à la construction : elles se recalent à
 * chaque démarrage de l'app et à chaque changement de session, ce qui est largement
 * suffisant pour un écran de statistiques personnel.
 */
@Singleton
class SessionStatsRepository @Inject constructor(
    private val dao: SessionDao
) {
    private val gson = GsonBuilder().create()

    private val today: LocalDate = LocalDate.now(ZoneId.systemDefault())
    private val todayStartMillis: Long =
        today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val last7StartMillis: Long =
        today.minusDays(LAST7_DAYS - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val last30StartMillis: Long =
        today.minusDays(LAST30_DAYS - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val historyFromEpochDay: Long = today.minusDays(LAST30_DAYS - 1).toEpochDay()

    /** Snapshot des statistiques, recomputé à chaque changement de la table `sessions`. */
    val snapshot: Flow<StatsSnapshot> = combine(
        dao.observeAggregates(),
        dao.observeDurationWindow(todayStartMillis, last7StartMillis, last30StartMillis),
        dao.observePerPattern(),
        dao.observeDailyHistory(historyFromEpochDay)
    ) { aggregate, window, perPattern, dailyHistory ->
        StatsSnapshot(
            totalDurationMillis = aggregate.totalDurationMillis,
            todayDurationMillis = window.todayDurationMillis,
            last7DaysDurationMillis = window.last7DaysDurationMillis,
            last30DaysDurationMillis = window.last30DaysDurationMillis,
            sessionCount = aggregate.sessionCount,
            avgSessionDurationMillis = aggregate.avgDurationMillis,
            maxSessionDurationMillis = aggregate.maxDurationMillis,
            perPattern = perPattern.map {
                PatternStat(
                    patternKey = it.patternKey,
                    patternName = it.patternName,
                    sessionCount = it.sessionCount,
                    totalDurationMillis = it.totalDurationMillis
                )
            },
            avgSpeedPercent = aggregate.avgSpeedPercent,
            maxSpeedPercent = aggregate.maxSpeedPercent,
            dailyHistory = buildDailyHistory(dailyHistory)
        )
    }

    /** Persiste une session clôturée par le tracker. */
    suspend fun recordSession(session: SessionEntity): Long = dao.insert(session)

    /** Vide entièrement la table des sessions. */
    suspend fun resetAll() {
        dao.deleteAll()
    }

    /** Export JSON complet des sessions, avec version et horodatage d'export. */
    suspend fun exportSessions(): String = gson.toJson(
        SessionsExport(
            version = EXPORT_VERSION,
            exportedAtMillis = System.currentTimeMillis(),
            sessions = dao.getAllSessions()
        )
    )

    /**
     * Remplace TOUTES les sessions par celles contenues dans [json]. Retourne le nombre
     * de sessions importées, ou [Result.failure] avec un message explicite si le format
     * est invalide (structure, version ou données incohérentes).
     */
    suspend fun importSessions(json: String): Result<Int> {
        val sessions = runCatching { parseSessions(json) }
            .getOrElse { error ->
                return Result.failure(IllegalArgumentException("Sauvegarde invalide : ${error.message}", error))
            }
        dao.replaceAll(sessions)
        return Result.success(sessions.size)
    }

    private fun parseSessions(json: String): List<SessionEntity> {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IllegalArgumentException("la racine doit être un objet JSON")
        val version = root.asJsonObject.get("version")
            ?.takeIf { it.isJsonPrimitive }
            ?.asInt
            ?: throw IllegalArgumentException("champ \"version\" manquant ou invalide")
        if (version != EXPORT_VERSION) {
            throw IllegalArgumentException("version $version non supportée (attendu : $EXPORT_VERSION)")
        }
        val sessionsElement = root.asJsonObject.get("sessions")
            ?: throw IllegalArgumentException("champ \"sessions\" manquant")
        if (sessionsElement.isJsonNull) throw IllegalArgumentException("champ \"sessions\" est null")
        val sessions = gson.fromJson(sessionsElement, Array<SessionEntity>::class.java)
        return sessions.map { raw ->
            val session = requireNotNull(raw) { "session nulle dans le tableau" }
            require(session.startTimeMillis > 0) { "session avec startTimeMillis invalide" }
            require(session.endTimeMillis >= session.startTimeMillis) {
                "session avec endTimeMillis avant startTimeMillis"
            }
            require(session.durationMillis >= 0) { "session avec durée négative" }
            require(!session.patternKey.isNullOrBlank()) { "session avec patternKey vide" }
            // Id regénérés par la base ; le jour calendaire est recalculé à partir du début.
            session.copy(
                id = 0,
                dateEpochDay = epochDayOf(session.startTimeMillis)
            )
        }
    }

    /** 30 jours calendaires, jours sans session remplis à zéro (pour le graphique). */
    private fun buildDailyHistory(rows: List<DailyUsageRow>): List<DailyStat> {
        val byDay = rows.associateBy { it.dateEpochDay }
        return (0L until LAST30_DAYS).map { offset ->
            val day = historyFromEpochDay + offset
            val row = byDay[day]
            DailyStat(
                dateEpochDay = day,
                durationMillis = row?.durationMillis ?: 0,
                sessionCount = row?.sessionCount ?: 0
            )
        }
    }

    private companion object {
        const val EXPORT_VERSION = 1
        const val LAST7_DAYS = 7L
        const val LAST30_DAYS = 30L
    }
}

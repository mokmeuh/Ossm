package com.ossm.remote.data.db

import androidx.room.Room
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert then observe sessions sorted by start descending`() = runTest {
        dao.insert(session(startTime = 1_000))
        dao.insert(session(startTime = 3_000))
        dao.insert(session(startTime = 2_000))

        val all = dao.observeSessions().first()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), all.map { it.startTimeMillis })
    }

    @Test
    fun `delete all empties the table`() = runTest {
        dao.insert(session(startTime = 1_000))
        dao.insert(session(startTime = 2_000))

        dao.deleteAll()

        assertTrue(dao.observeSessions().first().isEmpty())
    }

    @Test
    fun `aggregates compute counts sums averages and speed percent`() = runTest {
        dao.insert(session(startTime = 1_000, duration = 10_000, avgSpeed = 50f, maxSpeed = 80f))
        dao.insert(session(startTime = 2_000, duration = 20_000, avgSpeed = 70f, maxSpeed = 90f))

        val agg = dao.observeAggregates().first()
        assertEquals(2, agg.sessionCount)
        assertEquals(30_000L, agg.totalDurationMillis)
        assertEquals(15_000L, agg.avgDurationMillis)
        assertEquals(20_000L, agg.maxDurationMillis)
        assertEquals(60f, agg.avgSpeedPercent, 0.01f)
        assertEquals(90f, agg.maxSpeedPercent, 0.01f)
    }

    @Test
    fun `empty table yields zero aggregates`() = runTest {
        val agg = dao.observeAggregates().first()
        assertEquals(0, agg.sessionCount)
        assertEquals(0L, agg.totalDurationMillis)
        assertEquals(0L, agg.avgDurationMillis)
        assertEquals(0L, agg.maxDurationMillis)
        assertEquals(0f, agg.avgSpeedPercent, 0f)
        assertEquals(0f, agg.maxSpeedPercent, 0f)
    }

    @Test
    fun `duration window splits today 7 days and 30 days`() = runTest {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val day = 86_400_000L
        dao.insert(session(startTime = todayStart + 1_000, duration = 10_000))
        dao.insert(session(startTime = todayStart - day + 1_000, duration = 20_000))
        dao.insert(session(startTime = todayStart - 20 * day + 1_000, duration = 30_000))
        dao.insert(session(startTime = todayStart - 40 * day + 1_000, duration = 40_000))

        val window = dao.observeDurationWindow(
            sinceToday = todayStart,
            sinceLast7 = todayStart - 6 * day,
            sinceLast30 = todayStart - 29 * day
        ).first()
        assertEquals(10_000L, window.todayDurationMillis)
        assertEquals(30_000L, window.last7DaysDurationMillis)
        assertEquals(60_000L, window.last30DaysDurationMillis)
    }

    @Test
    fun `per pattern rows group by pattern key ordered by total duration`() = runTest {
        dao.insert(session(startTime = 1_000, duration = 10_000, patternKey = "a", patternName = "A"))
        dao.insert(session(startTime = 2_000, duration = 20_000, patternKey = "b", patternName = "B"))
        dao.insert(session(startTime = 3_000, duration = 5_000, patternKey = "a", patternName = "A"))

        val rows = dao.observePerPattern().first()
        assertEquals(2, rows.size)
        assertEquals(listOf("b", "a"), rows.map { it.patternKey })
        assertEquals(20_000L, rows[0].totalDurationMillis)
        assertEquals(2, rows[1].sessionCount)
        assertEquals("A", rows[1].patternName)
    }

    @Test
    fun `daily history groups by calendar day ascending`() = runTest {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val day = 86_400_000L
        val todayDay = LocalDate.now(zone).toEpochDay()
        dao.insert(session(startTime = todayStart + 1_000, duration = 10_000))
        dao.insert(session(startTime = todayStart + 2_000, duration = 5_000))
        dao.insert(session(startTime = todayStart - day + 1_000, duration = 7_000))

        val rows = dao.observeDailyHistory(fromEpochDay = todayDay - 1).first()
        assertEquals(2, rows.size)
        assertEquals(todayDay - 1, rows[0].dateEpochDay)
        assertEquals(7_000L, rows[0].durationMillis)
        assertEquals(1, rows[0].sessionCount)
        assertEquals(todayDay, rows[1].dateEpochDay)
        assertEquals(15_000L, rows[1].durationMillis)
        assertEquals(2, rows[1].sessionCount)
    }

    @Test
    fun `replaceAll swaps the whole table atomically`() = runTest {
        dao.insert(session(startTime = 1_000, duration = 10_000))
        dao.insert(session(startTime = 2_000, duration = 20_000))

        dao.replaceAll(listOf(session(startTime = 3_000, duration = 5_000)))

        val all = dao.observeSessions().first()
        assertEquals(1, all.size)
        assertEquals(5_000L, all[0].durationMillis)
        assertEquals(3_000L, all[0].startTimeMillis)
    }

    private fun session(
        startTime: Long,
        duration: Long = 10_000,
        patternKey: String = "simpleStroke",
        patternName: String = "Simple Stroke",
        avgSpeed: Float = 50f,
        maxSpeed: Float = 80f
    ) = SessionEntity(
        startTimeMillis = startTime,
        endTimeMillis = startTime + duration,
        durationMillis = duration,
        patternKey = patternKey,
        patternName = patternName,
        avgSpeedPercent = avgSpeed,
        maxSpeedPercent = maxSpeed,
        depthMin = 0.1f,
        depthMax = 0.9f,
        avgSensationPercent = 60f,
        complete = true,
        dateEpochDay = epochDayOf(startTime)
    )
}

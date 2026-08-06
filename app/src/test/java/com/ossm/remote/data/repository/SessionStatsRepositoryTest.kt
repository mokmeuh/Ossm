package com.ossm.remote.data.repository

import androidx.room.Room
import com.ossm.remote.data.db.AppDatabase
import com.ossm.remote.data.db.SessionDao
import com.ossm.remote.data.db.SessionEntity
import com.ossm.remote.data.db.epochDayOf
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SessionStatsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SessionDao
    private lateinit var repo: SessionStatsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.sessionDao()
        repo = SessionStatsRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `empty snapshot has neutral values`() = runTest {
        val snap = repo.snapshot.first()
        assertEquals(0L, snap.totalDurationMillis)
        assertEquals(0L, snap.todayDurationMillis)
        assertEquals(0L, snap.last7DaysDurationMillis)
        assertEquals(0L, snap.last30DaysDurationMillis)
        assertEquals(0, snap.sessionCount)
        assertEquals(0L, snap.avgSessionDurationMillis)
        assertEquals(0L, snap.maxSessionDurationMillis)
        assertTrue(snap.perPattern.isEmpty())
        assertEquals(0f, snap.avgSpeedPercent, 0f)
        assertEquals(0f, snap.maxSpeedPercent, 0f)
        assertEquals(30, snap.dailyHistory.size)
        assertTrue(snap.dailyHistory.all { it.durationMillis == 0L && it.sessionCount == 0 })
    }

    @Test
    fun `aggregation across windows patterns and speed`() = runTest {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val day = 86_400_000L
        dao.insert(session(startTime = todayStart + 1_000, duration = 10_000, patternKey = "a", patternName = "A", avgSpeed = 50f, maxSpeed = 80f))
        dao.insert(session(startTime = todayStart - day + 1_000, duration = 20_000, patternKey = "b", patternName = "B", avgSpeed = 70f, maxSpeed = 90f))
        dao.insert(session(startTime = todayStart - 20 * day + 1_000, duration = 30_000, patternKey = "a", patternName = "A", avgSpeed = 30f, maxSpeed = 60f))
        dao.insert(session(startTime = todayStart - 40 * day + 1_000, duration = 40_000, patternKey = "b", patternName = "B", avgSpeed = 100f, maxSpeed = 100f))

        val snap = repo.snapshot.first()
        assertEquals(100_000L, snap.totalDurationMillis)
        assertEquals(10_000L, snap.todayDurationMillis)
        assertEquals(30_000L, snap.last7DaysDurationMillis)
        assertEquals(60_000L, snap.last30DaysDurationMillis)
        assertEquals(4, snap.sessionCount)
        assertEquals(25_000L, snap.avgSessionDurationMillis)
        assertEquals(40_000L, snap.maxSessionDurationMillis)
        assertEquals(listOf("b", "a"), snap.perPattern.map { it.patternKey })
        assertEquals(60_000L, snap.perPattern[0].totalDurationMillis)
        assertEquals(2, snap.perPattern[1].sessionCount)
        assertEquals(62.5f, snap.avgSpeedPercent, 0.01f)
        assertEquals(100f, snap.maxSpeedPercent, 0.01f)

        val byDay = snap.dailyHistory.associateBy { it.dateEpochDay }
        val todayDay = LocalDate.now(zone).toEpochDay()
        assertEquals(10_000L, byDay[todayDay]?.durationMillis)
        assertEquals(20_000L, byDay[todayDay - 1]?.durationMillis)
        assertEquals(30_000L, byDay[todayDay - 20]?.durationMillis)
        assertNull(byDay[todayDay - 40])
    }

    @Test
    fun `export then import round trips identical data`() = runTest {
        dao.insert(session(startTime = 1_000, duration = 10_000, patternKey = "a", patternName = "A"))
        dao.insert(session(startTime = 2_000, duration = 20_000, patternKey = "b", patternName = "B"))
        dao.insert(session(startTime = 3_000, duration = 30_000, patternKey = "a", patternName = "A"))
        val before = repo.snapshot.first()

        val json = repo.exportSessions()
        assertTrue(json.contains("\"version\":1"))
        assertTrue(json.contains("exportedAtMillis"))

        repo.resetAll()
        assertEquals(0, repo.snapshot.first().sessionCount)

        val result = repo.importSessions(json)
        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow())

        val after = repo.snapshot.first()
        assertEquals(before.sessionCount, after.sessionCount)
        assertEquals(before.totalDurationMillis, after.totalDurationMillis)
        assertEquals(before.avgSessionDurationMillis, after.avgSessionDurationMillis)
        assertEquals(before.maxSessionDurationMillis, after.maxSessionDurationMillis)
        assertEquals(before.avgSpeedPercent, after.avgSpeedPercent, 0.01f)
        assertEquals(before.maxSpeedPercent, after.maxSpeedPercent, 0.01f)
        assertEquals(before.perPattern, after.perPattern)
        assertEquals(before.dailyHistory, after.dailyHistory)
    }

    @Test
    fun `import rejects invalid json without touching the table`() = runTest {
        dao.insert(session(startTime = 1_000, duration = 10_000))
        val invalidJsons = listOf(
            "not json",
            "[]",
            "{}",
            """{"version":2,"sessions":[]}""",
            """{"version":"abc","sessions":[]}""",
            """{"version":1}""",
            """{"version":1,"sessions":null}""",
            """{"version":1,"sessions":[{}]}""",
            """{"version":1,"sessions":[{"startTimeMillis":1000,"endTimeMillis":500,"durationMillis":10,"patternKey":"a"}]}""",
            """{"version":1,"sessions":[{"startTimeMillis":0,"endTimeMillis":1000,"durationMillis":10,"patternKey":"a"}]}"""
        )
        for (bad in invalidJsons) {
            val result = repo.importSessions(bad)
            assertTrue("should fail for: $bad", result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.isNotBlank() == true)
        }
        assertEquals(1, repo.snapshot.first().sessionCount)
    }

    @Test
    fun `import replaces all existing sessions`() = runTest {
        dao.insert(session(startTime = 1_000, duration = 10_000, patternKey = "a", patternName = "A"))
        dao.insert(session(startTime = 2_000, duration = 20_000, patternKey = "b", patternName = "B"))
        val json = repo.exportSessions()
        dao.insert(session(startTime = 3_000, duration = 30_000, patternKey = "c", patternName = "C"))
        assertEquals(3, repo.snapshot.first().sessionCount)

        val result = repo.importSessions(json)
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow())

        val snap = repo.snapshot.first()
        assertEquals(2, snap.sessionCount)
        assertEquals(30_000L, snap.totalDurationMillis)
        assertEquals(listOf("b", "a"), snap.perPattern.map { it.patternKey })
    }

    @Test
    fun `import recomputes dateEpochDay from start time`() = runTest {
        val now = System.currentTimeMillis()
        val json = """{"version":1,"exportedAtMillis":0,"sessions":[
            {"id":42,"startTimeMillis":$now,"endTimeMillis":${now + 10_000},"durationMillis":10000,
             "patternKey":"simpleStroke","patternName":"Simple Stroke","avgSpeedPercent":50.0,
             "maxSpeedPercent":80.0,"depthMin":0.1,"depthMax":0.9,"avgSensationPercent":60.0,
             "complete":true,"dateEpochDay":999999}
        ]}"""

        val result = repo.importSessions(json)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())

        val todayDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val snap = repo.snapshot.first()
        assertEquals(10_000L, snap.todayDurationMillis)
        assertEquals(1, snap.dailyHistory.count { it.dateEpochDay == todayDay && it.sessionCount == 1 })
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

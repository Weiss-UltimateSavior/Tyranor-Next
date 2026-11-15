package com.tyranor.next.core.game.playtime

import com.tyranor.next.core.engine.EngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlaytimeLogicTest {
    @Test
    fun resumedActivitiesInOneSessionShareOneClock() {
        val clock = PlaytimeSessionClock()
        val first = Any()
        val second = Any()

        clock.resume(first, 100)
        clock.resume(second, 200)
        clock.resume(first, 300) // Duplicate lifecycle callback does not restart the interval.
        assertEquals(900L, clock.snapshot(1_000))

        clock.pause(first, 1_100) // The other resumed Activity keeps the session active.
        assertEquals(1_100L, clock.snapshot(1_200))
        clock.pause(second, 1_300)
        assertEquals(1_200L, clock.snapshot(10_000))
        assertFalse(clock.isRunning())
    }

    @Test
    fun pausedIntervalsIncludingScreenOffAreNotCounted() {
        val beforeScreenOff = PlaytimeSessionClock()
        val activity = Any()
        beforeScreenOff.resume(activity, 10_000)
        beforeScreenOff.pause(activity, 15_000)
        assertEquals(5_000L, beforeScreenOff.snapshot(60_000))

        val afterUnlock = PlaytimeSessionClock()
        afterUnlock.resume(activity, 60_000)
        assertEquals(2_000L, afterUnlock.snapshot(62_000))
    }

    @Test
    fun repeatedCheckpointsMergeByMaximumAndLastSessionUsesEarliestWriterStart() {
        val snapshots = listOf(
            record("old-root", "session-old", 1_000, startedAt = 100),
            record("old-video", "session-old", 250, startedAt = 200),
            record("old-video", "session-old", 100, startedAt = 200), // A stale snapshot is not added twice.
            record("new-root", "session-new", 500, startedAt = 150),
        )

        val stats = PlaytimeRecordLogic.statsFor("game://one", snapshots)

        assertEquals(1_750L, stats.totalMillis)
        assertEquals(500L, stats.lastSessionMillis)
    }

    @Test
    fun onlyRequestedBuiltInEngineTypesAreSupported() {
        assertTrue(GamePlaytimeRepository.supports(EngineType.KIRIKIRI))
        assertTrue(GamePlaytimeRepository.supports(EngineType.XSYS4))
        assertFalse(GamePlaytimeRepository.supports(EngineType.PC))
        assertFalse(GamePlaytimeRepository.supports(EngineType.RENPY))
        assertFalse(GamePlaytimeRepository.supports(EngineType.RUFFLE))
        assertFalse(GamePlaytimeRepository.supports(EngineType.RUNTIME_MODULE))
    }

    @Test
    fun sessionOrderSurvivesClockRollbackAndLongOverflowBoundary() {
        assertEquals(101L, nextSessionOrder(currentTimeMillis = 50, previousOrderMillis = 100))
        assertEquals(Long.MAX_VALUE, nextSessionOrder(currentTimeMillis = 1, previousOrderMillis = Long.MAX_VALUE))
        assertEquals(5L, nextSessionOrder(currentTimeMillis = 5, previousOrderMillis = -1))
    }

    private fun record(
        recordId: String,
        sessionId: String,
        duration: Long,
        startedAt: Long,
    ) = PlaytimeRecordSnapshot(
        recordId = recordId,
        sessionId = sessionId,
        gameUri = "game://one",
        durationMillis = duration,
        startedAtMillis = startedAt,
        closed = false,
        processId = 1,
    )
}

package dev.instrum.ether.core.time

import dev.instrum.ether.testing.FakeTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTimerTest {

    @Test
    fun firstFrameEstablishesBaselineWithZeroDelta() {
        val timer = FrameTimer(FakeTimeSource(1_000L))
        val timing = timer.beginFrame()
        assertEquals(0L, timing.deltaNanos)
        assertEquals(0L, timing.elapsedNanos)
        assertEquals(0L, timing.frameIndex)
    }

    @Test
    fun subsequentFramesReportElapsedDelta() {
        val clock = FakeTimeSource()
        val timer = FrameTimer(clock)
        timer.beginFrame()
        clock.nanos = 16_000_000L
        val second = timer.beginFrame()
        assertEquals(16_000_000L, second.deltaNanos)
        assertEquals(16_000_000L, second.elapsedNanos)
        assertEquals(1L, second.frameIndex)
    }

    @Test
    fun elapsedAccumulatesAcrossFrames() {
        val clock = FakeTimeSource()
        val timer = FrameTimer(clock)
        timer.beginFrame()
        clock.nanos = 10L
        timer.beginFrame()
        clock.nanos = 25L
        val third = timer.beginFrame()
        assertEquals(15L, third.deltaNanos)
        assertEquals(25L, third.elapsedNanos)
        assertEquals(3L, timer.frames)
    }

    @Test
    fun deltaSecondsAndFrameScaleAreDerivedCorrectly() {
        val clock = FakeTimeSource()
        val timer = FrameTimer(clock)
        timer.beginFrame()
        clock.nanos = 16_666_666L
        val timing = timer.beginFrame()
        assertEquals(1f / 60f, timing.deltaSeconds, 1e-4f)
        assertEquals(1f, timing.deltaFrames60Hz, 1e-4f)
    }

    @Test
    fun resetRestartsTheBaseline() {
        val clock = FakeTimeSource()
        val timer = FrameTimer(clock)
        timer.beginFrame()
        clock.nanos = 100L
        timer.beginFrame()
        timer.reset()
        val afterReset = timer.beginFrame()
        assertEquals(0L, afterReset.deltaNanos)
        assertEquals(0L, afterReset.frameIndex)
        assertEquals(0L, afterReset.elapsedNanos)
    }

    @Test
    fun monotonicSourceAdvancesBetweenReadings() {
        val first = MonotonicTimeSource.nowNanos()
        val second = MonotonicTimeSource.nowNanos()
        assertTrue(second >= first, "monotonic clock must not go backwards")
    }
}

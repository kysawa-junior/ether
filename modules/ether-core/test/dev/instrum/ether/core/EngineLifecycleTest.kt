package dev.instrum.ether.core

import dev.instrum.ether.core.config.EngineConfig
import dev.instrum.ether.core.error.EngineInitializationException
import dev.instrum.ether.core.error.InvalidEngineStateException
import dev.instrum.ether.core.event.EventBus
import dev.instrum.ether.core.log.LogLevel
import dev.instrum.ether.core.log.NoOpLogSink
import dev.instrum.ether.core.log.SinkLogger
import dev.instrum.ether.core.time.FrameTiming
import dev.instrum.ether.core.time.FrameTimer
import dev.instrum.ether.core.time.TimeSource
import dev.instrum.ether.testing.FakeTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineLifecycleTest {

    private class RecordingEngine(
        config: EngineConfig = EngineConfig(),
        timeSource: TimeSource = FakeTimeSource(),
    ) : Engine(
        config = config,
        events = EventBus(),
        logger = SinkLogger(NoOpLogSink, "test", LogLevel.Off),
        timer = FrameTimer(timeSource),
    ) {
        val calls = mutableListOf<String>()

        override fun onInitialize() {
            calls += "initialize"
        }

        override fun onUpdate(timing: FrameTiming) {
            calls += "update:${timing.frameIndex}"
        }

        override fun onShutdown() {
            calls += "shutdown"
        }
    }

    @Test
    fun engineStartsInCreatedState() {
        val engine = Engine.create(sink = NoOpLogSink)
        assertEquals(EngineState.Created, engine.currentState)
    }

    @Test
    fun initializeMovesEngineToRunning() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        assertEquals(EngineState.Running, engine.currentState)
        engine.shutdown()
    }

    @Test
    fun lifecycleHooksAreInvokedInOrder() {
        val engine = RecordingEngine()
        engine.initialize()
        engine.update()
        engine.shutdown()
        assertEquals(listOf("initialize", "update:0", "shutdown"), engine.calls)
    }

    @Test
    fun updateBeforeInitializeIsRejected() {
        val engine = Engine.create(sink = NoOpLogSink)
        assertFailsWith<InvalidEngineStateException> { engine.update() }
    }

    @Test
    fun initializeTwiceIsRejected() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        assertFailsWith<InvalidEngineStateException> { engine.initialize() }
        engine.shutdown()
    }

    @Test
    fun shutdownMovesEngineToStopped() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        engine.shutdown()
        assertEquals(EngineState.Stopped, engine.currentState)
    }

    @Test
    fun doubleShutdownIsRejected() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        engine.shutdown()
        assertFailsWith<InvalidEngineStateException> { engine.shutdown() }
    }

    @Test
    fun updateAfterShutdownIsRejected() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        engine.shutdown()
        assertFailsWith<InvalidEngineStateException> { engine.update() }
    }

    @Test
    fun pauseAndResumeCycleThroughStates() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        engine.pause()
        assertEquals(EngineState.Paused, engine.currentState)
        engine.resume()
        assertEquals(EngineState.Running, engine.currentState)
        engine.shutdown()
    }

    @Test
    fun updateWhilePausedIsRejected() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        engine.pause()
        assertFailsWith<InvalidEngineStateException> { engine.update() }
        engine.resume()
        engine.shutdown()
    }

    @Test
    fun frameTimingAdvancesWithEachUpdate() {
        val clock = FakeTimeSource()
        val engine = RecordingEngine(timeSource = clock)
        engine.initialize()
        clock.nanos = 1_000_000L
        val first = engine.update()
        clock.nanos = 3_000_000L
        val second = engine.update()
        assertEquals(0L, first.frameIndex)
        assertEquals(1L, second.frameIndex)
        assertEquals(2_000_000L, second.deltaNanos)
    }

    @Test
    fun firstFrameReportsZeroDelta() {
        val clock = FakeTimeSource(nanos = 5_000_000L)
        val engine = RecordingEngine(timeSource = clock)
        engine.initialize()
        assertEquals(0L, engine.update().deltaNanos)
    }

    @Test
    fun excessiveFrameDeltaIsClamped() {
        val clock = FakeTimeSource()
        val engine = Engine.create(
            config = EngineConfig(maximumDeltaSeconds = 0.1f),
            sink = NoOpLogSink,
            timeSource = clock,
        )
        engine.initialize()
        // The first frame establishes the time baseline and reports a zero delta.
        engine.update()
        clock.nanos = 5_000_000_000L
        val timing = engine.update()
        assertEquals(100_000_000L, timing.deltaNanos)
        engine.shutdown()
    }

    @Test
    fun configRejectsNegativeFrameRate() {
        assertFailsWith<IllegalArgumentException> { EngineConfig(targetFramesPerSecond = -1) }
    }

    @Test
    fun configRejectsNonPositiveMaximumDelta() {
        assertFailsWith<IllegalArgumentException> { EngineConfig(maximumDeltaSeconds = 0f) }
    }

    @Test
    fun targetFrameIntervalIsDerivedFromFrameRate() {
        assertEquals(16_666_666L, EngineConfig(targetFramesPerSecond = 60).targetFrameIntervalNanos)
        assertEquals(null, EngineConfig(targetFramesPerSecond = 0).targetFrameIntervalNanos)
    }

    @Test
    fun failedInitializationMovesEngineToFailedAndWrapsCause() {
        val failure = IllegalStateException("no adapter")
        val engine = object : Engine(
            config = EngineConfig(),
            events = EventBus(),
            logger = SinkLogger(NoOpLogSink, "test", LogLevel.Off),
            timer = FrameTimer(FakeTimeSource()),
        ) {
            override fun onInitialize() {
                throw failure
            }
        }

        val thrown = assertFailsWith<EngineInitializationException> { engine.initialize() }
        assertEquals(failure, thrown.cause)
        assertEquals(EngineState.Failed, engine.currentState)
        assertFailsWith<InvalidEngineStateException> { engine.update() }
    }

    @Test
    fun engineIsUsableAfterSuccessfulLifecycle() {
        val engine = Engine.create(sink = NoOpLogSink)
        engine.initialize()
        repeat(3) { engine.update() }
        assertTrue(engine.lastFrameTiming.frameIndex == 2L)
        engine.shutdown()
    }
}

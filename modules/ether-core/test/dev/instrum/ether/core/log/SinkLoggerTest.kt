package dev.instrum.ether.core.log

import dev.instrum.ether.testing.RecordingLogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SinkLoggerTest {

    @Test
    fun recordsAtOrAboveLevelAreForwarded() {
        val sink = RecordingLogSink()
        val logger = SinkLogger(sink, "test", LogLevel.Info, clock = { 42L })

        logger.debug("ignored")
        logger.info("kept")
        logger.warn("also kept")
        logger.error("error kept")

        assertEquals(listOf("kept", "also kept", "error kept"), sink.all.map { it.message })
    }

    @Test
    fun offLevelSuppressesEverything() {
        val sink = RecordingLogSink()
        val logger = SinkLogger(sink, "test", LogLevel.Off)
        logger.error("nothing")
        assertTrue(sink.all.isEmpty())
    }

    @Test
    fun recordCarriesTagLevelTimestampAndMessage() {
        val sink = RecordingLogSink()
        val logger = SinkLogger(sink, "renderer", LogLevel.Debug, clock = { 1234L })

        logger.info("hello")

        val record = assertNotNull(sink.all.singleOrNull())
        assertEquals("renderer", record.tag)
        assertEquals(LogLevel.Info, record.level)
        assertEquals(1234L, record.timestampMillis)
        assertEquals("hello", record.message)
    }

    @Test
    fun throwableIsAttachedToRecord() {
        val sink = RecordingLogSink()
        val logger = SinkLogger(sink, "test", LogLevel.Debug)
        val failure = IllegalStateException("boom")

        logger.error("failed", failure)

        assertEquals(failure, sink.all.single().throwable)
    }

    @Test
    fun loggerWithTagReturnsLoggerSharingTheSameSink() {
        val sink = RecordingLogSink()
        val logger = SinkLogger(sink, "root", LogLevel.Debug)
        val child = logger.withTag("child")

        child.info("from child")

        assertEquals("child", sink.all.single().tag)
    }

    @Test
    fun logLevelOrderingIsMonotonic() {
        val ordered = listOf(
            LogLevel.Trace,
            LogLevel.Debug,
            LogLevel.Info,
            LogLevel.Warn,
            LogLevel.Error,
            LogLevel.Off,
        )
        ordered.zipWithNext().forEach { (lower, higher) ->
            assertTrue(lower.ordinal < higher.ordinal, "$lower should be below $higher")
        }
    }
}

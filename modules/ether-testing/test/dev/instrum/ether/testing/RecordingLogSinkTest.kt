package dev.instrum.ether.testing

import dev.instrum.ether.core.log.LogLevel
import dev.instrum.ether.core.log.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingLogSinkTest {

    private fun record(level: LogLevel, message: String): LogRecord =
        LogRecord(level, "test", message, null, 0L)

    @Test
    fun recordsAreCapturedInOrderAndFilteredByLevel() {
        val sink = RecordingLogSink()
        sink.write(record(LogLevel.Info, "first"))
        sink.write(record(LogLevel.Error, "second"))
        sink.write(record(LogLevel.Info, "third"))

        assertEquals(listOf("first", "second", "third"), sink.all.map { it.message })
        assertEquals(listOf("first", "third"), sink.messagesAt(LogLevel.Info))
        assertEquals(listOf("second"), sink.messagesAt(LogLevel.Error))
        assertEquals(3, sink.size)
    }

    @Test
    fun containsFindsMessageFragments() {
        val sink = RecordingLogSink()
        sink.write(record(LogLevel.Warn, "shader compilation slow"))

        assertTrue(sink.contains(LogLevel.Warn, "compilation"))
        assertFalse(sink.contains(LogLevel.Error, "compilation"))
        assertFalse(sink.contains(LogLevel.Warn, "missing"))
    }

    @Test
    fun clearRemovesAllRecords() {
        val sink = RecordingLogSink()
        sink.write(record(LogLevel.Info, "message"))
        sink.clear()

        assertEquals(0, sink.size)
        assertTrue(sink.all.isEmpty())
    }
}

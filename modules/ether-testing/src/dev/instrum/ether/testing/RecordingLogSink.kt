package dev.instrum.ether.testing

import dev.instrum.ether.core.log.LogLevel
import dev.instrum.ether.core.log.LogRecord
import dev.instrum.ether.core.log.LogSink

/**
 * A [LogSink] that records every message it receives, for assertions in tests.
 *
 * Instances are not synchronized; use one per test thread.
 */
public class RecordingLogSink : LogSink {
    private val records = mutableListOf<LogRecord>()

    /** Every record received so far, in order. */
    public val all: List<LogRecord>
        get() = records.toList()

    /** The number of records received so far. */
    public val size: Int
        get() = records.size

    override fun write(record: LogRecord) {
        records += record
    }

    /** The records received at [level]. */
    public fun recordsAt(level: LogLevel): List<LogRecord> = records.filter { it.level == level }

    /** The messages received at [level]. */
    public fun messagesAt(level: LogLevel): List<String> = recordsAt(level).map { it.message }

    /** Whether any record at [level] contains [fragment] in its message. */
    public fun contains(level: LogLevel, fragment: String): Boolean =
        recordsAt(level).any { it.message.contains(fragment) }

    /** Removes all recorded messages. */
    public fun clear() {
        records.clear()
    }
}


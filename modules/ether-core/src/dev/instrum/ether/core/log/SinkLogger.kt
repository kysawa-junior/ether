package dev.instrum.ether.core.log

import dev.instrum.ether.core.time.currentTimeMillis

/**
 * A [Logger] that forwards records to a [LogSink] after filtering by level.
 *
 * @param sink the destination for accepted records.
 * @param tag the subsystem tag for this logger.
 * @param level the minimum level to emit.
 */
public class SinkLogger(
    private val sink: LogSink,
    override val tag: String,
    override val level: LogLevel = LogLevel.Info,
    private val clock: () -> Long = ::currentTimeMillis,
) : Logger {

    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        if (!isEnabled(level)) return
        sink.write(LogRecord(level, tag, message, throwable, clock()))
    }

    /** Returns a logger with the same sink and level but a different [tag]. */
    public fun withTag(tag: String): SinkLogger = SinkLogger(sink, tag, level, clock)

    /** Returns a logger with the same sink and tag but a different [level]. */
    public fun withLevel(level: LogLevel): SinkLogger = SinkLogger(sink, tag, level, clock)
}

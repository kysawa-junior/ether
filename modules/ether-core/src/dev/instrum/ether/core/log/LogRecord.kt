package dev.instrum.ether.core.log

/**
 * A single log message produced by the engine.
 *
 * @property level the severity of the message.
 * @property tag the subsystem that produced the message.
 * @property message the human-readable message body.
 * @property throwable an optional error associated with the message.
 * @property timestampMillis the wall-clock time the message was created, in milliseconds since the
 *   Unix epoch.
 */
public data class LogRecord(
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
    public val throwable: Throwable? = null,
    public val timestampMillis: Long,
)

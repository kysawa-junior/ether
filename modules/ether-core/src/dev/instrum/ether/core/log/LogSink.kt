package dev.instrum.ether.core.log

/**
 * The sink that receives formatted log records.
 *
 * Implementations are responsible for their own thread safety. The default console sinks are not
 * synchronized; a sink shared across threads must provide synchronization.
 */
public fun interface LogSink {
    /**
     * Writes [record] to the underlying destination.
     *
     * Implementations should not throw; if formatting fails they should degrade to writing the raw
     * message rather than propagate the failure into engine code.
     */
    public fun write(record: LogRecord)
}

package dev.instrum.ether.core.log

/**
 * The engine's logging facade.
 *
 * A logger is bound to a [tag] that identifies the emitting subsystem. Filtering by [LogLevel] is
 * handled by the logger itself so that disabled levels cost only a comparison.
 *
 * Implementations are responsible for their own thread safety; [SinkLogger] itself is not
 * synchronized and must be confined to one thread unless the sink provides synchronization.
 */
public interface Logger {
    /** The subsystem tag associated with this logger. */
    public val tag: String

    /** The minimum level that this logger emits. */
    public val level: LogLevel

    /** Whether a message at [candidate] level would be emitted. */
    public fun isEnabled(candidate: LogLevel): Boolean = candidate.isEnabledFor(level)

    /** Logs a message at [level]. */
    public fun log(level: LogLevel, message: String, throwable: Throwable? = null)

    /** Logs a message at [LogLevel.Trace]. */
    public fun trace(message: String) {
        log(LogLevel.Trace, message)
    }

    /** Logs a message at [LogLevel.Debug]. */
    public fun debug(message: String) {
        log(LogLevel.Debug, message)
    }

    /** Logs a message at [LogLevel.Info]. */
    public fun info(message: String) {
        log(LogLevel.Info, message)
    }

    /** Logs a message at [LogLevel.Warn]. */
    public fun warn(message: String) {
        log(LogLevel.Warn, message)
    }

    /** Logs a message at [LogLevel.Error]. */
    public fun error(message: String, throwable: Throwable? = null) {
        log(LogLevel.Error, message, throwable)
    }
}

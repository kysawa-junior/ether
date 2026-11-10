package dev.instrum.ether.core.log

/**
 * Severity levels for engine log messages, ordered from most to least verbose.
 */
public enum class LogLevel {
    /** Fine-grained diagnostics, typically disabled in production. */
    Trace,

    /** Debugging information useful during development. */
    Debug,

    /** Informational messages about normal engine operation. */
    Info,

    /** Messages about recoverable problems or degraded behaviour. */
    Warn,

    /** Messages about failures that prevent normal operation. */
    Error,

    /** A pseudo-level that disables all logging. */
    Off,
    ;

    /**
     * Whether a message at this level should be emitted when the configured threshold is [threshold].
     *
     * A message is emitted when its level is at least as severe as the threshold.
     */
    public fun isEnabledFor(threshold: LogLevel): Boolean =
        this != Off && threshold != Off && this.ordinal >= threshold.ordinal
}

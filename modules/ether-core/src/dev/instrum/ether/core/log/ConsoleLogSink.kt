package dev.instrum.ether.core.log

/**
 * Writes [record] to the platform's standard output stream or browser console.
 *
 * Platform implementations live in the platform-specific source sets.
 */
internal expect fun writeToConsole(record: LogRecord)

/**
 * A [LogSink] that writes to the platform's standard output.
 *
 * On JVM, Android and native targets this writes to the process standard output. On browser
 * targets it writes to the JavaScript console.
 */
public object ConsoleLogSink : LogSink {
    override fun write(record: LogRecord) {
        writeToConsole(record)
    }
}

/**
 * A [LogSink] that discards every record. Useful in tests and in headless tools.
 */
public object NoOpLogSink : LogSink {
    override fun write(record: LogRecord): Unit = Unit
}

/**
 * The platform's default log sink.
 */
internal expect fun defaultLogSink(): LogSink

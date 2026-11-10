package dev.instrum.ether.core.log

/**
 * The platform's default log sink, writing to the standard output stream.
 */
internal actual fun defaultLogSink(): LogSink = ConsoleLogSink

/**
 * Writes [record] to the standard output stream.
 */
internal actual fun writeToConsole(record: LogRecord) {
    val suffix = record.throwable?.let { "\n${it.stackTraceToString()}" }.orEmpty()
    println("[${record.level}] [${record.tag}] ${record.message}$suffix")
}

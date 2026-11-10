@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.instrum.ether.core.log

/**
 * Writes [text] to the browser console via the global `console.log` function.
 */
private fun consoleLog(text: String): Unit = js("console.log(text)")

/**
 * Writes [text] and [detail] to the browser console via the global `console.error` function.
 */
private fun consoleError(text: String, detail: String): Unit = js("console.error(text, detail)")

/**
 * The platform's default log sink, writing to the browser console.
 */
internal actual fun defaultLogSink(): LogSink = ConsoleLogSink

/**
 * Writes [record] to the browser console.
 */
internal actual fun writeToConsole(record: LogRecord) {
    val text = "[${record.level}] [${record.tag}] ${record.message}"
    val throwableText = record.throwable?.stackTraceToString()
    if (throwableText == null) {
        consoleLog(text)
    } else {
        consoleError(text, throwableText)
    }
}

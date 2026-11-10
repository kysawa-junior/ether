package dev.instrum.ether.core.time

/**
 * A [TimeSource] backed by the platform's monotonic clock.
 *
 * The absolute epoch is unspecified; only differences between two readings are meaningful.
 *
 * The origin is captured once when this object is first initialized. Re-anchoring on every call
 * would make each reading approximately zero and destroy frame timing.
 */
public object MonotonicTimeSource : TimeSource {
    private val origin: kotlin.time.TimeMark = kotlin.time.TimeSource.Monotonic.markNow()

    override fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds
}

/**
 * The current wall-clock time in milliseconds since the Unix epoch.
 */
public fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

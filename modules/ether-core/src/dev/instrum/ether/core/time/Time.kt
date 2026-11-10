package dev.instrum.ether.core.time

/**
 * A source of monotonically increasing time values.
 *
 * The engine uses monotonic time for frame timing so that wall-clock adjustments (NTP sync,
 * suspend/resume) cannot produce negative or wildly large frame deltas.
 */
public fun interface TimeSource {
    /**
     * Returns the current time in nanoseconds.
     *
     * The value is only meaningful when compared against another value from the same source; the
     * absolute epoch is unspecified.
     */
    public fun nowNanos(): Long
}

/**
 * A snapshot of the timing state for a single engine frame.
 *
 * @property frameIndex the zero-based index of the frame being processed.
 * @property deltaNanos the time elapsed since the previous frame, in nanoseconds.
 * @property elapsedNanos the total time elapsed since the engine started, in nanoseconds.
 */
public data class FrameTiming(
    public val frameIndex: Long,
    public val deltaNanos: Long,
    public val elapsedNanos: Long,
) {
    /** The frame delta expressed in seconds. */
    public val deltaSeconds: Float
        get() = deltaNanos / NANOS_PER_SECOND

    /** The elapsed time expressed in seconds. */
    public val elapsedSeconds: Float
        get() = elapsedNanos / NANOS_PER_SECOND

    /** The frame delta expressed as a ratio to a 60 Hz frame, useful for scaling animation. */
    public val deltaFrames60Hz: Float
        get() = deltaNanos / NANOS_PER_60HZ_FRAME

    public companion object {
        /** The number of nanoseconds in one second. */
        public const val NANOS_PER_SECOND: Float = 1_000_000_000f

        /** The number of nanoseconds in a 60 Hz frame. */
        public const val NANOS_PER_60HZ_FRAME: Float = NANOS_PER_SECOND / 60f
    }
}

/**
 * Accumulates frame timing from a [TimeSource].
 *
 * The first call to [beginFrame] establishes the baseline and reports a zero delta, because no
 * frame has elapsed yet.
 */
public class FrameTimer(
    private val timeSource: TimeSource,
) {
    private var lastNanos: Long = 0L
    private var started: Boolean = false
    private var frameIndex: Long = 0L
    private var elapsedNanos: Long = 0L

    /** The number of frames measured so far. */
    public val frames: Long
        get() = frameIndex

    /** The total time measured so far, in nanoseconds. */
    public val totalNanos: Long
        get() = elapsedNanos

    /**
     * Advances the timer and returns the timing state for the new frame.
     */
    public fun beginFrame(): FrameTiming {
        val now = timeSource.nowNanos()
        if (!started) {
            started = true
            lastNanos = now
        }
        val delta = now - lastNanos
        lastNanos = now
        elapsedNanos += delta
        val timing = FrameTiming(frameIndex, delta, elapsedNanos)
        frameIndex++
        return timing
    }

    /** Resets the timer so that the next [beginFrame] reports a zero delta. */
    public fun reset() {
        started = false
        frameIndex = 0L
        elapsedNanos = 0L
        lastNanos = 0L
    }
}

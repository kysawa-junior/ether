package dev.instrum.ether.testing

import dev.instrum.ether.core.time.TimeSource

/**
 * A [TimeSource] whose value only changes when the test advances it.
 *
 * Instances are not synchronized; use one per test thread.
 */
public class FakeTimeSource(
    public var nanos: Long = 0L,
) : TimeSource {
    override fun nowNanos(): Long = nanos

    /** Moves the clock forward by [deltaNanos]. */
    public fun advance(deltaNanos: Long) {
        nanos += deltaNanos
    }
}

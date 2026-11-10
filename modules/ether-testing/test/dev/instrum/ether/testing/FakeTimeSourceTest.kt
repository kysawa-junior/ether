package dev.instrum.ether.testing

import kotlin.test.Test
import kotlin.test.assertEquals

class FakeTimeSourceTest {

    @Test
    fun startsAtConfiguredValue() {
        assertEquals(0L, FakeTimeSource().nowNanos())
        assertEquals(5_000L, FakeTimeSource(5_000L).nowNanos())
    }

    @Test
    fun advanceAccumulates() {
        val clock = FakeTimeSource()
        clock.advance(16_000_000L)
        clock.advance(500L)
        assertEquals(16_000_500L, clock.nowNanos())
    }
}

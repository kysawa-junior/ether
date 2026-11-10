package dev.instrum.ether.testing

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FloatAssertionsTest {

    @Test
    fun valuesWithinTolerancePass() {
        assertFloatEquals(1f, 1.000001f)
        assertFloatEquals(0f, 1e-6f, epsilon = 1e-5f)
    }

    @Test
    fun valuesOutsideToleranceThrow() {
        assertFailsWith<AssertionError> { assertFloatEquals(1f, 1.1f, epsilon = 1e-5f) }
    }

    @Test
    fun nanMatchesNanWhenIncluded() {
        assertFloatEqualsIncludingNaN(Float.NaN, Float.NaN)
    }

    @Test
    fun nanDoesNotMatchFiniteValue() {
        assertFailsWith<AssertionError> { assertFloatEqualsIncludingNaN(1f, Float.NaN) }
    }
}

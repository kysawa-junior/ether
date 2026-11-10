package dev.instrum.ether.testing

import kotlin.math.abs

/**
 * The default tolerance used when comparing floating point values in engine tests.
 */
public const val DefaultFloatEpsilon: Float = 1e-5f

/**
 * Asserts that [actual] equals [expected] within [epsilon].
 *
 * @throws AssertionError if the values differ by more than [epsilon].
 */
public fun assertFloatEquals(
    expected: Float,
    actual: Float,
    epsilon: Float = DefaultFloatEpsilon,
    message: String? = null,
) {
    val ok = abs(expected - actual) <= epsilon
    if (!ok) {
        throw AssertionError(
            message ?: "Expected <$expected> but was <$actual> (tolerance $epsilon)",
        )
    }
}

/**
 * Asserts that [actual] equals [expected] within [epsilon], treating `NaN` as equal to `NaN`.
 *
 * @throws AssertionError if the values differ by more than [epsilon].
 */
public fun assertFloatEqualsIncludingNaN(
    expected: Float,
    actual: Float,
    epsilon: Float = DefaultFloatEpsilon,
    message: String? = null,
) {
    if (expected.isNaN() && actual.isNaN()) return
    assertFloatEquals(expected, actual, epsilon, message)
}

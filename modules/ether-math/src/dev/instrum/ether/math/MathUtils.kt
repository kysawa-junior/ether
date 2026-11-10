package dev.instrum.ether.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scalar numeric helpers shared across the engine.
 *
 * These functions are intentionally allocation-free and safe to call from hot paths.
 */
public object MathUtils {
    /** The ratio of a circle's circumference to its diameter. */
    public const val PI: Float = 3.14159265358979323846f

    /** Two times [PI]. */
    public const val TAU: Float = 6.28318530717958647692f

    /** Converts [degrees] to radians. */
    public fun toRadians(degrees: Float): Float = degrees * (PI / 180f)

    /** Converts [radians] to degrees. */
    public fun toDegrees(radians: Float): Float = radians * (180f / PI)

    /** Linearly interpolates from [start] to [end] by [t], clamped to `[0, 1]`. */
    public fun lerp(start: Float, end: Float, t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return start + (end - start) * clamped
    }

    /** Linearly interpolates from [start] to [end] by [t], without clamping [t]. */
    public fun lerpUnclamped(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    /** Remaps [value] from the range `[fromLow, fromHigh]` into `[toLow, toHigh]`. */
    public fun remap(
        value: Float,
        fromLow: Float,
        fromHigh: Float,
        toLow: Float,
        toHigh: Float,
    ): Float {
        require(fromHigh != fromLow) { "Cannot remap from a zero-width range" }
        val t = (value - fromLow) / (fromHigh - fromLow)
        return toLow + (toHigh - toLow) * t
    }

    /** The inverse of the hyperbolic sine function. */
    public fun asinh(value: Float): Float = kotlin.math.asinh(value)

    /** Smooth Hermite interpolation between [edge0] and [edge1]. */
    public fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The fractional part of [value]. */
    public fun fract(value: Float): Float = value - floor(value)

    /** Rounds [value] to the nearest integer. */
    public fun roundToInt(value: Float): Int = value.roundToInt()

    /** Whether [a] and [b] are equal within [epsilon]. */
    public fun nearlyEqual(a: Float, b: Float, epsilon: Float = 1e-5f): Boolean = abs(a - b) <= epsilon

    /** Whether [a] and [b] are equal within [epsilon], treating `NaN` as equal to `NaN`. */
    public fun nearlyEqualIncludingNaN(a: Float, b: Float, epsilon: Float = 1e-5f): Boolean =
        if (a.isNaN() && b.isNaN()) true else nearlyEqual(a, b, epsilon)

    /** The sign of [value]: `-1`, `0` or `1`. */
    public fun sign(value: Float): Int = when {
        value > 0f -> 1
        value < 0f -> -1
        else -> 0
    }

    /** The positive modulo of [value] by [modulus]. */
    public fun positiveMod(value: Int, modulus: Int): Int {
        require(modulus > 0) { "Modulus must be positive, got $modulus" }
        val result = value % modulus
        return if (result < 0) result + modulus else result
    }

    /** The next power of two greater than or equal to [value]. */
    public fun nextPowerOfTwo(value: Int): Int {
        require(value > 0) { "Value must be positive, got $value" }
        var result = 1
        while (result < value) {
            result = result shl 1
            if (result <= 0) return 1 shl 30
        }
        return result
    }

    /** Converts [degrees] to radians and returns the sine and cosine of the result. */
    public fun sinCos(radians: Float): Pair<Float, Float> = sin(radians) to cos(radians)

    /** The square root of [value], clamped to zero for negative inputs. */
    public fun safeSqrt(value: Float): Float = sqrt(if (value < 0f) 0f else value)
}

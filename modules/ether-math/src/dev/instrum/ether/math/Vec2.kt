package dev.instrum.ether.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A two-dimensional vector of single-precision components.
 *
 * Instances are immutable. Every operation returns a new instance; no operation mutates the receiver.
 * Equality is exact bitwise-free floating point equality (`Float.equals`), so `Vec2(0.1f, 0.0f) != Vec2(0.1f, 0.0f)`
 * only when the underlying bits differ. Use [approximatelyEquals] for tolerance-based comparison.
 */
public data class Vec2(
    public val x: Float,
    public val y: Float,
) {
    public companion object {
        /** The zero vector `(0, 0)`. */
        public val ZERO: Vec2 = Vec2(0f, 0f)

        /** The one vector `(1, 1)`. */
        public val ONE: Vec2 = Vec2(1f, 1f)

        /** The unit vector along the X axis `(1, 0)`. */
        public val UNIT_X: Vec2 = Vec2(1f, 0f)

        /** The unit vector along the Y axis `(0, 1)`. */
        public val UNIT_Y: Vec2 = Vec2(0f, 1f)
    }

    /** The vector length (magnitude). */
    public val length: Float
        get() = sqrt(x * x + y * y)

    /** The squared vector length. Prefer this over [length] when only comparing magnitudes. */
    public val lengthSquared: Float
        get() = x * x + y * y

    /** The perpendicular vector `(-y, x)`. */
    public val perpendicular: Vec2
        get() = Vec2(-y, x)

    /** The angle of this vector in radians, measured from the positive X axis. */
    public val angle: Float
        get() = atan2(y, x)

    public operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    public operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    public operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)

    public operator fun div(scalar: Float): Vec2 = Vec2(x / scalar, y / scalar)

    /** Component-wise multiplication. */
    public operator fun times(other: Vec2): Vec2 = Vec2(x * other.x, y * other.y)

    /** Component-wise division. */
    public operator fun div(other: Vec2): Vec2 = Vec2(x / other.x, y / other.y)

    public operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    /** The dot product with [other]. */
    public fun dot(other: Vec2): Float = x * other.x + y * other.y

    /** The two-dimensional cross product (scalar) with [other]. */
    public fun cross(other: Vec2): Float = x * other.y - y * other.x

    /** This vector scaled to unit length, or [ZERO] when this vector has zero length. */
    public fun normalized(): Vec2 {
        val len = length
        return if (len == 0f) ZERO else Vec2(x / len, y / len)
    }

    /** This vector scaled to [targetLength]. */
    public fun withLength(targetLength: Float): Vec2 = normalized() * targetLength

    /** The component-wise minimum of this vector and [other]. */
    public fun min(other: Vec2): Vec2 = Vec2(minOf(x, other.x), minOf(y, other.y))

    /** The component-wise maximum of this vector and [other]. */
    public fun max(other: Vec2): Vec2 = Vec2(maxOf(x, other.x), maxOf(y, other.y))

    /** The component-wise absolute value of this vector. */
    public fun abs(): Vec2 = Vec2(abs(x), abs(y))

    /** Linearly interpolates from this vector to [other] by [t], clamped to `[0, 1]`. */
    public fun lerp(other: Vec2, t: Float): Vec2 {
        val clamped = t.coerceIn(0f, 1f)
        return Vec2(x + (other.x - x) * clamped, y + (other.y - y) * clamped)
    }

    /** The distance between this vector and [other]. */
    public fun distanceTo(other: Vec2): Float = (this - other).length

    /** Whether this vector equals [other] within [epsilon] on every component. */
    public fun approximatelyEquals(other: Vec2, epsilon: Float = 1e-5f): Boolean =
        abs(x - other.x) <= epsilon && abs(y - other.y) <= epsilon

    /** Returns this vector rotated counter-clockwise around the origin by [radians]. */
    public fun rotated(radians: Float): Vec2 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** Returns this vector rotated counter-clockwise around [pivot] by [radians]. */
    public fun rotatedAround(pivot: Vec2, radians: Float): Vec2 = (this - pivot).rotated(radians) + pivot

    /** Returns a vector with the X component replaced by [value]. */
    public fun withX(value: Float): Vec2 = copy(x = value)

    /** Returns a vector with the Y component replaced by [value]. */
    public fun withY(value: Float): Vec2 = copy(y = value)
}

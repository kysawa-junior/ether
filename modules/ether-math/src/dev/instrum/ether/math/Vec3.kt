package dev.instrum.ether.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A three-dimensional vector of single-precision components.
 *
 * Instances are immutable. Every operation returns a new instance; no operation mutates the receiver.
 * Equality uses exact floating point equality; use [approximatelyEquals] for tolerance-based comparison.
 */
public data class Vec3(
    public val x: Float,
    public val y: Float,
    public val z: Float,
) {
    public companion object {
        /** The zero vector `(0, 0, 0)`. */
        public val ZERO: Vec3 = Vec3(0f, 0f, 0f)

        /** The one vector `(1, 1, 1)`. */
        public val ONE: Vec3 = Vec3(1f, 1f, 1f)

        /** The unit vector along the X axis `(1, 0, 0)`. */
        public val UNIT_X: Vec3 = Vec3(1f, 0f, 0f)

        /** The unit vector along the Y axis `(0, 1, 0)`. */
        public val UNIT_Y: Vec3 = Vec3(0f, 1f, 0f)

        /** The unit vector along the Z axis `(0, 0, 1)`. */
        public val UNIT_Z: Vec3 = Vec3(0f, 0f, 1f)
    }

    /** The vector length (magnitude). */
    public val length: Float
        get() = sqrt(x * x + y * y + z * z)

    /** The squared vector length. Prefer this over [length] when only comparing magnitudes. */
    public val lengthSquared: Float
        get() = x * x + y * y + z * z

    /** The dot product with [other]. */
    public fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    /** The cross product with [other]. The result is perpendicular to both operands. */
    public fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    public operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    public operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    public operator fun times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)

    public operator fun div(scalar: Float): Vec3 = Vec3(x / scalar, y / scalar, z / scalar)

    /** Component-wise multiplication. */
    public operator fun times(other: Vec3): Vec3 = Vec3(x * other.x, y * other.y, z * other.z)

    /** Component-wise division. */
    public operator fun div(other: Vec3): Vec3 = Vec3(x / other.x, y / other.y, z / other.z)

    public operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    /** This vector scaled to unit length, or [ZERO] when this vector has zero length. */
    public fun normalized(): Vec3 {
        val len = length
        return if (len == 0f) ZERO else Vec3(x / len, y / len, z / len)
    }

    /** This vector scaled to [targetLength]. */
    public fun withLength(targetLength: Float): Vec3 = normalized() * targetLength

    /** The component-wise minimum of this vector and [other]. */
    public fun min(other: Vec3): Vec3 = Vec3(minOf(x, other.x), minOf(y, other.y), minOf(z, other.z))

    /** The component-wise maximum of this vector and [other]. */
    public fun max(other: Vec3): Vec3 = Vec3(maxOf(x, other.x), maxOf(y, other.y), maxOf(z, other.z))

    /** The component-wise absolute value of this vector. */
    public fun abs(): Vec3 = Vec3(abs(x), abs(y), abs(z))

    /** Linearly interpolates from this vector to [other] by [t], clamped to `[0, 1]`. */
    public fun lerp(other: Vec3, t: Float): Vec3 {
        val clamped = t.coerceIn(0f, 1f)
        return Vec3(
            x + (other.x - x) * clamped,
            y + (other.y - y) * clamped,
            z + (other.z - z) * clamped,
        )
    }

    /** The distance between this vector and [other]. */
    public fun distanceTo(other: Vec3): Float = (this - other).length

    /** Whether this vector equals [other] within [epsilon] on every component. */
    public fun approximatelyEquals(other: Vec3, epsilon: Float = 1e-5f): Boolean =
        abs(x - other.x) <= epsilon &&
            abs(y - other.y) <= epsilon &&
            abs(z - other.z) <= epsilon

    /** Returns this vector rotated around the given [axis] by [radians] using the right-hand rule. */
    public fun rotatedAround(axis: Vec3, radians: Float): Vec3 {
        val unitAxis = axis.normalized()
        val sinHalf = kotlin.math.sin(radians * 0.5f)
        val cosHalf = kotlin.math.cos(radians * 0.5f)
        val rotation = Quat(unitAxis.x * sinHalf, unitAxis.y * sinHalf, unitAxis.z * sinHalf, cosHalf)
        return rotation.rotate(this)
    }

    /** Returns a vector with the X component replaced by [value]. */
    public fun withX(value: Float): Vec3 = copy(x = value)

    /** Returns a vector with the Y component replaced by [value]. */
    public fun withY(value: Float): Vec3 = copy(y = value)

    /** Returns a vector with the Z component replaced by [value]. */
    public fun withZ(value: Float): Vec3 = copy(z = value)
}

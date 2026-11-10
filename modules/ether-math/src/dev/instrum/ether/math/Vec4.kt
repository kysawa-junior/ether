package dev.instrum.ether.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A four-dimensional vector of single-precision components.
 *
 * Instances are immutable. Every operation returns a new instance; no operation mutates the receiver.
 */
public data class Vec4(
    public val x: Float,
    public val y: Float,
    public val z: Float,
    public val w: Float,
) {
    public companion object {
        /** The zero vector `(0, 0, 0, 0)`. */
        public val ZERO: Vec4 = Vec4(0f, 0f, 0f, 0f)

        /** The one vector `(1, 1, 1, 1)`. */
        public val ONE: Vec4 = Vec4(1f, 1f, 1f, 1f)

        /** The unit vector along the X axis `(1, 0, 0, 0)`. */
        public val UNIT_X: Vec4 = Vec4(1f, 0f, 0f, 0f)

        /** The unit vector along the Y axis `(0, 1, 0, 0)`. */
        public val UNIT_Y: Vec4 = Vec4(0f, 1f, 0f, 0f)

        /** The unit vector along the Z axis `(0, 0, 1, 0)`. */
        public val UNIT_Z: Vec4 = Vec4(0f, 0f, 1f, 0f)

        /** The unit vector along the W axis `(0, 0, 0, 1)`. */
        public val UNIT_W: Vec4 = Vec4(0f, 0f, 0f, 1f)
    }

    /** The vector length (magnitude). */
    public val length: Float
        get() = sqrt(x * x + y * y + z * z + w * w)

    /** The squared vector length. Prefer this over [length] when only comparing magnitudes. */
    public val lengthSquared: Float
        get() = x * x + y * y + z * z + w * w

    /** The dot product with [other]. */
    public fun dot(other: Vec4): Float = x * other.x + y * other.y + z * other.z + w * other.w

    public operator fun plus(other: Vec4): Vec4 = Vec4(
        x + other.x,
        y + other.y,
        z + other.z,
        w + other.w,
    )

    public operator fun minus(other: Vec4): Vec4 = Vec4(
        x - other.x,
        y - other.y,
        z - other.z,
        w - other.w,
    )

    public operator fun times(scalar: Float): Vec4 = Vec4(x * scalar, y * scalar, z * scalar, w * scalar)

    public operator fun div(scalar: Float): Vec4 = Vec4(x / scalar, y / scalar, z / scalar, w / scalar)

    /** Component-wise multiplication. */
    public operator fun times(other: Vec4): Vec4 = Vec4(
        x * other.x,
        y * other.y,
        z * other.z,
        w * other.w,
    )

    /** Component-wise division. */
    public operator fun div(other: Vec4): Vec4 = Vec4(
        x / other.x,
        y / other.y,
        z / other.z,
        w / other.w,
    )

    public operator fun unaryMinus(): Vec4 = Vec4(-x, -y, -z, -w)

    /** This vector scaled to unit length, or [ZERO] when this vector has zero length. */
    public fun normalized(): Vec4 {
        val len = length
        return if (len == 0f) ZERO else Vec4(x / len, y / len, z / len, w / len)
    }

    /** The component-wise minimum of this vector and [other]. */
    public fun min(other: Vec4): Vec4 = Vec4(
        minOf(x, other.x),
        minOf(y, other.y),
        minOf(z, other.z),
        minOf(w, other.w),
    )

    /** The component-wise maximum of this vector and [other]. */
    public fun max(other: Vec4): Vec4 = Vec4(
        maxOf(x, other.x),
        maxOf(y, other.y),
        maxOf(z, other.z),
        maxOf(w, other.w),
    )

    /** Linearly interpolates from this vector to [other] by [t], clamped to `[0, 1]`. */
    public fun lerp(other: Vec4, t: Float): Vec4 {
        val clamped = t.coerceIn(0f, 1f)
        return Vec4(
            x + (other.x - x) * clamped,
            y + (other.y - y) * clamped,
            z + (other.z - z) * clamped,
            w + (other.w - w) * clamped,
        )
    }

    /** The distance between this vector and [other]. */
    public fun distanceTo(other: Vec4): Float = (this - other).length

    /** Whether this vector equals [other] within [epsilon] on every component. */
    public fun approximatelyEquals(other: Vec4, epsilon: Float = 1e-5f): Boolean =
        abs(x - other.x) <= epsilon &&
            abs(y - other.y) <= epsilon &&
            abs(z - other.z) <= epsilon &&
            abs(w - other.w) <= epsilon

    /** Returns this vector with the X component replaced by [value]. */
    public fun withX(value: Float): Vec4 = copy(x = value)

    /** Returns this vector with the Y component replaced by [value]. */
    public fun withY(value: Float): Vec4 = copy(y = value)

    /** Returns this vector with the Z component replaced by [value]. */
    public fun withZ(value: Float): Vec4 = copy(z = value)

    /** Returns this vector with the W component replaced by [value]. */
    public fun withW(value: Float): Vec4 = copy(w = value)

    /** Converts this vector to a [Vec3] by dropping the W component. */
    public fun toVec3(): Vec3 = Vec3(x, y, z)
}

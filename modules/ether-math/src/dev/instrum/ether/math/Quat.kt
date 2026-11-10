package dev.instrum.ether.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A quaternion representing a rotation in three-dimensional space.
 *
 * The quaternion is stored as `(x, y, z, w)` where `w` is the scalar part and `(x, y, z)` is the
 * vector part. Multiplication order follows the Hamilton convention, so `a * b` applies [b] first
 * and then [a] when used to rotate a vector.
 *
 * Instances are immutable.
 */
public data class Quat(
    public val x: Float,
    public val y: Float,
    public val z: Float,
    public val w: Float,
) {
    public companion object {
        /** The identity rotation `(0, 0, 0, 1)`. */
        public val IDENTITY: Quat = Quat(0f, 0f, 0f, 1f)

        /**
         * Creates a rotation of [radians] around [axis].
         *
         * @throws IllegalArgumentException if [axis] has zero length.
         */
        public fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val unit = axis.normalized()
            require(unit.lengthSquared > 0f) { "Rotation axis must have non-zero length" }
            val half = radians * 0.5f
            val s = sin(half)
            return Quat(unit.x * s, unit.y * s, unit.z * s, cos(half))
        }

        /**
         * Creates a rotation from intrinsic Euler angles applied in the order Z, then Y, then X.
         *
         * @param pitch rotation around the X axis in radians.
         * @param yaw rotation around the Y axis in radians.
         * @param roll rotation around the Z axis in radians.
         */
        public fun fromEuler(pitch: Float, yaw: Float, roll: Float): Quat {
            val cPitch = cos(pitch * 0.5f)
            val sPitch = sin(pitch * 0.5f)
            val cYaw = cos(yaw * 0.5f)
            val sYaw = sin(yaw * 0.5f)
            val cRoll = cos(roll * 0.5f)
            val sRoll = sin(roll * 0.5f)
            return Quat(
                x = sPitch * cYaw * cRoll - cPitch * sYaw * sRoll,
                y = cPitch * sYaw * cRoll + sPitch * cYaw * sRoll,
                z = cPitch * cYaw * sRoll - sPitch * sYaw * cRoll,
                w = cPitch * cYaw * cRoll + sPitch * sYaw * sRoll,
            )
        }
    }

    /** The vector part of this quaternion. */
    public val vector: Vec3
        get() = Vec3(x, y, z)

    /** The squared length of this quaternion. */
    public val lengthSquared: Float
        get() = x * x + y * y + z * z + w * w

    /** The length of this quaternion. */
    public val length: Float
        get() = sqrt(lengthSquared)

    /** The conjugate of this quaternion. For a unit quaternion this equals the inverse. */
    public val conjugate: Quat
        get() = Quat(-x, -y, -z, w)

    /** The inverse of this quaternion. */
    public val inverse: Quat
        get() {
            val lenSq = lengthSquared
            require(lenSq > 0f) { "Cannot invert a zero-length quaternion" }
            val inv = 1f / lenSq
            return Quat(-x * inv, -y * inv, -z * inv, w * inv)
        }

    /** This quaternion normalized to unit length, or [IDENTITY] when it has zero length. */
    public fun normalized(): Quat {
        val len = length
        return if (len == 0f) IDENTITY else Quat(x / len, y / len, z / len, w / len)
    }

    /** Hamilton product of this quaternion with [other]. */
    public operator fun times(other: Quat): Quat = Quat(
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
        w = w * other.w - x * other.x - y * other.y - z * other.z,
    )

    /** Rotates [vector] by this quaternion. The quaternion is assumed to be unit length. */
    public fun rotate(vector: Vec3): Vec3 {
        // v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v)
        val q = vector
        val t = Vec3(
            2f * (y * q.z - z * q.y),
            2f * (z * q.x - x * q.z),
            2f * (x * q.y - y * q.x),
        )
        return Vec3(
            q.x + w * t.x + (y * t.z - z * t.y),
            q.y + w * t.y + (z * t.x - x * t.z),
            q.z + w * t.z + (x * t.y - y * t.x),
        )
    }

    /** Linearly interpolates between this quaternion and [other] by [t], clamped to `[0, 1]`. */
    public fun lerp(other: Quat, t: Float): Quat {
        val clamped = t.coerceIn(0f, 1f)
        return Quat(
            x + (other.x - x) * clamped,
            y + (other.y - y) * clamped,
            z + (other.z - z) * clamped,
            w + (other.w - w) * clamped,
        ).normalized()
    }

    /**
     * Spherically interpolates between this quaternion and [other] by [t].
     *
     * @param t interpolation factor in `[0, 1]`.
     * @throws IllegalArgumentException if either quaternion has zero length.
     */
    public fun slerp(other: Quat, t: Float): Quat {
        require(lengthSquared > 0f && other.lengthSquared > 0f) {
            "Cannot slerp zero-length quaternions"
        }
        val clamped = t.coerceIn(0f, 1f)
        val a = normalized()
        val b = other.normalized()
        var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        val target = if (dot < 0f) {
            dot = -dot
            Quat(-b.x, -b.y, -b.z, -b.w)
        } else {
            b
        }
        if (dot > 0.9995f) {
            return Quat(
                a.x + (target.x - a.x) * clamped,
                a.y + (target.y - a.y) * clamped,
                a.z + (target.z - a.z) * clamped,
                a.w + (target.w - a.w) * clamped,
            ).normalized()
        }
        val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        val sinTheta = sin(theta)
        val scaleA = sin((1f - clamped) * theta) / sinTheta
        val scaleB = sin(clamped * theta) / sinTheta
        return Quat(
            a.x * scaleA + target.x * scaleB,
            a.y * scaleA + target.y * scaleB,
            a.z * scaleA + target.z * scaleB,
            a.w * scaleA + target.w * scaleB,
        ).normalized()
    }

    /** Whether this quaternion equals [other] within [epsilon] on every component. */
    public fun approximatelyEquals(other: Quat, epsilon: Float = 1e-5f): Boolean =
        abs(x - other.x) <= epsilon &&
            abs(y - other.y) <= epsilon &&
            abs(z - other.z) <= epsilon &&
            abs(w - other.w) <= epsilon
}

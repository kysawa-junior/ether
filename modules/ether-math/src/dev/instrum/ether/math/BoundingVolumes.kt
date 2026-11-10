package dev.instrum.ether.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * An axis-aligned bounding box defined by its minimum and maximum corners.
 *
 * Instances are immutable. [center], [extents] and [halfExtents] are derived from the corners.
 */
public data class Aabb(
    public val min: Vec3,
    public val max: Vec3,
) {
    public companion object {
        /**
         * Creates a bounding box that contains all [points].
         *
         * @throws IllegalArgumentException if [points] is empty.
         */
        public fun fromPoints(points: Iterable<Vec3>): Aabb {
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            var found = false
            for (point in points) {
                found = true
                minX = min(minX, point.x)
                minY = min(minY, point.y)
                minZ = min(minZ, point.z)
                maxX = max(maxX, point.x)
                maxY = max(maxY, point.y)
                maxZ = max(maxZ, point.z)
            }
            require(found) { "Cannot build an AABB from an empty point set" }
            return Aabb(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
        }

        /** Creates a bounding box centered at [center] with the given [halfExtents]. */
        public fun fromCenterHalfExtents(center: Vec3, halfExtents: Vec3): Aabb =
            Aabb(center - halfExtents, center + halfExtents)
    }

    /** The center point of this box. */
    public val center: Vec3
        get() = Vec3((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f)

    /** The full size of this box along each axis. */
    public val extents: Vec3
        get() = max - min

    /** The half size of this box along each axis. */
    public val halfExtents: Vec3
        get() = (max - min) * 0.5f

    /** The radius of the smallest sphere centered at [center] that contains this box. */
    public val boundingRadius: Float
        get() = halfExtents.length

    /** The eight corners of this box. */
    public val corners: List<Vec3>
        get() = listOf(
            Vec3(min.x, min.y, min.z),
            Vec3(max.x, min.y, min.z),
            Vec3(min.x, max.y, min.z),
            Vec3(max.x, max.y, min.z),
            Vec3(min.x, min.y, max.z),
            Vec3(max.x, min.y, max.z),
            Vec3(min.x, max.y, max.z),
            Vec3(max.x, max.y, max.z),
        )

    /** Whether [point] lies inside this box, inclusive of the boundary. */
    public fun contains(point: Vec3): Boolean =
        point.x >= min.x && point.x <= max.x &&
            point.y >= min.y && point.y <= max.y &&
            point.z >= min.z && point.z <= max.z

    /** Whether this box overlaps [other]. */
    public fun intersects(other: Aabb): Boolean =
        min.x <= other.max.x && max.x >= other.min.x &&
            min.y <= other.max.y && max.y >= other.min.y &&
            min.z <= other.max.z && max.z >= other.min.z

    /** The smallest box containing both this box and [other]. */
    public fun union(other: Aabb): Aabb = Aabb(min.min(other.min), max.max(other.max))

    /** This box expanded by [amount] in every direction. */
    public fun expanded(amount: Float): Aabb =
        Aabb(min - Vec3(amount, amount, amount), max + Vec3(amount, amount, amount))

    /** This box transformed by [matrix], producing an axis-aligned box that contains the result. */
    public fun transformed(matrix: Mat4): Aabb {
        val transformedCorners = corners.map { matrix.transformPoint(it) }
        return fromPoints(transformedCorners)
    }

    /** Whether this box equals [other] within [epsilon] on every corner component. */
    public fun approximatelyEquals(other: Aabb, epsilon: Float = 1e-5f): Boolean =
        min.approximatelyEquals(other.min, epsilon) && max.approximatelyEquals(other.max, epsilon)
}

/**
 * A bounding sphere defined by its center and radius.
 */
public data class BoundingSphere(
    public val center: Vec3,
    public val radius: Float,
) {
    /** Whether [point] lies inside this sphere, inclusive of the boundary. */
    public fun contains(point: Vec3): Boolean = center.distanceTo(point) <= radius

    /** Whether this sphere overlaps [other]. */
    public fun intersects(other: BoundingSphere): Boolean =
        center.distanceTo(other.center) <= radius + other.radius

    /** The smallest sphere containing both this sphere and [other]. */
    public fun union(other: BoundingSphere): BoundingSphere {
        val distance = center.distanceTo(other.center)
        if (distance == 0f) {
            val radius = maxOf(radius, other.radius)
            return BoundingSphere(center, radius)
        }
        val combinedRadius = (radius + other.radius + distance) * 0.5f
        val ratio = (combinedRadius - radius) / distance
        return BoundingSphere(center + (other.center - center) * ratio, combinedRadius)
    }

    /** This sphere transformed by [matrix], scaled conservatively by the largest axis scale. */
    public fun transformed(matrix: Mat4): BoundingSphere {
        val scale = matrix.scaleFactors()
        return BoundingSphere(matrix.transformPoint(center), radius * scale)
    }
}

/**
 * The largest scale factor applied by [this] matrix, used to conservatively scale bounding volumes.
 */
internal fun Mat4.scaleFactors(): Float {
    val x = Vec3(this[0, 0], this[1, 0], this[2, 0]).length
    val y = Vec3(this[0, 1], this[1, 1], this[2, 1]).length
    val z = Vec3(this[0, 2], this[1, 2], this[2, 2]).length
    return max(x, max(y, z))
}

/**
 * Computes the square root of [value] using a numerically stable formulation.
 */
internal fun stableSqrt(value: Float): Float = sqrt(abs(value))

/**
 * Whether [a] and [b] differ by no more than [epsilon].
 */
internal fun nearlyEqual(a: Float, b: Float, epsilon: Float = 1e-5f): Boolean = abs(a - b) <= epsilon

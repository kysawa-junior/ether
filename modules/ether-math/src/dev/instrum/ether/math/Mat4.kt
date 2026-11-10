package dev.instrum.ether.math

import kotlin.math.abs
import kotlin.math.tan

/**
 * A 4x4 matrix of single-precision components, stored in column-major order.
 *
 * Column-major storage matches the memory layout expected by Vulkan, Metal and WebGL, so matrix
 * data can be uploaded to a GPU buffer without transposition.
 *
 * The element at row [row] and column [column] is accessed with `this[row, column]`.
 */
public class Mat4 private constructor(
    private val elements: FloatArray,
) {
    /** The number of rows and columns in this matrix. */
    public val size: Int get() = 4

    /** The number of elements in this matrix. */
    public val elementCount: Int get() = 16

    /**
     * The element at the given [row] and [column].
     *
     * @throws IndexOutOfBoundsException if [row] or [column] is outside `0..3`.
     */
    public operator fun get(row: Int, column: Int): Float {
        requireIndex(row, column)
        return elements[column * 4 + row]
    }

    /** A copy of the matrix elements in column-major order. */
    public fun toFloatArray(): FloatArray = elements.copyOf()

    /** This matrix multiplied by [other]. */
    public operator fun times(other: Mat4): Mat4 {
        val result = FloatArray(16)
        for (column in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += this[row, k] * other[k, column]
                }
                result[column * 4 + row] = sum
            }
        }
        return Mat4(result)
    }

    /** This matrix multiplied by [vector], treating it as a homogeneous point (implicit `w = 1`). */
    public operator fun times(vector: Vec4): Vec4 = Vec4(
        this[0, 0] * vector.x + this[0, 1] * vector.y + this[0, 2] * vector.z + this[0, 3] * vector.w,
        this[1, 0] * vector.x + this[1, 1] * vector.y + this[1, 2] * vector.z + this[1, 3] * vector.w,
        this[2, 0] * vector.x + this[2, 1] * vector.y + this[2, 2] * vector.z + this[2, 3] * vector.w,
        this[3, 0] * vector.x + this[3, 1] * vector.y + this[3, 2] * vector.z + this[3, 3] * vector.w,
    )

    /**
     * Transforms [point] by this matrix and performs the perspective divide.
     *
     * @throws IllegalStateException if the resulting `w` component is zero, which indicates the
     *   point lies on the camera plane and cannot be projected.
     */
    public fun transformPoint(point: Vec3): Vec3 {
        val x = this[0, 0] * point.x + this[0, 1] * point.y + this[0, 2] * point.z + this[0, 3]
        val y = this[1, 0] * point.x + this[1, 1] * point.y + this[1, 2] * point.z + this[1, 3]
        val z = this[2, 0] * point.x + this[2, 1] * point.y + this[2, 2] * point.z + this[2, 3]
        val w = this[3, 0] * point.x + this[3, 1] * point.y + this[3, 2] * point.z + this[3, 3]
        check(abs(w) > 1e-20f) { "Cannot transform a point with w == 0" }
        return Vec3(x / w, y / w, z / w)
    }

    /**
     * Transforms [direction] by this matrix, ignoring translation.
     */
    public fun transformDirection(direction: Vec3): Vec3 = Vec3(
        this[0, 0] * direction.x + this[0, 1] * direction.y + this[0, 2] * direction.z,
        this[1, 0] * direction.x + this[1, 1] * direction.y + this[1, 2] * direction.z,
        this[2, 0] * direction.x + this[2, 1] * direction.y + this[2, 2] * direction.z,
    )

    /** The transpose of this matrix. */
    public fun transposed(): Mat4 {
        val result = FloatArray(16)
        for (column in 0..3) {
            for (row in 0..3) {
                result[column * 4 + row] = this[column, row]
            }
        }
        return Mat4(result)
    }

    /** The determinant of this matrix. */
    public fun determinant(): Float {
        var det = 0f
        for (column in 0..3) {
            val sign = if (column % 2 == 0) 1f else -1f
            det += sign * this[0, column] * minorDeterminant(removedRow = 0, removedColumn = column)
        }
        return det
    }

    /**
     * The inverse of this matrix.
     *
     * The inverse is computed from the adjugate: `inverse[i][j] = cofactor(j, i) / determinant`.
     * Each cofactor is the signed determinant of the 3x3 minor obtained by removing row `j` and
     * column `i`.
     *
     * @throws IllegalStateException if the matrix is singular (its determinant is zero).
     */
    public fun inverse(): Mat4 {
        val det = determinant()
        check(abs(det) > 1e-20f) { "Cannot invert a singular Mat4" }
        val invDet = 1f / det
        val result = FloatArray(16)
        for (row in 0..3) {
            for (column in 0..3) {
                // The inverse entry (row, column) is the cofactor of (column, row).
                val minor = minorDeterminant(removedRow = column, removedColumn = row)
                val sign = if ((row + column) % 2 == 0) 1f else -1f
                result[column * 4 + row] = sign * minor * invDet
            }
        }
        return Mat4(result)
    }

    /**
     * The determinant of the 3x3 minor formed by removing [removedRow] and [removedColumn].
     */
    private fun minorDeterminant(removedRow: Int, removedColumn: Int): Float {
        val rows = IntArray(3) { index -> if (index >= removedRow) index + 1 else index }
        val columns = IntArray(3) { index -> if (index >= removedColumn) index + 1 else index }
        val r0 = rows[0]
        val r1 = rows[1]
        val r2 = rows[2]
        val c0 = columns[0]
        val c1 = columns[1]
        val c2 = columns[2]
        return this[r0, c0] * (this[r1, c1] * this[r2, c2] - this[r1, c2] * this[r2, c1]) -
            this[r0, c1] * (this[r1, c0] * this[r2, c2] - this[r1, c2] * this[r2, c0]) +
            this[r0, c2] * (this[r1, c0] * this[r2, c1] - this[r1, c1] * this[r2, c0])
    }

    /**
     * The upper-left 3x3 portion of this matrix, used to transform normals.
     */
    public fun normalMatrix(): Mat3 {
        val upper = floatArrayOf(
            this[0, 0], this[1, 0], this[2, 0],
            this[0, 1], this[1, 1], this[2, 1],
            this[0, 2], this[1, 2], this[2, 2],
        )
        return Mat3.fromColumnMajor(upper).inverse().transposed()
    }

    /** Whether this matrix equals [other] within [epsilon] on every element. */
    public fun approximatelyEquals(other: Mat4, epsilon: Float = 1e-5f): Boolean {
        for (column in 0..3) {
            for (row in 0..3) {
                if (abs(this[row, column] - other[row, column]) > epsilon) return false
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Mat4) return false
        return elements.contentEquals(other.elements)
    }

    override fun hashCode(): Int = elements.contentHashCode()

    override fun toString(): String = buildString {
        append("Mat4(")
        for (column in 0..3) {
            append('[')
            for (row in 0..3) {
                if (row > 0) append(", ")
                append(this@Mat4[row, column])
            }
            append(']')
            if (column < 3) append(", ")
        }
        append(')')
    }

    public companion object {
        /** The identity matrix. */
        public val IDENTITY: Mat4 = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** The zero matrix. */
        public val ZERO: Mat4 = Mat4(FloatArray(16))

        /**
         * Creates a matrix from elements in column-major order.
         *
         * @param elements 16 values: columns 0..3, each stored top-to-bottom.
         */
        public fun fromColumnMajor(elements: FloatArray): Mat4 {
            require(elements.size == 16) { "Mat4 requires exactly 16 elements, got ${elements.size}" }
            return Mat4(elements.copyOf())
        }

        /** Creates a translation matrix. */
        public fun translation(translation: Vec3): Mat4 = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                translation.x, translation.y, translation.z, 1f,
            ),
        )

        /** Creates a scale matrix. */
        public fun scale(scale: Vec3): Mat4 = Mat4(
            floatArrayOf(
                scale.x, 0f, 0f, 0f,
                0f, scale.y, 0f, 0f,
                0f, 0f, scale.z, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** Creates a rotation matrix from the quaternion [rotation]. */
        public fun fromQuat(rotation: Quat): Mat4 {
            val r = Mat3.fromQuat(rotation)
            return Mat4(
                floatArrayOf(
                    r[0, 0], r[1, 0], r[2, 0], 0f,
                    r[0, 1], r[1, 1], r[2, 1], 0f,
                    r[0, 2], r[1, 2], r[2, 2], 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /**
         * Creates a right-handed perspective projection matrix.
         *
         * The resulting clip-space depth range is `[0, 1]`, matching Vulkan and Metal. WebGL
         * backends should remap depth to `[-1, 1]`.
         *
         * @param verticalFieldOfView vertical field of view in radians.
         * @param aspectRatio viewport width divided by height.
         * @param nearDistance distance to the near clipping plane; must be greater than zero.
         * @param farDistance distance to the far clipping plane.
         * @throws IllegalArgumentException if [nearDistance] or [farDistance] is not positive, or if
         *   [aspectRatio] is not positive.
         */
        public fun perspective(
            verticalFieldOfView: Float,
            aspectRatio: Float,
            nearDistance: Float,
            farDistance: Float,
        ): Mat4 {
            require(nearDistance > 0f) { "Near distance must be positive, got $nearDistance" }
            require(farDistance > nearDistance) {
                "Far distance ($farDistance) must be greater than near distance ($nearDistance)"
            }
            require(aspectRatio > 0f) { "Aspect ratio must be positive, got $aspectRatio" }
            val f = 1f / tan(verticalFieldOfView * 0.5f)
            return Mat4(
                floatArrayOf(
                    f / aspectRatio, 0f, 0f, 0f,
                    0f, f, 0f, 0f,
                    0f, 0f, farDistance / (nearDistance - farDistance), -1f,
                    0f, 0f, nearDistance * farDistance / (nearDistance - farDistance), 0f,
                ),
            )
        }

        /**
         * Creates a right-handed orthographic projection matrix.
         *
         * The resulting clip-space depth range is `[0, 1]`, matching Vulkan and Metal.
         */
        public fun orthographic(
            left: Float,
            right: Float,
            bottom: Float,
            top: Float,
            nearDistance: Float,
            farDistance: Float,
        ): Mat4 {
            require(right != left) { "Left and right bounds must differ" }
            require(top != bottom) { "Top and bottom bounds must differ" }
            require(farDistance != nearDistance) { "Near and far distances must differ" }
            return Mat4(
                floatArrayOf(
                    2f / (right - left), 0f, 0f, 0f,
                    0f, 2f / (top - bottom), 0f, 0f,
                    0f, 0f, 1f / (nearDistance - farDistance), 0f,
                    (right + left) / (left - right),
                    (top + bottom) / (bottom - top),
                    nearDistance / (nearDistance - farDistance),
                    1f,
                ),
            )
        }

        /**
         * Creates a right-handed look-at view matrix.
         *
         * @param eye the position of the viewer.
         * @param target the point the viewer looks at.
         * @param up the up direction; must not be parallel to the view direction.
         */
        public fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
            val forward = (target - eye).normalized()
            check(forward.lengthSquared > 0f) { "Eye and target must not be equal" }
            val right = forward.cross(up).normalized()
            check(right.lengthSquared > 0f) { "Up vector must not be parallel to the view direction" }
            val trueUp = right.cross(forward)
            return Mat4(
                floatArrayOf(
                    right.x, trueUp.x, -forward.x, 0f,
                    right.y, trueUp.y, -forward.y, 0f,
                    right.z, trueUp.z, -forward.z, 0f,
                    -right.dot(eye), -trueUp.dot(eye), forward.dot(eye), 1f,
                ),
            )
        }
    }

    private fun requireIndex(row: Int, column: Int) {
        require(row in 0..3 && column in 0..3) {
            "Mat4 indices must be in 0..3, got row=$row column=$column"
        }
    }
}

package dev.instrum.ether.math

import kotlin.math.abs

/**
 * A 3x3 matrix of single-precision components, stored in column-major order.
 *
 * Column-major storage matches the memory layout expected by Vulkan, Metal and WebGL, so matrix
 * data can be uploaded to a GPU buffer without transposition.
 *
 * The element at row [row] and column [column] is accessed with `this[row, column]`.
 */
public class Mat3 private constructor(
    private val elements: FloatArray,
) {
    /** The number of rows and columns in this matrix. */
    public val size: Int get() = 3

    /** The number of elements in this matrix. */
    public val elementCount: Int get() = 9

    /**
     * The element at the given [row] and [column].
     *
     * @throws IndexOutOfBoundsException if [row] or [column] is outside `0..2`.
     */
    public operator fun get(row: Int, column: Int): Float {
        requireIndex(row, column)
        return elements[column * 3 + row]
    }

    /** A copy of the matrix elements in column-major order. */
    public fun toFloatArray(): FloatArray = elements.copyOf()

    /** This matrix multiplied by [other]. */
    public operator fun times(other: Mat3): Mat3 {
        val result = FloatArray(9)
        for (column in 0..2) {
            for (row in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += this[row, k] * other[k, column]
                }
                result[column * 3 + row] = sum
            }
        }
        return Mat3(result)
    }

    /** This matrix multiplied by [vector]. */
    public operator fun times(vector: Vec3): Vec3 = Vec3(
        this[0, 0] * vector.x + this[0, 1] * vector.y + this[0, 2] * vector.z,
        this[1, 0] * vector.x + this[1, 1] * vector.y + this[1, 2] * vector.z,
        this[2, 0] * vector.x + this[2, 1] * vector.y + this[2, 2] * vector.z,
    )

    /** The transpose of this matrix. */
    public fun transposed(): Mat3 {
        val result = FloatArray(9)
        for (column in 0..2) {
            for (row in 0..2) {
                result[column * 3 + row] = this[column, row]
            }
        }
        return Mat3(result)
    }

    /** The determinant of this matrix. */
    public fun determinant(): Float =
        this[0, 0] * (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) -
            this[0, 1] * (this[1, 0] * this[2, 2] - this[1, 2] * this[2, 0]) +
            this[0, 2] * (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0])

    /**
     * The inverse of this matrix.
     *
     * The inverse is computed from the adjugate: `inverse[i][j] = cofactor(j, i) / determinant`.
     *
     * @throws IllegalStateException if the matrix is singular (its determinant is zero).
     */
    public fun inverse(): Mat3 {
        val det = determinant()
        check(abs(det) > 1e-20f) { "Cannot invert a singular Mat3" }
        val invDet = 1f / det
        val result = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                // The inverse entry (row, column) is the cofactor of (column, row).
                val minor = minorDeterminant(removedRow = column, removedColumn = row)
                val sign = if ((row + column) % 2 == 0) 1f else -1f
                result[column * 3 + row] = sign * minor * invDet
            }
        }
        return Mat3(result)
    }

    /**
     * The determinant of the 2x2 minor formed by removing [removedRow] and [removedColumn].
     */
    private fun minorDeterminant(removedRow: Int, removedColumn: Int): Float {
        val rows = IntArray(2) { index -> if (index >= removedRow) index + 1 else index }
        val columns = IntArray(2) { index -> if (index >= removedColumn) index + 1 else index }
        return this[rows[0], columns[0]] * this[rows[1], columns[1]] -
            this[rows[0], columns[1]] * this[rows[1], columns[0]]
    }

    /** Whether this matrix equals [other] within [epsilon] on every element. */
    public fun approximatelyEquals(other: Mat3, epsilon: Float = 1e-5f): Boolean {
        for (column in 0..2) {
            for (row in 0..2) {
                if (abs(this[row, column] - other[row, column]) > epsilon) return false
            }
        }
        return true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Mat3) return false
        return elements.contentEquals(other.elements)
    }

    override fun hashCode(): Int = elements.contentHashCode()

    override fun toString(): String = buildString {
        append("Mat3(")
        for (column in 0..2) {
            append('[')
            for (row in 0..2) {
                if (row > 0) append(", ")
                append(this@Mat3[row, column])
            }
            append(']')
            if (column < 2) append(", ")
        }
        append(')')
    }

    public companion object {
        /** The identity matrix. */
        public val IDENTITY: Mat3 = Mat3(
            floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            ),
        )

        /** The zero matrix. */
        public val ZERO: Mat3 = Mat3(FloatArray(9))

        /**
         * Creates a matrix from elements in column-major order.
         *
         * @param elements 9 values: columns 0, 1 and 2, each stored top-to-bottom.
         */
        public fun fromColumnMajor(elements: FloatArray): Mat3 {
            require(elements.size == 9) { "Mat3 requires exactly 9 elements, got ${elements.size}" }
            return Mat3(elements.copyOf())
        }

        /** Creates a scale matrix. */
        public fun scale(scale: Vec3): Mat3 = Mat3(
            floatArrayOf(
                scale.x, 0f, 0f,
                0f, scale.y, 0f,
                0f, 0f, scale.z,
            ),
        )

        /** Creates a rotation matrix from the quaternion [rotation]. */
        public fun fromQuat(rotation: Quat): Mat3 {
            val q = rotation.normalized()
            val xx = q.x * q.x
            val yy = q.y * q.y
            val zz = q.z * q.z
            val xy = q.x * q.y
            val xz = q.x * q.z
            val yz = q.y * q.z
            val wx = q.w * q.x
            val wy = q.w * q.y
            val wz = q.w * q.z
            return Mat3(
                floatArrayOf(
                    1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy),
                    2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx),
                    2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy),
                ),
            )
        }
    }

    private fun requireIndex(row: Int, column: Int) {
        require(row in 0..2 && column in 0..2) {
            "Mat3 indices must be in 0..2, got row=$row column=$column"
        }
    }
}

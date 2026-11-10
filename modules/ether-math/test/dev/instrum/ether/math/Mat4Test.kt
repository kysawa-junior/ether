package dev.instrum.ether.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Mat4Test {

    @Test
    fun identityLeavesVectorsUnchanged() {
        val point = Vec3(1f, 2f, 3f)
        val result = Mat4.IDENTITY.transformPoint(point)
        assertTrue(result.approximatelyEquals(point, 1e-5f))
    }

    @Test
    fun translationMovesPoints() {
        val matrix = Mat4.translation(Vec3(10f, 20f, 30f))
        val result = matrix.transformPoint(Vec3(1f, 1f, 1f))
        assertTrue(result.approximatelyEquals(Vec3(11f, 21f, 31f), 1e-5f))
    }

    @Test
    fun translationDoesNotAffectDirections() {
        val matrix = Mat4.translation(Vec3(10f, 20f, 30f))
        val result = matrix.transformDirection(Vec3(1f, 0f, 0f))
        assertTrue(result.approximatelyEquals(Vec3(1f, 0f, 0f), 1e-5f))
    }

    @Test
    fun scaleMultipliesComponents() {
        val matrix = Mat4.scale(Vec3(2f, 3f, 4f))
        val result = matrix.transformPoint(Vec3(1f, 1f, 1f))
        assertTrue(result.approximatelyEquals(Vec3(2f, 3f, 4f), 1e-5f))
    }

    @Test
    fun matrixMultiplicationComposesTransforms() {
        val translate = Mat4.translation(Vec3(1f, 0f, 0f))
        val scale = Mat4.scale(Vec3(2f, 2f, 2f))
        // translate * scale applies the scale first, then the translation.
        val result = (translate * scale).transformPoint(Vec3(1f, 1f, 1f))
        assertTrue(result.approximatelyEquals(Vec3(3f, 2f, 2f), 1e-5f))
    }

    @Test
    fun inverseUndoesTheOriginalTransform() {
        val matrix = Mat4.translation(Vec3(4f, -2f, 7f)) *
            Mat4.fromQuat(Quat.fromAxisAngle(Vec3.UNIT_Y, 0.7f)) *
            Mat4.scale(Vec3(2f, 2f, 2f))
        val point = Vec3(3f, 5f, 7f)
        val roundTrip = matrix.inverse().transformPoint(matrix.transformPoint(point))
        assertTrue(roundTrip.approximatelyEquals(point, 1e-3f))
    }

    @Test
    fun inverseOfIdentityIsIdentity() {
        assertTrue(Mat4.IDENTITY.inverse().approximatelyEquals(Mat4.IDENTITY, 1e-5f))
    }

    @Test
    fun singularMatrixCannotBeInverted() {
        val singular = Mat4.scale(Vec3(0f, 1f, 1f))
        assertFailsWith<IllegalStateException> { singular.inverse() }
    }

    @Test
    fun transposeSwapsRowsAndColumns() {
        val matrix = Mat4.fromColumnMajor(
            floatArrayOf(
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
                9f, 10f, 11f, 12f,
                13f, 14f, 15f, 16f,
            ),
        )
        val transposed = matrix.transposed()
        assertEquals(matrix[0, 1], transposed[1, 0])
        assertEquals(matrix[2, 3], transposed[3, 2])
    }

    @Test
    fun determinantOfIdentityIsOne() {
        assertEquals(1f, Mat4.IDENTITY.determinant(), 1e-5f)
    }

    @Test
    fun perspectiveProjectsNearPlaneToZeroDepth() {
        val projection = Mat4.perspective(
            verticalFieldOfView = MathUtils.toRadians(60f),
            aspectRatio = 16f / 9f,
            nearDistance = 0.1f,
            farDistance = 100f,
        )
        // A point on the near plane maps to depth 0 in Vulkan-style clip space.
        val nearPoint = projection.transformPoint(Vec3(0f, 0f, -0.1f))
        assertEquals(0f, nearPoint.z, 1e-4f)
        // A point on the far plane maps to depth 1.
        val farPoint = projection.transformPoint(Vec3(0f, 0f, -100f))
        assertEquals(1f, farPoint.z, 1e-4f)
    }

    @Test
    fun perspectiveRejectsInvalidParameters() {
        assertFailsWith<IllegalArgumentException> {
            Mat4.perspective(1f, 1f, 0f, 10f)
        }
        assertFailsWith<IllegalArgumentException> {
            Mat4.perspective(1f, 1f, 1f, 0.5f)
        }
    }

    @Test
    fun lookAtPlacesCameraLookingDownNegativeZ() {
        val view = Mat4.lookAt(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f), Vec3.UNIT_Y)
        // The forward direction maps to the -Z axis of view space.
        val forwardInView = view.transformDirection(Vec3(0f, 0f, -1f))
        assertTrue(forwardInView.approximatelyEquals(Vec3(0f, 0f, -1f), 1e-5f))
    }

    @Test
    fun columnMajorStorageMatchesGpuLayout() {
        val matrix = Mat4.translation(Vec3(7f, 8f, 9f))
        val elements = matrix.toFloatArray()
        // Translation occupies the last column: elements 12, 13, 14.
        assertEquals(7f, elements[12])
        assertEquals(8f, elements[13])
        assertEquals(9f, elements[14])
        assertEquals(1f, elements[15])
    }

    @Test
    fun normalMatrixTransformsNormalsCorrectlyForNonUniformScale() {
        val model = Mat4.scale(Vec3(1f, 2f, 1f))
        val normal = model.normalMatrix()
        val result = normal * Vec3(0f, 1f, 0f)
        // Under a y-scale of 2 the inverse-transpose maps (0,1,0) to (0,0.5,0).
        assertTrue(result.approximatelyEquals(Vec3(0f, 0.5f, 0f), 1e-5f))
    }
}

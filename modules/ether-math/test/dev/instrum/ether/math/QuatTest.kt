package dev.instrum.ether.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuatTest {

    @Test
    fun identityRotationLeavesVectorUnchanged() {
        val v = Vec3(1f, 2f, 3f)
        assertTrue(Quat.IDENTITY.rotate(v).approximatelyEquals(v, 1e-5f))
    }

    @Test
    fun axisAngleRotationByNinetyDegrees() {
        val rotation = Quat.fromAxisAngle(Vec3.UNIT_Z, MathUtils.PI / 2f)
        val result = rotation.rotate(Vec3.UNIT_X)
        assertTrue(result.approximatelyEquals(Vec3.UNIT_Y, 1e-4f))
    }

    @Test
    fun rotationCompositionAppliesRightOperandFirst() {
        val yaw = Quat.fromAxisAngle(Vec3.UNIT_Y, MathUtils.PI / 2f)
        val pitch = Quat.fromAxisAngle(Vec3.UNIT_X, MathUtils.PI / 2f)
        // yaw * pitch applies pitch first, then yaw.
        val composed = yaw * pitch
        val separate = yaw.rotate(pitch.rotate(Vec3.UNIT_X))
        assertTrue(composed.rotate(Vec3.UNIT_X).approximatelyEquals(separate, 1e-4f))
    }

    @Test
    fun conjugateIsInverseForUnitQuaternions() {
        val rotation = Quat.fromAxisAngle(Vec3.UNIT_Y, 0.9f)
        val product = rotation * rotation.conjugate
        assertTrue(product.approximatelyEquals(Quat.IDENTITY, 1e-5f))
    }

    @Test
    fun normalizedQuaternionHasUnitLength() {
        val normalized = Quat(1f, 2f, 3f, 4f).normalized()
        assertEquals(1f, normalized.length, 1e-5f)
    }

    @Test
    fun zeroAxisIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Quat.fromAxisAngle(Vec3.ZERO, 1f)
        }
    }

    @Test
    fun slerpInterpolatesBetweenQuaternions() {
        val start = Quat.IDENTITY
        val end = Quat.fromAxisAngle(Vec3.UNIT_Y, MathUtils.PI / 2f)
        val midpoint = start.slerp(end, 0.5f)
        val expected = Quat.fromAxisAngle(Vec3.UNIT_Y, MathUtils.PI / 4f)
        assertTrue(midpoint.approximatelyEquals(expected, 1e-4f))
    }

    @Test
    fun eulerAnglesMatchEquivalentAxisAngle() {
        val fromEuler = Quat.fromEuler(0f, MathUtils.PI / 2f, 0f)
        val fromAxis = Quat.fromAxisAngle(Vec3.UNIT_Y, MathUtils.PI / 2f)
        assertTrue(fromEuler.approximatelyEquals(fromAxis, 1e-4f))
    }
}

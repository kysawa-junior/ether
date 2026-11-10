package dev.instrum.ether.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Vec3Test {

    @Test
    fun additionAndSubtraction() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(4f, 5f, 6f)
        assertEquals(Vec3(5f, 7f, 9f), a + b)
        assertEquals(Vec3(-3f, -3f, -3f), a - b)
    }

    @Test
    fun scalarMultiplication() {
        assertEquals(Vec3(2f, 4f, 6f), Vec3(1f, 2f, 3f) * 2f)
        assertEquals(Vec3(0.5f, 1f, 1.5f), Vec3(1f, 2f, 3f) / 2f)
    }

    @Test
    fun dotProductIsSymmetric() {
        val a = Vec3(1f, 2f, 3f)
        val b = Vec3(4f, 5f, 6f)
        assertEquals(32f, a.dot(b))
        assertEquals(a.dot(b), b.dot(a))
    }

    @Test
    fun crossProductIsPerpendicularToBothOperands() {
        val a = Vec3(1f, 0f, 0f)
        val b = Vec3(0f, 1f, 0f)
        val cross = a.cross(b)
        assertEquals(Vec3(0f, 0f, 1f), cross)
        assertEquals(0f, cross.dot(a))
        assertEquals(0f, cross.dot(b))
    }

    @Test
    fun lengthAndLengthSquaredAgree() {
        val v = Vec3(3f, 4f, 12f)
        assertEquals(13f, v.length)
        assertEquals(169f, v.lengthSquared)
    }

    @Test
    fun normalizedVectorHasUnitLength() {
        val normalized = Vec3(3f, 0f, 4f).normalized()
        assertEquals(1f, normalized.length)
    }

    @Test
    fun normalizingZeroVectorYieldsZeroRatherThanNaN() {
        val normalized = Vec3.ZERO.normalized()
        assertEquals(Vec3.ZERO, normalized)
        assertFalse(normalized.x.isNaN())
    }

    @Test
    fun lerpClampsInterpolationFactor() {
        val a = Vec3(0f, 0f, 0f)
        val b = Vec3(10f, 10f, 10f)
        assertEquals(Vec3(5f, 5f, 5f), a.lerp(b, 0.5f))
        assertEquals(Vec3.ZERO, a.lerp(b, -1f))
        assertEquals(b, a.lerp(b, 2f))
    }

    @Test
    fun componentWiseMinAndMax() {
        val a = Vec3(1f, 5f, 3f)
        val b = Vec3(4f, 2f, 6f)
        assertEquals(Vec3(1f, 2f, 3f), a.min(b))
        assertEquals(Vec3(4f, 5f, 6f), a.max(b))
    }

    @Test
    fun rotationAroundAxisPreservesLength() {
        val v = Vec3(1f, 0f, 0f)
        val rotated = v.rotatedAround(Vec3.UNIT_Z, MathUtils.PI / 2f)
        assertTrue(rotated.approximatelyEquals(Vec3(0f, 1f, 0f), 1e-4f))
        assertEquals(v.length, rotated.length, 1e-4f)
    }

    @Test
    fun approximatelyEqualsRespectsEpsilon() {
        assertTrue(Vec3(1f, 2f, 3f).approximatelyEquals(Vec3(1.000001f, 2f, 3f), 1e-4f))
        assertFalse(Vec3(1f, 2f, 3f).approximatelyEquals(Vec3(1.1f, 2f, 3f), 1e-4f))
    }
}

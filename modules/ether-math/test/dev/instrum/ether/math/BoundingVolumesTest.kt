package dev.instrum.ether.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundingVolumesTest {

    @Test
    fun boxFromPointsComputesExtents() {
        val box = Aabb.fromPoints(listOf(Vec3(-1f, -1f, -1f), Vec3(1f, 1f, 1f)))
        assertEquals(Vec3(-1f, -1f, -1f), box.min)
        assertEquals(Vec3(1f, 1f, 1f), box.max)
        assertEquals(Vec3.ZERO, box.center)
        assertEquals(Vec3(2f, 2f, 2f), box.extents)
    }

    @Test
    fun boxFromEmptyPointSetIsRejected() {
        assertFailsWith<IllegalArgumentException> { Aabb.fromPoints(emptyList()) }
    }

    @Test
    fun containmentIsInclusiveOfBoundary() {
        val box = Aabb.fromCenterHalfExtents(Vec3.ZERO, Vec3.ONE)
        assertTrue(box.contains(Vec3.ZERO))
        assertTrue(box.contains(Vec3(1f, 1f, 1f)))
        assertTrue(!box.contains(Vec3(1.1f, 0f, 0f)))
    }

    @Test
    fun intersectionDetectsOverlap() {
        val a = Aabb.fromCenterHalfExtents(Vec3.ZERO, Vec3.ONE)
        val overlapping = Aabb.fromCenterHalfExtents(Vec3(1.5f, 0f, 0f), Vec3.ONE)
        val disjoint = Aabb.fromCenterHalfExtents(Vec3(3f, 0f, 0f), Vec3.ONE)
        assertTrue(a.intersects(overlapping))
        assertTrue(!a.intersects(disjoint))
    }

    @Test
    fun unionCoversBothBoxes() {
        val a = Aabb.fromCenterHalfExtents(Vec3(-1f, 0f, 0f), Vec3.ONE)
        val b = Aabb.fromCenterHalfExtents(Vec3(1f, 0f, 0f), Vec3.ONE)
        val union = a.union(b)
        assertEquals(Vec3(-2f, -1f, -1f), union.min)
        assertEquals(Vec3(2f, 1f, 1f), union.max)
    }

    @Test
    fun transformedBoxContainsTransformedCorners() {
        val box = Aabb.fromCenterHalfExtents(Vec3.ZERO, Vec3.ONE)
        val matrix = Mat4.translation(Vec3(10f, 0f, 0f))
        val transformed = box.transformed(matrix)
        assertTrue(transformed.contains(Vec3(11f, 1f, 1f)))
        assertTrue(!transformed.contains(Vec3(0f, 0f, 0f)))
    }

    @Test
    fun sphereUnionCoversBothSpheres() {
        val a = BoundingSphere(Vec3(-1f, 0f, 0f), 1f)
        val b = BoundingSphere(Vec3(1f, 0f, 0f), 1f)
        val union = a.union(b)
        assertTrue(union.contains(Vec3(-2f, 0f, 0f)))
        assertTrue(union.contains(Vec3(2f, 0f, 0f)))
    }

    @Test
    fun sphereIntersectionDetectsOverlap() {
        val a = BoundingSphere(Vec3.ZERO, 1f)
        assertTrue(a.intersects(BoundingSphere(Vec3(1.5f, 0f, 0f), 1f)))
        assertTrue(!a.intersects(BoundingSphere(Vec3(3f, 0f, 0f), 1f)))
    }
}

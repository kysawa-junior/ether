package dev.instrum.ether.map.geom

import dev.instrum.ether.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract tests for the ear-clipping triangulation port, including hole bridging and the
 * degenerate-input rejection (`null`) that keeps importers going instead of aborting. */
class PolygonTriangulatorTest {

    private fun square(offset: Float = 0f) = listOf(
        Vec3(0f, 0f, 0f),
        Vec3(10f, 0f, 0f),
        Vec3(10f, 0f, 10f),
        Vec3(0f, 0f, 10f),
    ).map { Vec3(it.x + offset, 0f, it.z + offset) }

    @Test
    fun `triangula quadrado simples em dois triangulos`() {
        val triangulation = PolygonTriangulator.triangulate(square(), emptyList())!!
        assertEquals(4, triangulation.vertices.size)
        assertEquals(6, triangulation.triangles.size)
        val triangles = triangulation.triangles
        var area = 0.0
        var index = 0
        while (index < triangles.size) {
            val va = triangulation.vertices[triangles[index]]
            val vb = triangulation.vertices[triangles[index + 1]]
            val vc = triangulation.vertices[triangles[index + 2]]
            val cross = (vb.x - va.x) * (vc.z - va.z) - (vb.z - va.z) * (vc.x - va.x)
            area += kotlin.math.abs(cross / 2.0)
            index += 3
        }
        assertEquals(100.0, area, 1e-2)
    }

    @Test
    fun `triangula poligono com buraco respeitando a lacuna`() {
        val hole = listOf(
            Vec3(4f, 0f, 4f),
            Vec3(6f, 0f, 4f),
            Vec3(6f, 0f, 6f),
            Vec3(4f, 0f, 6f),
        )
        val triangulation = PolygonTriangulator.triangulate(square(), listOf(hole))!!
        // 4 outer + 4 hole + 2 duplicated bridge vertices.
        assertEquals(10, triangulation.vertices.size)
        assertTrue(triangulation.triangles.size > 6, "buraco precisa criar triângulos-ponte")

        val outer = square()
        val holeRing = hole
        for (index in 0 until triangulation.triangles.size step 3) {
            val centroid = (0..2).map { triangulation.vertices[triangulation.triangles[index + it]] }
                .reduce { a, b -> Vec3(a.x + b.x, 0f, a.z + b.z) }
                .let { Vec3(it.x / 3f, 0f, it.z / 3f) }
            val insideHole = pointInRing(centroid, holeRing)
            assertTrue(!insideHole, "nenhum triângulo pode cobrir o buraco (centro ${centroid.x},${centroid.z})")
            assertTrue(pointInRing(centroid, outer), "triângulo dentro do anel externo")
        }
    }

    @Test
    fun `poligono degenerado retorna nulo`() {
        // Three collinear points on the XZ plane carry no area.
        val line = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f))
        assertNull(PolygonTriangulator.triangulate(line, emptyList()))
        assertNull(PolygonTriangulator.triangulate(emptyList(), emptyList()))
    }

    /** Even-odd containment test on the XZ plane. */
    private fun pointInRing(point: Vec3, ring: List<Vec3>): Boolean {
        var inside = false
        var i = 0
        var j = ring.size - 1
        while (i < ring.size) {
            val a = ring[i]
            val b = ring[j]
            if ((a.z > point.z) != (b.z > point.z) &&
                point.x < (b.x - a.x) * (point.z - a.z) / (b.z - a.z) + a.x
            ) {
                inside = !inside
            }
            j = i
            i++
        }
        return inside
    }
}

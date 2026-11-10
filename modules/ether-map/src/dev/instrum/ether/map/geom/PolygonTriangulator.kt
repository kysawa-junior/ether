package dev.instrum.ether.map.geom

import dev.instrum.ether.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of an ear-clipping triangulation with hole bridging.
 *
 * [vertices] indexes into the original ring coordinates (XZ plane, Y carried through); every
 * triple in [triangles] forms one triangle, oriented for the caller's purposes.
 */
public class Triangulation(
    public val vertices: List<Vec3>,
    public val triangles: IntArray,
)

/**
 * Triangulation of 2D polygons on the XZ plane with holes, via ear clipping with hole bridging -
 * direct port of the F.O.G.O. `PolygonTriangulator`.
 *
 * Invalid polygons return `null` so an importer keeps going instead of aborting. Sliver triangles
 * (double area below 1 cm^2) are discarded after clipping.
 */
public object PolygonTriangulator {
    private const val EPS = 1e-6

    /**
     * Triangulates [outer] with [holes].
     *
     * @return the triangulation, or `null` when the polygon is degenerate or unclippable.
     */
    public fun triangulate(outer: List<Vec3>, holes: List<List<Vec3>>): Triangulation? {
        var ring = prepare(outer) ?: return null
        for (hole in holes) {
            val prepared = prepare(hole) ?: continue
            val bridged = bridge(ring, prepared) ?: return null
            ring = bridged
        }
        val triangles = earClip(ring) ?: return null
        val kept = ArrayList<Int>(triangles.size)
        var index = 0
        while (index < triangles.size) {
            val a = ring[triangles[index]]
            val b = ring[triangles[index + 1]]
            val c = ring[triangles[index + 2]]
            val area2 = abs((b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x))
            if (area2 >= 2e-4f) {
                kept.add(triangles[index])
                kept.add(triangles[index + 1])
                kept.add(triangles[index + 2])
            }
            index += 3
        }
        if (kept.isEmpty()) return null
        return Triangulation(ring, kept.toIntArray())
    }

    private fun prepare(points: List<Vec3>): List<Vec3>? {
        val out = ArrayList<Vec3>(points.size)
        for (point in points) {
            if (out.isNotEmpty() && same(out.last(), point)) continue
            out.add(point)
        }
        if (out.size > 2 && same(out.first(), out.last())) out.removeAt(out.size - 1)
        return if (out.size < 3) null else out
    }

    private fun same(a: Vec3, b: Vec3): Boolean = abs(a.x - b.x) < EPS && abs(a.z - b.z) < EPS

    private fun area(ring: List<Vec3>): Float {
        var sum = 0f
        for (index in ring.indices) {
            val a = ring[index]
            val b = ring[(index + 1) % ring.size]
            sum += a.x * b.z - b.x * a.z
        }
        return sum / 2f
    }

    private fun bridge(ring: List<Vec3>, holeIn: List<Vec3>): List<Vec3>? {
        var hole = holeIn
        val ringArea = area(ring)
        val holeArea = area(hole)
        if (ringArea > 0f && holeArea > 0f) hole = hole.reversed()
        else if (ringArea < 0f && holeArea < 0f) hole = hole.reversed()

        var holeIndex = 0
        for (index in 1 until hole.size) {
            if (hole[index].x > hole[holeIndex].x) holeIndex = index
        }
        var ringIndex = -1
        var best = Float.NEGATIVE_INFINITY
        for (index in ring.indices) {
            if (ring[index].x < hole[holeIndex].x) continue
            val dx = ring[index].x - hole[holeIndex].x
            val dz = ring[index].z - hole[holeIndex].z
            val score = ring[index].x - sqrt(dx * dx + dz * dz)
            if (score > best) {
                best = score
                ringIndex = index
            }
        }
        if (ringIndex < 0) return null
        val out = ArrayList<Vec3>(ring.size + hole.size + 2)
        out.addAll(ring.subList(0, ringIndex + 1))
        for (index in hole.indices) out.add(hole[(holeIndex + index) % hole.size])
        out.add(hole[holeIndex])
        out.add(ring[ringIndex])
        out.addAll(ring.subList(ringIndex + 1, ring.size))
        return out
    }

    private fun earClip(ring: List<Vec3>): IntArray? {
        val needsReverse = area(ring) > 0f
        var list: MutableList<Int> = MutableList(ring.size) { it }
        if (needsReverse) list = ArrayList(list.reversed())
        val triangles = ArrayList<Int>(maxOf(1, list.size - 2) * 3)
        var guard = 0
        while (list.size > 3 && guard++ < 10000) {
            var clipped = false
            for (i in list.indices) {
                val a = list[(i + list.size - 1) % list.size]
                val b = list[i]
                val c = list[(i + 1) % list.size]
                if (isEar(ring, list, a, b, c)) {
                    triangles.add(a)
                    triangles.add(b)
                    triangles.add(c)
                    list.removeAt(i)
                    clipped = true
                    break
                }
            }
            if (!clipped) return if (triangles.isEmpty()) null else triangles.toIntArray()
        }
        if (list.size == 3) {
            triangles.add(list[0])
            triangles.add(list[1])
            triangles.add(list[2])
        }
        return triangles.toIntArray()
    }

    private fun isEar(ring: List<Vec3>, remaining: List<Int>, a: Int, b: Int, c: Int): Boolean {
        val pa = ring[a]
        val pb = ring[b]
        val pc = ring[c]
        val cross = (pb.x - pa.x) * (pc.z - pa.z) - (pb.z - pa.z) * (pc.x - pa.x)
        if (cross >= 0f) return false
        for (other in remaining) {
            if (other == a || other == b || other == c) continue
            if (pointInTriangle(ring[other], pa, pb, pc)) return false
        }
        return true
    }

    private fun pointInTriangle(p: Vec3, a: Vec3, b: Vec3, c: Vec3): Boolean {
        val d1 = sign(p, a, b)
        val d2 = sign(p, b, c)
        val d3 = sign(p, c, a)
        if (d1 == 0f || d2 == 0f || d3 == 0f) return false
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

    private fun sign(p: Vec3, a: Vec3, b: Vec3): Float =
        (p.x - b.x) * (a.z - b.z) - (a.x - b.x) * (p.z - b.z)
}

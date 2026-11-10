package dev.instrum.ether.map.mesh

import dev.instrum.ether.map.geom.PolygonTriangulator
import dev.instrum.ether.map.geom.Triangulation
import dev.instrum.ether.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Road surface - port of the F.O.G.O. `RoadMeshBuilder`: side outlines with offset line
 * intersections at inner joints, circular arc on the outer side of curves, miter limit of 2h with
 * bevel fallback, straight end caps. Rings (roundabouts) produce a polygon with a hole.
 */
public object RoadMeshBuilder {
    public fun build(
        centerline: List<Vec3>,
        widthMeters: Float,
        yLift: Float,
    ): MeshData? {
        val half = widthMeters / 2f
        if (half <= 0f) return null

        val isRing = centerline.size > 2 &&
            distXZ2(centerline.first(), centerline.last()) < Style.POINT_EPSILON * Style.POINT_EPSILON

        val points = dedupe(centerline)
        if (points.size < if (isRing) 3 else 2) return null

        val count = points.size
        val segmentCount = if (isRing) count else count - 1
        val dirs = ArrayList<Vec3>(segmentCount)
        for (i in 0 until segmentCount) {
            dirs.add(
                Vec3(
                    points[(i + 1) % count].x - points[i].x,
                    0f,
                    points[(i + 1) % count].z - points[i].z,
                ).normalized(),
            )
        }

        val triangulation: Triangulation? = if (isRing) {
            val loopA = compact(buildRingSide(points, dirs, half, 1))
            val loopB = compact(buildRingSide(points, dirs, half, -1))
            if (loopA.size < 3 || loopB.size < 3) return null
            val outer = if (abs(ringArea2(loopA)) >= abs(ringArea2(loopB))) loopA else loopB
            val hole = if (outer === loopA) loopB else loopA
            val holeFlat = hole.map { Vec3(it.x, 0f, it.z) }
            val outerFlat = outer.map { Vec3(it.x, 0f, it.z) }
            var t = if (abs(ringArea2(hole)) / 2f >= 0.5f) {
                PolygonTriangulator.triangulate(outerFlat, listOf(holeFlat))
            } else {
                null
            }
            if (t != null && t.triangles.isNotEmpty()) {
                val covered = coveredArea2(t)
                val expected = abs(ringArea2(outer)) - abs(ringArea2(hole))
                if (covered < 0.95f * expected) t = null
            }
            t ?: PolygonTriangulator.triangulate(outerFlat, emptyList())
        } else {
            val left = buildSide(points, dirs, half, 1)
            val right = buildSide(points, dirs, half, -1)
            if (left.isEmpty() || right.isEmpty()) return null
            val outline = left + right.reversed()
            val compacted = compact(outline)
            if (compacted.size < 3) return null
            PolygonTriangulator.triangulate(compacted.map { Vec3(it.x, 0f, it.z) }, emptyList())
        }
        if (triangulation == null || triangulation.triangles.isEmpty()) return null

        val vertexTotal = triangulation.vertices.size
        val positions = FloatArray(vertexTotal * 3)
        val colors = FloatArray(vertexTotal * 3)
        for (i in 0 until vertexTotal) {
            positions[i * 3] = triangulation.vertices[i].x
            positions[i * 3 + 1] = yLift
            positions[i * 3 + 2] = triangulation.vertices[i].z
        }
        return MeshData(positions, colors, triangulation.triangles)
    }

    private fun coveredArea2(triangulation: Triangulation): Float {
        var sum = 0f
        var i = 0
        while (i < triangulation.triangles.size) {
            val a = triangulation.vertices[triangulation.triangles[i]]
            val b = triangulation.vertices[triangulation.triangles[i + 1]]
            val c = triangulation.vertices[triangulation.triangles[i + 2]]
            sum += abs((b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x))
            i += 3
        }
        return sum
    }

    private fun ringArea2(loop: List<Vec3>): Float {
        var sum = 0f
        for (i in loop.indices) {
            val a = loop[i]
            val b = loop[(i + 1) % loop.size]
            sum += a.x * b.z - b.x * a.z
        }
        return sum
    }

    private fun buildSide(points: List<Vec3>, dirs: List<Vec3>, half: Float, side: Int): List<Vec3> {
        val chain = ArrayList<Vec3>()
        chain.add(offset(points.first(), dirs.first(), side, half))
        for (i in 1 until points.size - 1) {
            chain.addAll(join(points[i], dirs[i - 1], dirs[i], side, half))
        }
        chain.add(offset(points.last(), dirs.last(), side, half))
        return chain
    }

    private fun join(vertex: Vec3, before: Vec3, after: Vec3, side: Int, half: Float): List<Vec3> {
        val cross = before.x * after.z - before.z * after.x
        val dot = before.x * after.x + before.z * after.z
        val delta = atan2(cross, dot)
        val start = offset(vertex, before, side, half)
        val end = offset(vertex, after, side, half)

        if (abs(delta) < 1e-6) return listOf(end)
        if (abs(kotlin.math.PI.toFloat() - abs(delta)) < 1e-5) return listOf(start, end)

        if (delta * side < 0) {
            // Outer side: arc from start to end around the vertex.
            val startAngle = atan2(start.z - vertex.z, start.x - vertex.x)
            val steps = ((abs(delta) / (kotlin.math.PI.toFloat() / 12f)).toInt() + 1).coerceIn(1, 24)
            val out = ArrayList<Vec3>(steps + 1)
            out.add(start)
            for (i in 1..steps) {
                val angle = startAngle + delta * i / steps
                out.add(Vec3(vertex.x + cos(angle) * half, 0f, vertex.z + sin(angle) * half))
            }
            return out
        }

        // Inner side: intersection of the offset lines (bevel fallback).
        if (abs(cross) < 1e-8) return listOf(start, end)
        val betweenX = end.x - start.x
        val betweenZ = end.z - start.z
        val t = (betweenX * after.z - betweenZ * after.x) / cross
        if (!t.isFinite()) return listOf(start, end)
        val intersection = Vec3(start.x + before.x * t, 0f, start.z + before.z * t)
        val distSq = (intersection.x - vertex.x) * (intersection.x - vertex.x) +
            (intersection.z - vertex.z) * (intersection.z - vertex.z)
        if (distSq > half * half * Style.MITER_LIMIT * Style.MITER_LIMIT) {
            return listOf(start, end)
        }
        return listOf(intersection)
    }

    private fun offset(vertex: Vec3, dir: Vec3, side: Int, half: Float): Vec3 =
        Vec3(vertex.x - dir.z * side * half, 0f, vertex.z + dir.x * side * half)

    private fun buildRingSide(points: List<Vec3>, dirs: List<Vec3>, half: Float, side: Int): List<Vec3> {
        val count = points.size
        val out = ArrayList<Vec3>(count * 2)
        for (i in 0 until count) {
            out.addAll(join(points[i], dirs[(i - 1 + count) % count], dirs[i], side, half))
        }
        return out
    }

    private fun dedupe(points: List<Vec3>): List<Vec3> {
        val out = ArrayList<Vec3>(points.size)
        for (point in points) {
            if (point.x.isNaN() || point.y.isNaN() || point.z.isNaN()) continue
            if (out.isNotEmpty() && distXZ2(out.last(), point) < Style.POINT_EPSILON * Style.POINT_EPSILON) continue
            out.add(point)
        }
        if (out.size > 1 && distXZ2(out.first(), out.last()) < Style.POINT_EPSILON * Style.POINT_EPSILON) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    private fun compact(points: List<Vec3>): List<Vec3> {
        val out = ArrayList<Vec3>(points.size)
        for (point in points) {
            if (out.isEmpty() || distXZ2(out.last(), point) >= Style.POINT_EPSILON * Style.POINT_EPSILON) {
                out.add(point)
            }
        }
        if (out.size > 1 && distXZ2(out.first(), out.last()) < Style.POINT_EPSILON * Style.POINT_EPSILON) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    private fun distXZ2(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }
}

package dev.instrum.ether.map.mesh

import dev.instrum.ether.math.Vec3
import dev.instrum.ether.map.geom.PolygonTriangulator
import kotlin.math.abs

/**
 * Extruded building - port of the F.O.G.O. `BuildingMeshBuilder`: walls per ring (holes with
 * inverted orientation), roof and floor triangulated with holes cut out, post-filtered slivers and
 * per vertex colors with fake directional shading.
 */
public object BuildingMeshBuilder {
    public fun build(
        rings: List<List<Vec3>>,
        heightMeters: Float,
        minHeightMeters: Float?,
        colorR: Float,
        colorG: Float,
        colorB: Float,
    ): MeshData? {
        if (rings.isEmpty()) return null
        if (!heightMeters.isFinite()) return null
        val clampedHeight = heightMeters.coerceIn(Style.MIN_BUILDING_HEIGHT, Style.MAX_BUILDING_HEIGHT)
        val base = minHeightMeters ?: 0f
        val top = base + clampedHeight

        val outerPrepared = oriented(dedupeClosed(rings[0]), positive = true)
        if (outerPrepared.size < 3) return null
        val holeRings = rings.drop(1).mapNotNull { hole ->
            val deduped = dedupeClosed(hole)
            if (deduped.size >= 3) oriented(deduped, positive = false) else null
        }

        val vertices = ArrayList<Vec3>()
        val shades = ArrayList<Float>()
        val triangles = ArrayList<Int>()

        for (ringIndex in 0..holeRings.size) {
            val isHole = ringIndex > 0
            val ring = if (isHole) holeRings[ringIndex - 1] else outerPrepared
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                val wall = wallNormal(a, b, flip = isHole)
                val shade = if (isHole) Style.WALL_AMBIENT else shadeFor(wall)
                val i0 = vertices.size
                vertices.add(Vec3(a.x, base + a.y, a.z))
                vertices.add(Vec3(b.x, base + b.y, b.z))
                vertices.add(Vec3(a.x, top + a.y, a.z))
                vertices.add(Vec3(b.x, top + b.y, b.z))
                repeat(4) { shades.add(shade) }
                if (isHole) {
                    triangles.addAll(listOf(i0, i0 + 1, i0 + 2, i0 + 1, i0 + 3, i0 + 2))
                } else {
                    triangles.addAll(listOf(i0, i0 + 2, i0 + 1, i0 + 1, i0 + 2, i0 + 3))
                }
            }
        }

        val roof = PolygonTriangulator.triangulate(
            outerPrepared.map { Vec3(it.x, 0f, it.z) },
            holeRings.map { hole -> hole.map { Vec3(it.x, 0f, it.z) } },
        ) ?: return null
        val roofBase = vertices.size
        for (vertex in roof.vertices) {
            vertices.add(Vec3(vertex.x, top, vertex.z))
            shades.add(Style.ROOF_SHADE)
        }
        for (index in roof.triangles) triangles.add(index + roofBase)

        val floor = PolygonTriangulator.triangulate(
            outerPrepared.map { Vec3(it.x, 0f, it.z) },
            holeRings.map { hole -> hole.map { Vec3(it.x, 0f, it.z) } },
        ) ?: return null
        val floorBase = vertices.size
        for (vertex in floor.vertices) {
            vertices.add(Vec3(vertex.x, base, vertex.z))
            shades.add(Style.WALL_AMBIENT)
        }
        var index = 0
        while (index < floor.triangles.size) {
            triangles.add(floor.triangles[index] + floorBase)
            triangles.add(floor.triangles[index + 2] + floorBase)
            triangles.add(floor.triangles[index + 1] + floorBase)
            index += 3
        }

        // Post-filter of slivers (degenerate walls after float32).
        val kept = ArrayList<Int>(triangles.size)
        var k = 0
        while (k < triangles.size) {
            val a = vertices[triangles[k]]
            val b = vertices[triangles[k + 1]]
            val c = vertices[triangles[k + 2]]
            if ((b - a).cross(c - a).lengthSquared >= 4e-8f) {
                kept.add(triangles[k])
                kept.add(triangles[k + 1])
                kept.add(triangles[k + 2])
            }
            k += 3
        }
        if (kept.isEmpty()) return null

        val positions = FloatArray(vertices.size * 3)
        val colors = FloatArray(vertices.size * 3)
        for (vertex in vertices.indices) {
            positions[vertex * 3] = vertices[vertex].x
            positions[vertex * 3 + 1] = vertices[vertex].y
            positions[vertex * 3 + 2] = vertices[vertex].z
            val shade = shades[vertex]
            colors[vertex * 3] = colorR * shade
            colors[vertex * 3 + 1] = colorG * shade
            colors[vertex * 3 + 2] = colorB * shade
        }
        return MeshData(positions, colors, kept.toIntArray())
    }

    private fun shadeFor(wallNormal: Vec3): Float {
        val dot = wallNormal.dot(Style.LIGHT).coerceIn(0f, 1f)
        return Style.WALL_AMBIENT + Style.WALL_DIFFUSE * dot
    }

    private fun wallNormal(a: Vec3, b: Vec3, flip: Boolean): Vec3 {
        val d = b - a
        var normal = Vec3(d.z, 0f, -d.x)
        if (normal.lengthSquared < 1e-12f) return Vec3(0f, 0f, 1f)
        normal = normal.normalized()
        return if (flip) normal * -1f else normal
    }

    private fun dedupeClosed(points: List<Vec3>): List<Vec3> {
        val out = ArrayList<Vec3>(points.size)
        for (point in points) {
            if (out.isNotEmpty() && distXZ2(out.last(), point) < 1e-4) continue
            out.add(point)
        }
        if (out.size > 2 && distXZ2(out.first(), out.last()) < 1e-4) out.removeAt(out.size - 1)
        return out
    }

    private fun distXZ2(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return dx * dx + dz * dz
    }

    private fun oriented(ring: List<Vec3>, positive: Boolean): List<Vec3> {
        var sum = 0f
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.x * b.z - b.x * a.z
        }
        return if ((sum > 0) == positive) ring else ring.reversed()
    }
}

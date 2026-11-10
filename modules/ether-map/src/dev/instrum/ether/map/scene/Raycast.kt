package dev.instrum.ether.map.scene

import dev.instrum.ether.math.Mat4
import dev.instrum.ether.math.Vec3
import kotlin.math.max
import kotlin.math.min

/**
 * CPU picking - Moller-Trumbore per entity with an AABB pre-test (a single click walks ~200k
 * triangles in ms).
 */
public object Raycast {
    /** Screen ray: `(origin, unit direction)` in world space. Vulkan convention: ndcY -1 is image row 0. */
    public fun screenRay(
        viewProjection: Mat4,
        eye: Vec3,
        pixelX: Float,
        pixelY: Float,
        width: Int,
        height: Int,
    ): Pair<Vec3, Vec3> {
        val inverse = viewProjection.inverse()
        val ndcX = 2f * pixelX / width - 1f
        val ndcY = 2f * pixelY / height - 1f
        val near = inverse.transformPoint(Vec3(ndcX, ndcY, 0f))
        val far = inverse.transformPoint(Vec3(ndcX, ndcY, 1f))
        val direction = (far - near).normalized()
        return Pair(near, direction)
    }

    public fun pick(
        scene: Scene,
        viewProjection: Mat4,
        eye: Vec3,
        pixelX: Float,
        pixelY: Float,
        width: Int,
        height: Int,
    ): SceneEntity? {
        val (origin, direction) = screenRay(viewProjection, eye, pixelX, pixelY, width, height)
        var best: SceneEntity? = null
        var bestT = Float.MAX_VALUE
        for (entity in scene.entities) {
            // Fast AABB (may yield false positives; the triangles refine).
            if (!aabbHit(origin, direction, entity.bounds, bestT)) continue
            var index = 0
            val triangleIndices = entity.indices
            while (index < triangleIndices.size) {
                val t = triangleHit(
                    origin, direction,
                    entity.trianglesWorld[triangleIndices[index] * 3],
                    entity.trianglesWorld[triangleIndices[index] * 3 + 1],
                    entity.trianglesWorld[triangleIndices[index] * 3 + 2],
                    entity.trianglesWorld[triangleIndices[index + 1] * 3],
                    entity.trianglesWorld[triangleIndices[index + 1] * 3 + 1],
                    entity.trianglesWorld[triangleIndices[index + 1] * 3 + 2],
                    entity.trianglesWorld[triangleIndices[index + 2] * 3],
                    entity.trianglesWorld[triangleIndices[index + 2] * 3 + 1],
                    entity.trianglesWorld[triangleIndices[index + 2] * 3 + 2],
                )
                if (t != null && t in 0f..bestT) {
                    bestT = t
                    best = entity
                }
                index += 3
            }
        }
        return best
    }

    private fun aabbHit(origin: Vec3, direction: Vec3, bounds: dev.instrum.ether.math.Aabb, maxT: Float): Boolean {
        var tmin = 0f
        var tmax = maxT
        val boundsMin = bounds.min
        val boundsMax = bounds.max
        val axes = arrayOf(
            floatArrayOf(origin.x, direction.x, boundsMin.x, boundsMax.x),
            floatArrayOf(origin.y, direction.y, boundsMin.y, boundsMax.y),
            floatArrayOf(origin.z, direction.z, boundsMin.z, boundsMax.z),
        )
        for (axis in axes) {
            val o = axis[0]
            val d = axis[1]
            val mn = axis[2]
            val mx = axis[3]
            if (d == 0f) {
                if (o < mn || o > mx) return false
                continue
            }
            var t1 = (mn - o) / d
            var t2 = (mx - o) / d
            if (t1 > t2) {
                val tmp = t1; t1 = t2; t2 = tmp
            }
            tmin = max(tmin, t1)
            tmax = min(tmax, t2)
            if (tmin > tmax) return false
        }
        return true
    }

    private fun triangleHit(
        origin: Vec3,
        direction: Vec3,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ): Float? {
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val px = direction.y * e2z - direction.z * e2y
        val py = direction.z * e2x - direction.x * e2z
        val pz = direction.x * e2y - direction.y * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det > -1e-9f && det < 1e-9f) return null
        val inv = 1f / det
        val tx = origin.x - ax; val ty = origin.y - ay; val tz = origin.z - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0f || u > 1f) return null
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (direction.x * qx + direction.y * qy + direction.z * qz) * inv
        if (v < 0f || u + v > 1f) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (t > 1e-6f) t else null
    }

    /** World to pixel projection (screen labels and overlays). */
    public fun worldToScreen(viewProjection: Mat4, point: Vec3, width: Int, height: Int): Pair<Float, Float>? {
        val clip = viewProjection.transformPoint(point)
        if (clip.z < -1f || clip.z > 1f) return null
        val x = (clip.x + 1f) / 2f * width
        val y = (1f - clip.y) / 2f * height
        if (x < -1000 || y < -1000 || x > width + 1000 || y > height + 1000) return null
        return Pair(x, y)
    }
}

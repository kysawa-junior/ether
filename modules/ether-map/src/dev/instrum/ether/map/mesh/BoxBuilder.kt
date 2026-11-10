package dev.instrum.ether.map.mesh

import dev.instrum.ether.math.Vec3

/**
 * Axis aligned box with per face color (vehicles and utilities) - the top face is brighter than the
 * side faces for cheap volumetric reading.
 */
public object BoxBuilder {
    public fun build(
        center: Vec3,
        sizeX: Float,
        sizeY: Float,
        sizeZ: Float,
        colorR: Float,
        colorG: Float,
        colorB: Float,
    ): MeshData {
        val hx = sizeX / 2f
        val hy = sizeY / 2f
        val hz = sizeZ / 2f
        val x0 = center.x - hx
        val x1 = center.x + hx
        val y0 = center.y - hy
        val y1 = center.y + hy
        val z0 = center.z - hz
        val z1 = center.z + hz

        // 6 faces x 4 vertices; per face color (bright top).
        val positions = floatArrayOf(
            x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, // front (z0)
            x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, // back (z1)
            x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, // left
            x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, // right
            x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, // top
            x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, // bottom
        )
        val side = 0.8f
        val top = 1.0f
        val colors = FloatArray(24 * 3)
        for (face in 0..3) {
            for (vertex in 0..3) {
                colors[(face * 4 + vertex) * 3] = colorR * side
                colors[(face * 4 + vertex) * 3 + 1] = colorG * side
                colors[(face * 4 + vertex) * 3 + 2] = colorB * side
            }
        }
        for (vertex in 0..3) {
            colors[(16 + vertex) * 3] = colorR * top
            colors[(16 + vertex) * 3 + 1] = colorG * top
            colors[(16 + vertex) * 3 + 2] = colorB * top
        }
        val indices = IntArray(36)
        for (face in 0..5) {
            val offset = face * 4
            val index = face * 6
            indices[index] = offset
            indices[index + 1] = offset + 1
            indices[index + 2] = offset + 2
            indices[index + 3] = offset
            indices[index + 4] = offset + 2
            indices[index + 5] = offset + 3
        }
        return MeshData(positions, colors, indices)
    }
}

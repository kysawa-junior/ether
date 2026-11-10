package dev.instrum.ether.map.geometry

import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.BoundingSphere
import dev.instrum.ether.math.Vec3
import kotlin.math.sqrt

/**
 * Device independent mesh; the arrays are the upload representation.
 *
 * Optional attribute arrays follow the position layout ([positions] is xyz per vertex); the UV
 * attribute is xy per vertex. The [bounds] box covers all positions.
 */
public class Mesh(
    public val positions: FloatArray,
    public val indices: IntArray,
    public val normals: FloatArray? = null,
    public val colors: FloatArray? = null,
    public val uvs: FloatArray? = null,
    public val bounds: Aabb = if (positions.size < 3) {
        Aabb(Vec3.ZERO, Vec3.ZERO)
    } else {
        Aabb.fromPoints(
            generateSequence(0) { it + 1 }
                .take(positions.size / 3)
                .map { Vec3(positions[it * 3], positions[it * 3 + 1], positions[it * 3 + 2]) }
                .asIterable(),
        )
    },
) {
    init {
        require(positions.size % 3 == 0) { "positions.size not a multiple of 3" }
        require(normals == null || normals.size == positions.size) {
            "normals.size (${normals?.size}) must match positions.size (${positions.size})"
        }
        require(colors == null || colors.size == positions.size) {
            "colors.size (${colors?.size}) must match positions.size (${positions.size})"
        }
        require(uvs == null || uvs.size == (positions.size / 3) * 2) {
            "uvs.size (${uvs?.size}) must hold xy per vertex"
        }
    }

    public val vertexCount: Int
        get() = positions.size / 3

    public val triangleCount: Int
        get() = indices.size / 3

    public val isValid: Boolean
        get() {
            if (positions.size % 3 != 0) return false
            for (value in positions) if (!value.isFinite()) return false
            for (index in indices) if (index < 0 || index >= vertexCount) return false
            return indices.size % 3 == 0
        }
}

/** Mesh processing utilities (normals, compaction, bounding spheres). */
public object MeshProcessor {
    /** Area weighted smooth normals. */
    public fun generateNormals(positions: FloatArray, indices: IntArray): FloatArray {
        require(positions.size % 3 == 0 && indices.size % 3 == 0) { "mesh inválida" }
        val normals = FloatArray(positions.size)
        var i = 0
        while (i < indices.size) {
            val ia = indices[i] * 3
            val ib = indices[i + 1] * 3
            val ic = indices[i + 2] * 3
            val abx = positions[ib] - positions[ia]
            val aby = positions[ib + 1] - positions[ia + 1]
            val abz = positions[ib + 2] - positions[ia + 2]
            val acx = positions[ic] - positions[ia]
            val acy = positions[ic + 1] - positions[ia + 1]
            val acz = positions[ic + 2] - positions[ia + 2]
            val nx = aby * acz - abz * acy
            val ny = abz * acx - abx * acz
            val nz = abx * acy - aby * acx
            normals[ia] += nx
            normals[ia + 1] += ny
            normals[ia + 2] += nz
            normals[ib] += nx
            normals[ib + 1] += ny
            normals[ib + 2] += nz
            normals[ic] += nx
            normals[ic + 1] += ny
            normals[ic + 2] += nz
            i += 3
        }
        var v = 0
        while (v < normals.size) {
            val length = sqrt(
                normals[v] * normals[v] +
                    normals[v + 1] * normals[v + 1] +
                    normals[v + 2] * normals[v + 2],
            )
            if (length > 1e-12) {
                normals[v] /= length
                normals[v + 1] /= length
                normals[v + 2] /= length
            } else {
                normals[v + 1] = 1f
            }
            v += 3
        }
        return normals
    }

    /** Compacts unreferenced vertices and rebuilds indices. */
    public fun optimize(mesh: Mesh): Mesh {
        val used = BooleanArray(mesh.vertexCount)
        mesh.indices.forEach { used[it] = true }
        val remap = IntArray(mesh.vertexCount) { -1 }
        val positions = ArrayList<Float>(mesh.positions.size)
        val normals = mesh.normals?.let { ArrayList<Float>(it.size) }
        val colors = mesh.colors?.let { ArrayList<Float>(it.size) }
        val uvs = mesh.uvs?.let { ArrayList<Float>(it.size) }
        for (old in 0 until mesh.vertexCount) {
            if (!used[old]) continue
            val new = positions.size / 3
            remap[old] = new
            positions.add(mesh.positions[old * 3])
            positions.add(mesh.positions[old * 3 + 1])
            positions.add(mesh.positions[old * 3 + 2])
            appendVertex(mesh.normals, normals, old, 3)
            appendVertex(mesh.colors, colors, old, 3)
            appendVertex(mesh.uvs, uvs, old, 2)
        }
        val indices = mesh.indices.map { remap[it] }.toIntArray()
        val positionsArray = FloatArray(positions.size) { positions[it] }
        return Mesh(
            positions = positionsArray,
            indices = indices,
            normals = normals?.toFloatArray(),
            colors = colors?.toFloatArray(),
            uvs = uvs?.toFloatArray(),
        )
    }

    /** Copies `stride` floats of vertex `old` from `src` into `dst` (noop when the attribute is absent). */
    private fun appendVertex(src: FloatArray?, dst: ArrayList<Float>?, old: Int, stride: Int) {
        if (src == null || dst == null) return
        for (k in 0 until stride) dst.add(src[old * stride + k])
    }

    public fun boundingSphere(mesh: Mesh): BoundingSphere {
        val center = mesh.bounds.center
        var radiusSquared = 0f
        var i = 0
        while (i < mesh.positions.size) {
            val dx = mesh.positions[i] - center.x
            val dy = mesh.positions[i + 1] - center.y
            val dz = mesh.positions[i + 2] - center.z
            radiusSquared = maxOf(radiusSquared, dx * dx + dy * dy + dz * dz)
            i += 3
        }
        return BoundingSphere(center, sqrt(radiusSquared))
    }
}

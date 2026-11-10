package dev.instrum.ether.map.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mesh processing contracts ported from the removed engine: area weighted normals and unused
 * vertex compaction. */
class MeshProcessorTest {

    @Test
    fun `malha gera normais e remove vertices nao usados`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 9f, 9f, 9f)
        val indices = intArrayOf(0, 1, 2)
        val normals = MeshProcessor.generateNormals(positions, indices)
        // Triangle (1,0,0)x(0,0,1) faces -Y in this convention.
        assertEquals(-1f, normals[4], 1e-6f)
        val mesh = Mesh(positions, indices, normals)
        val optimized = MeshProcessor.optimize(mesh)
        assertEquals(3, optimized.vertexCount)
        assertEquals(1, optimized.triangleCount)
        assertEquals(-1f, optimized.normals!![4], 1e-6f)
    }

    @Test
    fun `malha valida rejeita indices fora do alcance`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val mesh = Mesh(positions, intArrayOf(0, 1, 2))
        assertTrue(mesh.isValid)
        assertFalse(Mesh(positions, intArrayOf(0, 1, 9)).isValid)
        assertFalse(Mesh(positions, intArrayOf(0, 1)).isValid)
    }

    @Test
    fun `bounding sphere cobre todos os vertices`() {
        val positions = floatArrayOf(-1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -1f)
        val mesh = Mesh(positions, intArrayOf(0, 1, 2))
        val sphere = MeshProcessor.boundingSphere(mesh)
        assertEquals(0f, sphere.center.x, 1e-6f)
        assertEquals(-0.5f, sphere.center.z, 1e-6f)
        assertEquals(1.1180341f, sphere.radius, 1e-4f, "raio cobre os cantos extremos")
    }
}

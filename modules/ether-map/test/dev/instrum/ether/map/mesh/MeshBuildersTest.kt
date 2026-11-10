package dev.instrum.ether.map.mesh

import dev.instrum.ether.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mesh builder contracts inherited from the removed engine: height clamping, ring (closed road)
 * handling, degenerate rejection and the fixed vertex/index counts of the box builder. */
class MeshBuildersTest {

    /** 10 m x 10 m closed footprint around the origin. */
    private fun footprint() = listOf(
        Vec3(0f, 0f, 0f),
        Vec3(10f, 0f, 0f),
        Vec3(10f, 0f, 10f),
        Vec3(0f, 0f, 10f),
        Vec3(0f, 0f, 0f),
    )

    @Test
    fun `construtor de edificio extruda paredes teto e piso`() {
        val mesh = BuildingMeshBuilder.build(listOf(footprint()), 12f, null, 0.16f, 0.18f, 0.22f)!!
        assertTrue(mesh.isValid)
        assertTrue(mesh.triangleCount > 8, "paredes + teto + piso")

        val tops = (0 until mesh.vertexCount).map { mesh.positions[it * 3 + 1] }
        assertEquals(12f, tops.max(), 1e-4f, "teto fica na altura informada")
        assertEquals(0f, tops.min(), 1e-6f, "piso no chão")
    }

    @Test
    fun `altura do edificio e limitada ao piso e teto do estilo`() {
        val low = BuildingMeshBuilder.build(listOf(footprint()), 0.2f, null, 1f, 1f, 1f)!!
        assertEquals(
            Style.MIN_BUILDING_HEIGHT,
            (0 until low.vertexCount).maxOf { low.positions[it * 3 + 1] },
            1e-4f,
        )

        val high = BuildingMeshBuilder.build(listOf(footprint()), 500f, null, 1f, 1f, 1f)!!
        assertEquals(
            Style.MAX_BUILDING_HEIGHT,
            (0 until high.vertexCount).maxOf { high.positions[it * 3 + 1] },
            1e-2f,
        )
    }

    @Test
    fun `edificio rejeita anel degenerado`() {
        assertNull(BuildingMeshBuilder.build(emptyList(), 12f, null, 1f, 1f, 1f))
        val line = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f), Vec3(3f, 0f, 0f))
        assertNull(BuildingMeshBuilder.build(listOf(line), 12f, null, 1f, 1f, 1f))
    }

    @Test
    fun `via em anel fecha a malha`() {
        // Roundabout: closed square centerline (+eps closure), 4 m wide.
        val ring = listOf(
            Vec3(0f, 0f, 0f),
            Vec3(20f, 0f, 0f),
            Vec3(20f, 0f, 20f),
            Vec3(0f, 0f, 20f),
            Vec3(0f, 0f, 0f),
        )
        val mesh = RoadMeshBuilder.build(ring, 4f, 0f)!!
        assertTrue(mesh.isValid)
        assertTrue(mesh.triangleCount > 4, "anel produz faixa contínua")
        val top = (0 until mesh.vertexCount).maxOf { mesh.positions[it * 3 + 1] }
        assertEquals(0f, top, 1e-6f, "elevação vem do yLift")
    }

    @Test
    fun `via degenerada retorna nulo`() {
        assertNull(RoadMeshBuilder.build(emptyList(), 6f, 0f))
        assertNull(RoadMeshBuilder.build(listOf(Vec3(0f, 0f, 0f)), 6f, 0f))
        // Zero width cannot be offset.
        assertNull(RoadMeshBuilder.build(listOf(Vec3(0f, 0f, 0f), Vec3(10f, 0f, 0f)), 0f, 0f))
    }

    @Test
    fun `via reta cobre o comprimento do trecho`() {
        val mesh = RoadMeshBuilder.build(listOf(Vec3(0f, 0f, 0f), Vec3(100f, 0f, 0f)), 6f, 0f)!!
        val xs = (0 until mesh.vertexCount).map { mesh.positions[it * 3] }
        assertEquals(0f, xs.min(), 1e-3f, "end caps retos não prolongam o trecho")
        assertEquals(100f, xs.max(), 1e-3f)
        val zs = (0 until mesh.vertexCount).map { mesh.positions[it * 3 + 2] }
        assertEquals(-3f, zs.min(), 1e-3f, "meia-largura para cada lado")
        assertEquals(3f, zs.max(), 1e-3f)
    }

    @Test
    fun `caixa produz 24 vertices e 36 indices`() {
        val box = BoxBuilder.build(Vec3(1f, 2f, 3f), 4f, 2f, 6f, 1f, 0f, 0f)
        assertTrue(box.isValid)
        assertEquals(24, box.vertexCount)
        assertEquals(36, box.indices.size)
        assertEquals(12, box.triangleCount)

        // Top face is brighter than the sides (indices 16..19 are the top face vertices).
        val sideColor = box.colors[0]
        val topColor = box.colors[16 * 3]
        assertEquals(0.8f, sideColor, 1e-6f)
        assertEquals(1f, topColor, 1e-6f)

        val ys = (0 until box.vertexCount).map { box.positions[it * 3 + 1] }
        assertEquals(1f, ys.min(), 1e-6f)
        assertEquals(3f, ys.max(), 1e-6f)
    }
}

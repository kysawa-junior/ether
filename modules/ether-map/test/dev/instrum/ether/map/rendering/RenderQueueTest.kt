package dev.instrum.ether.map.rendering

import dev.instrum.ether.map.camera.PerspectiveCamera
import dev.instrum.ether.map.geometry.Mesh
import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Frustum-culling draw list contracts, ported from the removed engine (`render queue remove item
 * fora do frustum`) plus the batching/instancing/layer rules. The decorative `CullMode` parameter
 * and the unused `InstanceBatch` of the old queue were dropped by design.
 */
class RenderQueueTest {

    private fun triangle() = Mesh(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
        indices = intArrayOf(0, 1, 2),
    )

    private fun item(
        id: String,
        mesh: Mesh,
        min: Vec3,
        max: Vec3,
        pipelineId: String = "p",
        materialId: String = "m",
        layer: RenderLayer = RenderLayer.Opaque,
    ) = RenderItem(id, mesh, Aabb(min, max), pipelineId, materialId, layer)

    @Test
    fun `render queue remove item fora do frustum`() {
        val queue = RenderQueue()
        queue.submit(
            item(
                "far", triangle(),
                Vec3(100_000f, 0f, 0f), Vec3(100_001f, 1f, 1f),
            ),
        )
        val camera = PerspectiveCamera(position = Vec3(0f, 0f, 10f), target = Vec3.ZERO)
        val plan = queue.buildPlan(camera, 1f)
        assertEquals(1, plan.culledItems)
        assertEquals(0, plan.drawCalls)
        assertEquals(0, plan.opaque.size)
        assertEquals(0, plan.transparent.size)
    }

    @Test
    fun `fila agrupa itens por pipeline material e mesh`() {
        val mesh = triangle()
        val queue = RenderQueue()
        val camera = PerspectiveCamera(position = Vec3(0f, 0f, 10f), target = Vec3.ZERO)
        queue.submit(item("a", mesh, Vec3(-1f, -1f, -10f), Vec3(1f, 1f, -5f)))
        queue.submit(item("b", mesh, Vec3(-2f, -1f, -10f), Vec3(-1f, 1f, -5f)))
        queue.submit(item("c", mesh, Vec3(1f, -1f, -10f), Vec3(2f, 1f, -5f), pipelineId = "other"))

        val plan = queue.buildPlan(camera, 1f)
        assertEquals(0, plan.culledItems)
        assertEquals(2, plan.opaque.size, "dois batches: p/m e other/m")
        assertEquals(2, plan.drawCalls, "sem depth prepass: opacos apenas")
        assertEquals(3, plan.instanceCount)
    }

    @Test
    fun `instancing desligado produz um por batch`() {
        val mesh = triangle()
        val queue = RenderQueue()
        val camera = PerspectiveCamera(position = Vec3(0f, 0f, 10f), target = Vec3.ZERO)
        queue.submit(item("a", mesh, Vec3(-1f, -1f, -10f), Vec3(1f, 1f, -5f)))
        queue.submit(item("b", mesh, Vec3(-2f, -1f, -10f), Vec3(-1f, 1f, -5f)))

        val plan = queue.buildPlan(camera, 1f, enableInstancing = false)
        assertEquals(1, plan.opaque.size)
        assertEquals(1, plan.instanceCount, "sem instancing, só o primeiro de cada grupo")
        assertEquals(1, plan.opaque[0].instances.size)
    }

    @Test
    fun `camadas transparentes ordenam por material e prepass usa opacos`() {
        val mesh = triangle()
        val queue = RenderQueue()
        val camera = PerspectiveCamera(position = Vec3(0f, 0f, 10f), target = Vec3.ZERO)
        queue.submit(item("t2", mesh, Vec3(-1f, -1f, -10f), Vec3(1f, 1f, -5f), materialId = "water", layer = RenderLayer.Transparent))
        queue.submit(item("t1", mesh, Vec3(2f, -1f, -10f), Vec3(3f, 1f, -5f), materialId = "glass", layer = RenderLayer.Transparent))
        queue.submit(item("o1", mesh, Vec3(4f, -1f, -10f), Vec3(5f, 1f, -5f)))

        val plan = queue.buildPlan(camera, 1f, depthPrepass = true)
        assertEquals(listOf("glass", "water"), plan.transparent.map { it.materialId })
        assertEquals(1, plan.depthPrepass.size, "prepass cobre só opacos")
        assertEquals(4, plan.drawCalls, "1 opaco + 1 prepass + 2 transparentes")

        // Without the prepass the draw calls come from the layers alone.
        val plain = queue.buildPlan(camera, 1f)
        assertEquals(3, plain.drawCalls)
    }

    @Test
    fun `fila rejeita item sem id e clear esvazia`() {
        val queue = RenderQueue()
        assertFailsWith<IllegalArgumentException> {
            queue.submit(item("  ", triangle(), Vec3(-1f, -1f, -10f), Vec3(1f, 1f, -5f)))
        }
        queue.submit(item("a", triangle(), Vec3(-1f, -1f, -10f), Vec3(1f, 1f, -5f)))
        queue.clear()
        val plan = queue.buildPlan(
            PerspectiveCamera(position = Vec3(0f, 0f, 10f), target = Vec3.ZERO),
            1f,
        )
        assertTrue(plan.opaque.isEmpty() && plan.transparent.isEmpty())
        assertEquals(0, plan.drawCalls)
    }
}

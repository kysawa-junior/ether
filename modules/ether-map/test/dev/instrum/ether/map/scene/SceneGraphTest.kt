package dev.instrum.ether.map.scene

import dev.instrum.ether.map.geometry.Mesh
import dev.instrum.ether.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hybrid graph contracts ported from the removed engine: hierarchy updates, transform combining
 * and the visible-only renderables materialization. */
class SceneGraphTest {

    @Test
    fun `scene graph atualiza hierarquia`() {
        val graph = SceneGraph()
        val parent = SceneObject("parent")
        val child = SceneObject("child")
        parent.add(child)
        graph.add(parent)
        graph.update(0.1f)
        assertEquals(parent, graph.find("parent")?.let { it.children.first().parent })
        assertEquals(1, graph.allObjects().size)
    }

    @Test
    fun `transforms se combinam pai para filho`() {
        val graph = SceneGraph()
        val parent = SceneObject("parent", transform = MapTransform(position = Vec3(10f, 0f, 0f)))
        val child = SceneObject(
            "child",
            transform = MapTransform(position = Vec3(0f, 2f, 0f), scale = Vec3(2f, 2f, 2f)),
        )
        parent.add(child)
        graph.add(parent)
        graph.update(0.016f)
        val world = graph.find("parent")!!.children.first().worldTransform
        assertEquals(10f, world.position.x, 1e-5f)
        assertEquals(2f, world.position.y, 1e-5f)
        assertEquals(2f, world.scale.x, 1e-5f, "escala herda da cadeia")
    }

    @Test
    fun `renderables respeitam visibilidade e componentes`() {
        val graph = SceneGraph()
        val mesh = Mesh(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            indices = intArrayOf(0, 1, 2),
        )
        val visible = SceneObject("visible")
        visible.components.add(MeshComponent(mesh))
        visible.components.add(
            MaterialComponent(
                materialId = "mat",
                parameters = MaterialParameters(baseColor = floatArrayOf(0.1f, 0.2f, 0.3f, 1f)),
            ),
        )
        val hidden = SceneObject("hidden", visible = false)
        hidden.components.add(MeshComponent(mesh))
        visible.add(hidden)
        graph.add(visible)

        val renderables = graph.renderables()
        assertEquals(1, renderables.size, "objeto invisível não materializa")
        assertEquals("visible", renderables[0].objectId)
        assertEquals("mat", renderables[0].material?.materialId)
        assertTrue(renderables[0].bounds.max.x >= mesh.bounds.max.x, "bounds do mesh por padrão")

        visible.visible = false
        assertEquals(0, graph.renderables().size)
    }

    @Test
    fun `grafico rejeita duplicados e ciclos`() {
        val graph = SceneGraph()
        val object1 = SceneObject("a")
        graph.add(object1)
        assertFailsWith(IllegalArgumentException::class) { graph.add(object1) }
        assertEquals(1, graph.allObjects().size)

        val parent = SceneObject("p")
        val child = SceneObject("c")
        val stranger = SceneObject("stranger")
        parent.add(child)
        graph.add(parent)
        graph.add(child) // parented objects register without becoming roots
        assertTrue(graph.roots.contains(parent))
        assertTrue(!graph.roots.contains(child), "filhos não entram na raiz")
        assertFailsWith(IllegalArgumentException::class) { stranger.add(child) }
        assertFailsWith(IllegalArgumentException::class) { child.add(parent) }
        assertTrue(parent.remove(child))
        assertNull(child.parent)
    }
}

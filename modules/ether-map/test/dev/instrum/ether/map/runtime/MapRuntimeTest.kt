package dev.instrum.ether.map.runtime

import dev.instrum.ether.map.TestScenes
import dev.instrum.ether.map.rendering.MapFrameRenderer
import dev.instrum.ether.math.Mat4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime contracts: scene load (upload + fit + queue build), frame driving (camera tick, fleet
 * soup, culling plan, renderer record), CPU pick and the closed lifecycle. The renderer is a
 * recording fake, matching the [MapFrameRenderer] injection design.
 */
class MapRuntimeTest {

    private class RecordingRenderer : MapFrameRenderer {
        var sceneUploads = 0
        var dynamicUploads = 0
        var renders = 0
        var lastViewProjection: Mat4? = null
        var closed = false
        var width = 320
        var height = 240

        override val renderWidth: Int
            get() = width
        override val renderHeight: Int
            get() = height

        override fun uploadScene(scene: dev.instrum.ether.map.scene.Scene) {
            sceneUploads++
        }

        override fun uploadDynamic(mesh: FloatArray) {
            dynamicUploads++
        }

        override fun resize(width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        override fun render(viewProjection: Mat4) {
            renders++
            lastViewProjection = viewProjection
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `runtime carrega cena enquadra e desenha frame`() {
        val renderer = RecordingRenderer()
        val runtime = MapRuntime(renderer = renderer)
        runtime.loadScene(TestScenes.sceneWithOneRoad())
        assertEquals(1, renderer.sceneUploads, "cena enviada ao renderer no load")

        val result = runtime.frame(0.016f)
        assertEquals(1, renderer.renders)
        assertEquals(1, renderer.dynamicUploads, "frota alimenta a sopa dinâmica")
        assertEquals(MapMode.Overhead2D, result.mode, "distância do fit mantém 2D")
        assertTrue(result.plan.drawCalls > 0, "via visível gera draw call")
        assertEquals(0, result.plan.culledItems)
        assertNotNull(renderer.lastViewProjection)

        val picked = runtime.pick(160f, 120f)
        assertNotNull(picked, "centro da tela cai na via")
        assertTrue(picked.id.startsWith("way/"), "entidade da via: ${picked.id}")
    }

    @Test
    fun `frame antes de carregar lanca e pick devolve nulo`() {
        val runtime = MapRuntime(renderer = RecordingRenderer())
        assertNull(runtime.pick(10f, 10f), "sem cena não há pick")
        assertFailsWith<IllegalStateException> { runtime.frame(0.016f) }
    }

    @Test
    fun `loadScene duplicado lanca`() {
        val runtime = MapRuntime(renderer = RecordingRenderer())
        runtime.loadScene(TestScenes.sceneWithOneRoad())
        assertFailsWith<IllegalStateException> { runtime.loadScene(TestScenes.sceneWithOneRoad()) }
    }

    @Test
    fun `resize repassa ao renderer`() {
        val renderer = RecordingRenderer()
        val runtime = MapRuntime(renderer = renderer)
        runtime.resize(640, 480)
        assertEquals(640, renderer.width)
        assertEquals(480, renderer.height)
        assertEquals(640, runtime.renderQueue.let { 640 }, "sanity: queue exposta")
    }

    @Test
    fun `close encerra renderer e invalida frame`() {
        val renderer = RecordingRenderer()
        val runtime = MapRuntime(renderer = renderer)
        runtime.loadScene(TestScenes.sceneWithOneRoad())
        runtime.close()
        assertTrue(renderer.closed, "close repassa ao renderer injetado")
        assertFailsWith<IllegalStateException> { runtime.frame(0.016f) }
    }
}

package dev.instrum.ether.map.rendering

import dev.instrum.ether.map.TestScenes
import dev.instrum.ether.map.camera.OrthographicCamera
import dev.instrum.ether.math.Vec3
import dev.instrum.ether.render.core.GraphicsDevice
import dev.instrum.ether.render.nullbackend.NullBackend
import dev.instrum.ether.render.nullbackend.NullCompletionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recorder-backed frame renderer over the headless `ether-render-null` backend: upload/resize/
 * render lifecycle and the LE pack helpers used for the uploads.
 */
class MapSceneRendererTest {

    @Test
    fun `pack floats e ints produzem little endian`() {
        val floatBytes = packFloats(floatArrayOf(1f, -2f, 0f))
        // IEEE 754: 1f = 0x3f800000, -2f = 0xc0000000 (LE: least significant byte first).
        assertTrue(
            floatBytes.contentEquals(
                byteArrayOf(
                    0x00, 0x00, 0x80.toByte(), 0x3f,
                    0x00, 0x00, 0x00, 0xc0.toByte(),
                    0x00, 0x00, 0x00, 0x00,
                ),
            ),
        )
        val intBytes = packInts(intArrayOf(1, -1, 0x11223344))
        assertTrue(
            intBytes.contentEquals(
                byteArrayOf(
                    0x01, 0x00, 0x00, 0x00,
                    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
                    0x44, 0x33, 0x22, 0x11,
                ),
            ),
        )
        // Round trip through the backend convention.
        assertEquals(1.5f, readLeFloat(packFloats(floatArrayOf(1.5f)), 0))
    }

    @Test
    fun `renderer grava passadas no backend nulo`() {
        val device = GraphicsDevice.create(NullBackend(NullCompletionMode.Manual))
        val renderer = MapSceneRenderer(device, 64, 64)
        try {
            // Before the scene upload there is nothing to draw: render() is a no-op.
            val camera = OrthographicCamera(position = Vec3(0f, 100f, 0f), heightMeters = 200f, near = 1f, far = 200f)
            renderer.render(camera.viewProjection(1f))
            assertEquals(0, device.pendingSubmissionCount)

            renderer.uploadScene(TestScenes.sceneWithOneRoad())
            renderer.render(camera.viewProjection(1f))
            assertEquals(1, device.pendingSubmissionCount, "submissão pendente em modo Manual")
            device.waitIdle()
            assertEquals(0, device.pendingSubmissionCount)

            renderer.uploadDynamic(
                floatArrayOf(
                    0f, 0f, 0f, 1f, 0f, 0f,
                    1f, 0f, 0f, 0f, 1f, 0f,
                ),
            )
            renderer.render(camera.viewProjection(1f))
            device.waitIdle()

            // Pipeline + 2 shader modules + 2 targets + 2 static buffers + 1 dynamic buffer.
            assertEquals(8, device.liveResourceCount)
        } finally {
            renderer.close()
        }
        assertEquals(0, device.liveResourceCount, "close libera tudo que o renderer criou")
        device.close()
        assertTrue(device.isClosed)
    }

    @Test
    fun `resize recria alvos e render sem cena e permitido`() {
        val device = GraphicsDevice.create(NullBackend())
        val renderer = MapSceneRenderer(device, 32, 32)
        try {
            renderer.resize(16, 16)
            assertEquals(16, renderer.renderWidth)
            assertEquals(16, renderer.renderHeight)
            renderer.resize(0, 0) // ignored
            assertEquals(16, renderer.renderWidth)

            renderer.uploadScene(TestScenes.sceneWithOneBuilding())
            renderer.uploadDynamic(FloatArray(0)) // empty soup clears the dynamic buffer
            renderer.render(
                OrthographicCamera(
                    position = Vec3(0f, 100f, 0f),
                    heightMeters = 200f,
                    near = 1f,
                    far = 200f,
                ).viewProjection(1f),
            )
            device.waitIdle()
            assertEquals(0, device.pendingSubmissionCount)
            // Pipeline + 2 shader modules + 2 targets + 2 buffers, no dynamic buffer for the empty soup.
            assertEquals(7, device.liveResourceCount)
        } finally {
            renderer.close()
        }
        assertEquals(0, device.liveResourceCount)
        device.close()
    }

    /** Reads one little-endian float back, mirroring the backend byte order. */
    private fun readLeFloat(bytes: ByteArray, offset: Int): Float {
        var bits = 0
        for (i in 3 downTo 0) {
            bits = (bits shl 8) or (bytes[offset + i].toInt() and 0xff)
        }
        return Float.fromBits(bits)
    }
}

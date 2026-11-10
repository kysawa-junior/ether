package dev.instrum.ether.map.camera

import dev.instrum.ether.math.Mat4
import dev.instrum.ether.math.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vulkan-convention projection contracts inherited from the removed engine: flipped Y, depth
 * 0..1 and the Gribb/Hartmann frustum extractor on top of them. */
class CameraTest {

    @Test
    fun `ortografica vulkan mapeia volume central com y invertido`() {
        val ortho = VulkanProjections.orthographic(100f, 1f, 1f, 101f)
        val center = ortho.transformPoint(Vec3(0f, 0f, -51f))
        assertEquals(0f, center.x, 1e-6f)
        assertEquals(0f, center.y, 1e-6f)
        assertEquals(0.5f, center.z, 1e-6f)
        // Vulkan flipped Y: top of eye space becomes top of the image (ndc -1).
        assertEquals(-1f, ortho.transformPoint(Vec3(0f, 50f, -51f)).y, 1e-6f)
        assertEquals(0f, ortho.transformPoint(Vec3(0f, 0f, -1f)).z, 1e-6f)
        assertEquals(1f, ortho.transformPoint(Vec3(0f, 0f, -101f)).z, 1e-6f)
    }

    @Test
    fun `perspectiva vulkan cumpre profundidade zero a um e y invertido`() {
        val fovY = (PI / 3.0).toFloat() // 60 degrees
        val perspective = VulkanProjections.perspective(fovY, 1f, 0.1f, 100f)
        assertTrue(perspective.transformPoint(Vec3(0f, 50f, -50f)).y < 0f, "y para cima sai negativo em NDC")
        assertEquals(0f, perspective.transformPoint(Vec3(0f, 0f, -0.1f)).z, 1e-4f, "near mapeia em 0")
        assertEquals(1f, perspective.transformPoint(Vec3(0f, 0f, -100f)).z, 1e-4f, "far mapeia em 1")
    }

    @Test
    fun `extrator de frustum normaliza planos e corta pela visao`() {
        val camera = PerspectiveCamera(
            position = Vec3(0f, 0f, 10f),
            target = Vec3.ZERO,
            near = 0.1f,
            far = 1000f,
        )
        val frustum = FrustumExtractor.extract(camera.viewProjection(1f))
        for (plane in listOf(frustum.left, frustum.right, frustum.bottom, frustum.top, frustum.near, frustum.far)) {
            val length = kotlin.math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z)
            assertEquals(1f, length, 1e-4f, "planos normalizados")
        }
        assertTrue(frustum.intersectsSphere(Vec3(0f, 0f, -100f), 1f), "esfera dentro da visão")
        assertTrue(!frustum.intersectsSphere(Vec3(0f, 0f, 200f), 1f), "esfera atrás da câmera")
        assertTrue(
            frustum.intersectsBox(Vec3(-1f, -1f, -10f), Vec3(1f, 1f, -5f)),
            "box dentro da visão",
        )
        assertTrue(
            !frustum.intersectsBox(Vec3(10_000f, -1f, -10f), Vec3(10_001f, 1f, -5f)),
            "box fora da visão",
        )
    }

    @Test
    fun `orthographic camera orbita e produz matriz finita`() {
        val camera = OrthographicCamera(
            position = Vec3(0f, 100f, 0f),
            target = Vec3.ZERO,
            heightMeters = 200f,
            near = 1f,
            far = 200f,
        )
        val viewProjection = camera.viewProjection(1.5f)
        assertTrue(viewProjection.toFloatArray().all { it.isFinite() })
        // The world center sits 100 m below the eye: half of the 1..200 depth range.
        val center = viewProjection.transformPoint(Vec3.ZERO)
        assertEquals(0f, center.x, 1e-4f)
        assertEquals(0f, center.y, 1e-4f)
        assertEquals(0.4975f, center.z, 1e-3f)
    }

    @Test
    fun `matriz view projection compoe look e projecao`() {
        val camera = PerspectiveCamera()
        val composed = camera.viewProjection(1f)
        val manual = camera.projectionMatrix(1f) * camera.viewMatrix()
        assertTrue(composed.approximatelyEquals(manual, 1e-5f))
        assertEquals(Mat4.lookAt(camera.position, camera.target, Vec3(0f, 1f, 0f)), camera.viewMatrix())
    }
}

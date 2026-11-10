package dev.instrum.ether.map.scene

import dev.instrum.ether.map.TestScenes
import dev.instrum.ether.map.camera.OrthographicCamera
import dev.instrum.ether.map.camera.PerspectiveCamera
import dev.instrum.ether.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CPU picking contracts: Möller–Trumbore with AABB pre-test, and the screen projector being the
 * inverse of the ray caster (Vulkan ndcY -1 = image row 0). */
class RaycastTest {

    @Test
    fun `raycast acerta edificio no centro da tela`() {
        val scene = TestScenes.sceneWithOneBuilding()
        val camera = OrthographicCamera(
            position = Vec3(0f, 100f, 0f),
            target = Vec3.ZERO,
            heightMeters = 200f,
            near = 1f,
            far = 1000f,
        )
        val viewProjection = camera.viewProjection(1f)
        val hit = Raycast.pick(scene, viewProjection, camera.position, 50f, 50f, 100, 100)
        assertNotNull(hit)
        assertEquals(SceneEntity.EntityKind.Building, hit.kind)

        assertNull(Raycast.pick(scene, viewProjection, camera.position, 3f, 3f, 100, 100))
    }

    @Test
    fun `raycast escolha o mais proximo entre entidades`() {
        val scene = TestScenes.sceneWithAreaAndRoad()
        val center = scene.worldBounds.center
        val camera = OrthographicCamera(
            position = Vec3(center.x, 100f, center.z),
            target = Vec3(center.x, 0f, center.z),
            heightMeters = 200f,
            near = 1f,
            far = 1000f,
        )
        val viewProjection = camera.viewProjection(1f)
        val hit = Raycast.pick(scene, viewProjection, camera.position, 50f, 50f, 100, 100)
        assertNotNull(hit)
        assertTrue(
            hit.kind == SceneEntity.EntityKind.Road || hit.kind == SceneEntity.EntityKind.Area,
            "qualquer superfície horizontal serve: ${hit.kind}",
        )
    }

    @Test
    fun `worldToScreen e screenRay sao inversos`() {
        val camera = PerspectiveCamera(
            position = Vec3(0f, 50f, 100f),
            target = Vec3.ZERO,
            near = 1f,
            far = 1000f,
        )
        val viewProjection = camera.viewProjection(1.5f)
        val point = Vec3.ZERO
        val screen = Raycast.worldToScreen(viewProjection, point, 300, 200)
        assertNotNull(screen)
        val (pixelX, pixelY) = screen
        assertEquals(150f, pixelX, 1e-2f, "ponto alvo fica no centro da imagem")
        assertEquals(100f, pixelY, 1e-2f)

        val (origin, direction) = Raycast.screenRay(viewProjection, camera.position, pixelX, pixelY, 300, 200)
        val t = (point - origin).dot(direction)
        assertTrue(t > 0f, "raio parte da câmera na direção do alvo")
        val recovered = origin + direction * t
        assertEquals(point.x, recovered.x, 1e-2f)
        assertEquals(point.y, recovered.y, 1e-2f)
        assertEquals(point.z, recovered.z, 1e-2f)
    }

    @Test
    fun `worldToScreen rejeita atras da camera`() {
        val camera = PerspectiveCamera(
            position = Vec3(0f, 50f, 100f),
            target = Vec3.ZERO,
            near = 1f,
            far = 1000f,
        )
        val viewProjection = camera.viewProjection(1f)
        assertNull(Raycast.worldToScreen(viewProjection, Vec3(0f, 50f, 200f), 100, 100))
    }
}

package dev.instrum.ether.map.scene

import dev.instrum.ether.map.runtime.MapMode
import dev.instrum.ether.map.runtime.MapModePolicy
import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Vec3
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Map camera contracts: startup in Overhead2D, the user 2D/3D distance swap with the hysteresis
 * band (220 m close / 420 m far) replacing the removed single threshold, and the movement
 * constraints (zoom limits, pan confinement, fit).
 */
class OrbitCameraTest {

    @Test
    fun `camera inicia em overhead 2d`() {
        val camera = OrbitCamera()
        assertEquals(MapMode.Overhead2D, camera.mode, "estado inicial distante renderiza 2D")
        val viewProjection = camera.camera(0.75f).viewProjection(0.75f)
        assertTrue(viewProjection.toFloatArray().all { it.isFinite() })
    }

    @Test
    fun `camera orbital 2d quando longe e 3d ao aproximar`() {
        val camera = OrbitCamera()
        camera.goalDistance = 5_000f
        camera.goalElevation = 0.9f
        camera.tick(60f) // damping saturated
        assertEquals(OrbitCamera.TOP_DOWN_ELEVATION, camera.elevationRadians, 1e-9f)
        assertEquals(MapMode.Overhead2D, camera.mode)

        // Coming close beyond the threshold reveals the 3D with the default tilt.
        camera.goalDistance = 200f
        camera.tick(60f)
        assertEquals(OrbitCamera.TILT_ELEVATION, camera.elevationRadians, 1e-9f)
        assertEquals(MapMode.Street3D, camera.mode)

        // Moving back away returns to the 2D zenith.
        camera.goalDistance = 8_000f
        camera.tick(60f)
        assertEquals(OrbitCamera.TOP_DOWN_ELEVATION, camera.elevationRadians, 1e-9f)
        assertEquals(MapMode.Overhead2D, camera.mode)
    }

    @Test
    fun `histerese mantem modo dentro da faixa`() {
        // Flat at 400 m: inside the band, keeps the 2D (oscillation suppression).
        val flat = OrbitCamera()
        flat.goalDistance = 400f
        flat.tick(60f)
        assertEquals(MapMode.Overhead2D, flat.mode)

        // Three-dimensional at 300 m: inside the band, keeps the 3D.
        val tilted = OrbitCamera()
        tilted.goalDistance = 200f
        tilted.tick(60f)
        tilted.goalDistance = 300f
        tilted.tick(60f)
        assertEquals(MapMode.Street3D, tilted.mode)
    }

    @Test
    fun `modo muda somente na passada do tick`() {
        val camera = OrbitCamera() // starts flat (far away)
        camera.goalDistance = 100f // below the close threshold
        assertEquals(MapMode.Overhead2D, camera.mode, "sem tick, o modo do frame se mantém")
        camera.tick(60f)
        assertEquals(MapMode.Street3D, camera.mode)
    }

    @Test
    fun `tick satura amortecimento e obedece metas`() {
        val camera = OrbitCamera(smoothing = 12f)
        // First tick demotes the camera to 3D (TILT), which releases goalElevation to the caller.
        camera.goalDistance = 100f
        camera.tick(60f)
        camera.goalDistance = 1_000f
        camera.goalElevation = 1.2f
        camera.goalAzimuth = 0.5f
        camera.goalTarget = Vec3(30f, 0f, -40f)
        camera.tick(60f)
        assertEquals(1_000f, camera.distanceMeters, 1e-3f)
        assertEquals(0.5f, camera.azimuthRadians, 1e-4f)
        assertEquals(30f, camera.target.x, 1e-3f)
        assertEquals(-40f, camera.target.z, 1e-3f)
        // Damping interpolates smoothly (not yet saturated after a short step).
        val smoothCamera = OrbitCamera(smoothing = 12f)
        smoothCamera.goalDistance = 100f
        smoothCamera.tick(60f)
        val before = smoothCamera.distanceMeters
        smoothCamera.goalDistance = 200f
        smoothCamera.tick(0.016f)
        assertTrue(smoothCamera.distanceMeters > before, "amortecimento avança na direção da meta")
        assertTrue(smoothCamera.distanceMeters < 200f, "e não satura em um passo curto")
    }

    @Test
    fun `zoom respeita limites e pan confina ao bounds`() {
        val camera = OrbitCamera()
        camera.zoom(1_000_000f)
        assertEquals(OrbitCamera().minDistance, camera.goalDistance, 1e-4f, "zoom in limitado")
        camera.zoom(1e-9f)
        assertEquals(25_000f, camera.goalDistance, 1e-2f, "zoom out limitado")

        val bounds = Aabb(min = Vec3(-10f, 0f, -10f), max = Vec3(10f, 0f, 10f))
        camera.confine(bounds)
        camera.pan(1_000f, 0f)
        camera.tick(60f)
        assertTrue(camera.target.x in -10f..10f, "target X confinado: ${camera.target.x}")
        assertTrue(camera.target.z in -10f..10f, "target Z confinado: ${camera.target.z}")
    }

    @Test
    fun `fit enquadra bounds com orientacao longa`() {
        val camera = OrbitCamera()
        camera.fit(Aabb(min = Vec3(-100f, 0f, -10f), max = Vec3(100f, 0f, 10f)), instant = true)
        // Long axis on X (200 m): azimuth pi/2, distance = diagonal * 1.15.
        assertEquals((PI / 2).toFloat(), camera.azimuthRadians, 1e-4f)
        assertEquals(231.1471f, camera.distanceMeters, 1e-2f)
        assertEquals(0f, camera.target.x, 1e-4f)
        assertEquals(0f, camera.target.z, 1e-4f)

        // Square bounds tilt the other way (dx >= dz still picks azimuth pi/2; use deep Z instead).
        val vertical = OrbitCamera()
        vertical.fit(Aabb(min = Vec3(-10f, 0f, -100f), max = Vec3(10f, 0f, 100f)), instant = true)
        assertEquals(0f, vertical.azimuthRadians, 1e-4f, "dz > dx orienta no eixo Z")
    }

    @Test
    fun `politica e configuravel pelo usuario`() {
        val camera = OrbitCamera(modePolicy = MapModePolicy(100f, 300f))
        camera.goalDistance = 90f
        camera.tick(60f)
        assertEquals(MapMode.Street3D, camera.mode)
        camera.goalDistance = 250f
        camera.tick(60f)
        assertEquals(MapMode.Street3D, camera.mode, "faixa de histerese própria mantém 3D")
        camera.goalDistance = 400f
        camera.tick(60f)
        assertEquals(MapMode.Overhead2D, camera.mode)
    }
}

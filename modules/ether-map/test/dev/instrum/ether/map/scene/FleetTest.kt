package dev.instrum.ether.map.scene

import dev.instrum.ether.map.TestScenes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Simulated fleet: same seed determinism (`kotlin.random.Random(20261028)` replaces the JVM
 * `java.util.Random`), movement along road segments and the empty-roads guard. */
class FleetTest {

    @Test
    fun `frota e determinista com a mesma semente`() {
        val scene = TestScenes.sceneWithOneRoad()
        val first = FleetSystem(scene, vehicleCount = 4)
        val second = FleetSystem(scene, vehicleCount = 4)
        assertEquals(first.vehicles.size, second.vehicles.size)
        for (index in first.vehicles.indices) {
            assertEquals(first.vehicles[index].kind, second.vehicles[index].kind)
            assertEquals(first.vehicles[index].roadIndex, second.vehicles[index].roadIndex)
        }
        val firstFrame = first.tick(0.016f)
        val secondFrame = second.tick(0.016f)
        assertTrue(firstFrame.contentEquals(secondFrame), "mesma cena e semente: mesma malha")
        assertTrue(firstFrame.isNotEmpty())
        // The dynamic soup is non-indexed with 6 floats per vertex.
        assertEquals(0, firstFrame.size % 6)
    }

    @Test
    fun `frota anda pelos segmentos da via`() {
        val scene = TestScenes.sceneWithOneRoad()
        val fleet = FleetSystem(scene, vehicleCount = 2)
        val before = fleet.vehicles.map { it.t }
        fleet.tick(0.016f)
        for (index in fleet.vehicles.indices) {
            assertTrue(
                fleet.vehicles[index].t != before[index],
                "veículo $index avança pelo segmento",
            )
        }
        // Vehicles alternate Police/Fire and carry the palette colors.
        assertEquals(FleetVehicle.Kind.Police, fleet.vehicles[0].kind)
        assertEquals(FleetVehicle.Kind.Fire, fleet.vehicles[1].kind)
    }

    @Test
    fun `frota sem vias retorna malha vazia`() {
        val fleet = FleetSystem(TestScenes.sceneWithOneBuilding(), vehicleCount = 3)
        val frame = fleet.tick(0.016f)
        assertEquals(0, frame.size)
    }
}

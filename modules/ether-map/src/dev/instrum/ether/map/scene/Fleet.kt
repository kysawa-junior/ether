package dev.instrum.ether.map.scene

import dev.instrum.ether.map.mesh.BoxBuilder
import dev.instrum.ether.math.Vec3
import kotlin.random.Random

/**
 * Simulated fleet: colored boxes driving on the scene roads - the live state stays in the app
 * (inventory/server); only the movement lives here.
 *
 * Divergence from the removed engine: `kotlin.random.Random` replaces `java.util.Random` for
 * multiplatform support, so the box sequence differs from the JVM-only implementation. The seed is
 * the same, preserving determinism per run.
 */
public class FleetVehicle(
    public val kind: Kind,
    public var roadIndex: Int,
    public var segmentIndex: Int,
    public var t: Float,
    public var forward: Boolean,
) {
    public enum class Kind { Police, Fire }
}

public class FleetSystem(private val scene: Scene, vehicleCount: Int = 10) {
    private val roads = scene.entities.filter { it.kind == SceneEntity.EntityKind.Road }
    private val random = Random(20261028)
    public val vehicles: List<FleetVehicle> = List(vehicleCount) {
        FleetVehicle(
            kind = if (it % 2 == 0) FleetVehicle.Kind.Police else FleetVehicle.Kind.Fire,
            roadIndex = random.nextInt(maxOf(1, roads.size)),
            segmentIndex = 0,
            t = random.nextDouble().toFloat(),
            forward = random.nextBoolean(),
        )
    }

    /** Typical urban speeds (m/s). */
    private val speedMetersPerSecond = 14f

    /**
     * Advances the fleet; returns the dynamic NON-INDEXED mesh (triangle soup with interleaved
     * position+color - indexless draw in the renderer).
     */
    public fun tick(deltaSeconds: Float): FloatArray {
        val out = ArrayList<Float>(vehicles.size * 36 * 6)
        if (roads.isEmpty()) return FloatArray(0)
        for (vehicle in vehicles) {
            val road = roads[vehicle.roadIndex]
            val count = road.trianglesWorld.size / 3
            if (count < 2) continue
            val segmentCount = count - 1
            vehicle.segmentIndex = vehicle.segmentIndex.coerceIn(0, segmentCount - 1)
            val segment = vehicle.segmentIndex
            val ax = road.trianglesWorld[segment * 3]
            val az = road.trianglesWorld[segment * 3 + 2]
            val bx = road.trianglesWorld[(segment + 1) * 3]
            val bz = road.trianglesWorld[(segment + 1) * 3 + 2]
            val segmentLength = maxOf(1e-3f, kotlin.math.sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az)))
            vehicle.t += speedMetersPerSecond * deltaSeconds / segmentLength * if (vehicle.forward) 1f else -1f
            if (vehicle.t > 1f) {
                vehicle.t = 0f
                vehicle.segmentIndex = (segment + 1).coerceAtMost(segmentCount - 1)
            } else if (vehicle.t < 0f) {
                vehicle.t = 1f
                vehicle.segmentIndex = (segment - 1).coerceAtLeast(0)
            }
            val x = ax + (bx - ax) * vehicle.t
            val z = az + (bz - az) * vehicle.t
            val color = if (vehicle.kind == FleetVehicle.Kind.Police) Palette.POLICE_VEHICLE else Palette.FIRE_VEHICLE
            val mesh = BoxBuilder.build(Vec3(x, 1f, z), 4.5f, 2f, 2.2f, color[0], color[1], color[2])
            for (index in mesh.indices) {
                out.add(mesh.positions[index * 3])
                out.add(mesh.positions[index * 3 + 1])
                out.add(mesh.positions[index * 3 + 2])
                out.add(mesh.colors[index * 3])
                out.add(mesh.colors[index * 3 + 1])
                out.add(mesh.colors[index * 3 + 2])
            }
        }
        val array = FloatArray(out.size)
        for (index in out.indices) array[index] = out[index]
        return array
    }
}

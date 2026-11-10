package dev.instrum.ether.map.scene

import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Vec3

/**
 * Minimal material parameters for the map scene graph - replaces the removed `graphics-api`
 * `MaterialParameters` with only what the map pipeline consumes today.
 */
public class MaterialParameters(
    public val baseColor: FloatArray = floatArrayOf(0.8f, 0.8f, 0.8f, 1f),
    public val metallic: Float = 0f,
    public val roughness: Float = 0.8f,
) {
    override fun equals(other: Any?): Boolean =
        other is MaterialParameters &&
            baseColor.contentEquals(other.baseColor) &&
            metallic == other.metallic &&
            roughness == other.roughness

    override fun hashCode(): Int = 31 * baseColor.contentHashCode() + (metallic.hashCode() * 31 + roughness.hashCode())

    override fun toString(): String =
        "MaterialParameters(baseColor=${baseColor.contentToString()}, metallic=$metallic, roughness=$roughness)"
}

/** Hybrid hierarchy: objects with transforms, typed components and a per-frame flat scene. */
public interface SceneComponent {
    public val componentType: String
    public fun update(deltaSeconds: Float) {}
}

/** Position/orientation/scale of a scene object; rotation applies roll-pitch-yaw. */
public data class MapTransform(
    public val position: Vec3 = Vec3.ZERO,
    public val yawRadians: Float = 0f,
    public val pitchRadians: Float = 0f,
    public val rollRadians: Float = 0f,
    public val scale: Vec3 = Vec3(1f, 1f, 1f),
) {
    /** Rotates [position] around the object origin and applies the scale. */
    public fun rotated(position: Vec3): Vec3 {
        val cy = kotlin.math.cos(yawRadians); val sy = kotlin.math.sin(yawRadians)
        val cp = kotlin.math.cos(pitchRadians); val sp = kotlin.math.sin(pitchRadians)
        val cr = kotlin.math.cos(rollRadians); val sr = kotlin.math.sin(rollRadians)
        val x1 = position.x * cr - position.y * sr
        val y1 = position.x * sr + position.y * cr
        val z1 = position.z
        val y2 = y1 * cp - z1 * sp
        val z2 = y1 * sp + z1 * cp
        val x3 = x1 * cy + z2 * sy
        val z3 = -x1 * sy + z2 * cy
        return Vec3(x3 * scale.x, y2 * scale.y, z3 * scale.z)
    }

    public fun combine(parent: MapTransform): MapTransform {
        val worldPosition = parent.position + parent.rotated(position)
        return MapTransform(
            worldPosition,
            parent.yawRadians + yawRadians,
            parent.pitchRadians + pitchRadians,
            parent.rollRadians + rollRadians,
            Vec3(parent.scale.x * scale.x, parent.scale.y * scale.y, parent.scale.z * scale.z),
        )
    }
}

public class MeshComponent(public val mesh: dev.instrum.ether.map.geometry.Mesh) : SceneComponent {
    override val componentType: String
        get() = "mesh"
}

public class MaterialComponent(
    public val materialId: String,
    public val parameters: MaterialParameters = MaterialParameters(),
) : SceneComponent {
    override val componentType: String
        get() = "material"
}

public enum class LightKind { Directional, Point, Spot }

public class LightComponent(
    public val kind: LightKind,
    public val color: FloatArray = floatArrayOf(1f, 1f, 1f),
    public val intensity: Float = 1f,
    public val rangeMeters: Float = 100f,
) : SceneComponent {
    override val componentType: String
        get() = "light"

    override fun equals(other: Any?): Boolean =
        other is LightComponent &&
            kind == other.kind &&
            color.contentEquals(other.color) &&
            intensity == other.intensity &&
            rangeMeters == other.rangeMeters

    override fun hashCode(): Int = 31 * kind.hashCode() + color.contentHashCode()
}

public class BoundsComponent(public val bounds: Aabb) : SceneComponent {
    override val componentType: String
        get() = "bounds"
}

public class SceneObject(
    public val id: String,
    public val name: String = id,
    public var transform: MapTransform = MapTransform(),
    public var visible: Boolean = true,
    public var parent: SceneObject? = null,
) {
    public val children: MutableList<SceneObject> = mutableListOf()
    public val components: MutableList<SceneComponent> = mutableListOf()

    public var worldTransform: MapTransform = transform
        internal set

    public fun add(child: SceneObject): SceneObject {
        require(child !== this) { "ciclo na hierarquia" }
        require(child.parent == null) { "objeto já possui pai" }
        // A cycle appears whenever the new child is already above (an ancestor of) this object.
        var cursor: SceneObject? = this
        while (cursor != null) {
            require(cursor !== child) { "ciclo na hierarquia" }
            cursor = cursor.parent
        }
        child.parent = this
        children += child
        return this
    }

    public fun remove(child: SceneObject): Boolean {
        if (!children.remove(child)) return false
        child.parent = null
        return true
    }
}

/** Materialized renderable produced by [SceneGraph.renderables]. */
public data class RenderableObject(
    public val objectId: String,
    public val worldTransform: MapTransform,
    public val mesh: dev.instrum.ether.map.geometry.Mesh,
    public val material: MaterialComponent?,
    public val bounds: Aabb,
)

public class SceneGraph {
    private val objects = linkedMapOf<String, SceneObject>()
    public val roots: MutableList<SceneObject> = mutableListOf()

    public fun add(objects: Iterable<SceneObject>): SceneGraph {
        for (sceneObject in objects) add(sceneObject)
        return this
    }

    public fun add(sceneObject: SceneObject): SceneObject {
        require(sceneObject.id !in objects) { "objeto duplicado: ${sceneObject.id}" }
        objects[sceneObject.id] = sceneObject
        if (sceneObject.parent == null) roots += sceneObject
        return sceneObject
    }

    public fun remove(sceneObject: SceneObject): Boolean {
        sceneObject.parent?.remove(sceneObject)
        roots.remove(sceneObject)
        return objects.remove(sceneObject.id) != null
    }

    public fun find(id: String): SceneObject? = objects[id]

    public fun update(deltaSeconds: Float) {
        for (root in roots) updateObject(root, MapTransform(), deltaSeconds)
    }

    private fun updateObject(sceneObject: SceneObject, parentTransform: MapTransform, deltaSeconds: Float) {
        sceneObject.worldTransform = sceneObject.transform.combine(parentTransform)
        sceneObject.components.forEach { it.update(deltaSeconds) }
        sceneObject.children.forEach { updateObject(it, sceneObject.worldTransform, deltaSeconds) }
    }

    public fun renderables(): List<RenderableObject> = buildList {
        fun visit(sceneObject: SceneObject) {
            if (!sceneObject.visible) return
            val mesh = sceneObject.components.filterIsInstance<MeshComponent>().firstOrNull()?.mesh
            if (mesh != null) {
                val bounds = sceneObject.components.filterIsInstance<BoundsComponent>().firstOrNull()?.bounds
                    ?: mesh.bounds
                add(
                    RenderableObject(
                        sceneObject.id,
                        sceneObject.worldTransform,
                        mesh,
                        sceneObject.components.filterIsInstance<MaterialComponent>().firstOrNull(),
                        bounds,
                    ),
                )
            }
            sceneObject.children.forEach { visit(it) }
        }
        roots.forEach { visit(it) }
    }

    public fun allObjects(): List<SceneObject> = objects.values.toList()
}

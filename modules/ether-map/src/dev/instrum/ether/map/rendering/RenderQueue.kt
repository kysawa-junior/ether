package dev.instrum.ether.map.rendering

import dev.instrum.ether.map.camera.Camera
import dev.instrum.ether.map.camera.FrustumExtractor
import dev.instrum.ether.map.geometry.Mesh
import dev.instrum.ether.map.scene.MapTransform
import dev.instrum.ether.math.Aabb

public enum class RenderLayer { Opaque, Transparent }

public data class RenderItem(
    public val id: String,
    public val mesh: Mesh,
    public val worldBounds: Aabb,
    public val pipelineId: String,
    public val materialId: String,
    public val layer: RenderLayer = RenderLayer.Opaque,
    public val transform: MapTransform = MapTransform(),
    public val castShadow: Boolean = true,
)

public data class DrawBatch(
    public val pipelineId: String,
    public val materialId: String,
    public val mesh: Mesh,
    public val instances: List<MapTransform>,
    public val layer: RenderLayer,
)

public data class RenderPlan(
    public val opaque: List<DrawBatch>,
    public val transparent: List<DrawBatch>,
    public val depthPrepass: List<DrawBatch>,
    public val culledItems: Int,
    public val drawCalls: Int,
    public val instanceCount: Int,
)

/**
 * Frustum-culling draw list.
 *
 * Divergence from the removed engine: the unused `InstanceBatch` (no consumer anywhere) and the
 * decorative `CullMode` parameter (which clashed with `ether-render-api.CullMode` and changed no
 * behavior) are dropped; culling here is strictly frustum based.
 */
public class RenderQueue {
    private val items = mutableListOf<RenderItem>()

    public fun submit(item: RenderItem) {
        require(item.id.isNotBlank()) { "render item sem id" }
        items += item
    }

    public fun clear() {
        items.clear()
    }

    public fun buildPlan(
        camera: Camera,
        aspect: Float,
        depthPrepass: Boolean = false,
        enableInstancing: Boolean = true,
    ): RenderPlan {
        val frustum = FrustumExtractor.extract(camera.viewProjection(aspect))
        var culled = 0
        val visible = items.filter { item ->
            val inside = frustum.intersectsBox(item.worldBounds.min, item.worldBounds.max)
            if (!inside) culled++
            inside
        }
        val grouped = visible.groupBy { Triple(it.pipelineId, it.materialId, it.mesh) }
        val batches = grouped.map { (key, group) ->
            val first = group.first()
            DrawBatch(
                key.first, key.second, key.third,
                if (enableInstancing) group.map { it.transform } else listOf(first.transform),
                first.layer,
            )
        }
        val opaque = batches.filter { it.layer == RenderLayer.Opaque }.sortedBy { it.pipelineId }
        val transparent = batches.filter { it.layer == RenderLayer.Transparent }.sortedBy { it.materialId }
        val prepass = if (depthPrepass) opaque.filter { it.instances.isNotEmpty() } else emptyList()
        return RenderPlan(
            opaque = opaque,
            transparent = transparent,
            depthPrepass = prepass,
            culledItems = culled,
            drawCalls = opaque.size + transparent.size + prepass.size,
            instanceCount = batches.sumOf { it.instances.size },
        )
    }
}

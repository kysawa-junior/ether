package dev.instrum.ether.map.runtime

import dev.instrum.ether.map.scene.FleetSystem
import dev.instrum.ether.map.scene.OrbitCamera
import dev.instrum.ether.map.scene.Raycast
import dev.instrum.ether.map.scene.Scene
import dev.instrum.ether.map.scene.SceneEntity
import dev.instrum.ether.map.geometry.Mesh
import dev.instrum.ether.map.rendering.MapFrameRenderer
import dev.instrum.ether.map.rendering.RenderItem
import dev.instrum.ether.map.rendering.RenderQueue
import dev.instrum.ether.math.Mat4

/** One rendered map frame: resolved mode, camera matrix and the culling plan. */
public data class MapFrameResult(
    public val mode: MapMode,
    public val viewProjection: Mat4,
    public val plan: dev.instrum.ether.map.rendering.RenderPlan,
)

/**
 * Map runtime: owns the camera, the scene, the fleet and the draw queue; it does not know the UI
 * framework.
 *
 * Divergence from the removed engine: the renderer is injected through [MapFrameRenderer] instead
 * of a built-in Vulkan rasterizer, and a frame no longer produces CPU pixels - it produces the
 * [MapFrameResult] consumed by the render HAL and by tests.
 */
public class MapRuntime(
    public val camera: OrbitCamera = OrbitCamera(),
    private val renderer: MapFrameRenderer,
) : AutoCloseable {
    private val queue = RenderQueue()
    public var scene: Scene? = null
        private set
    public var fleet: FleetSystem? = null
        private set
    private var initialized = false
    private var lastViewProjection: Mat4? = null

    /** Submit queue fed by the scene entities (paint order preserved). */
    public val renderQueue: RenderQueue
        get() = queue

    public fun loadScene(loaded: Scene) {
        check(!initialized) { "runtime já inicializado" }
        renderer.uploadScene(loaded)
        camera.fit(loaded.worldBounds, instant = true)
        queue.clear()
        for (entity in loaded.entities) {
            queue.submit(
                RenderItem(
                    id = entity.id,
                    mesh = Mesh(entity.trianglesWorld, entity.indices, bounds = entity.bounds),
                    worldBounds = entity.bounds,
                    pipelineId = "ether-map.scene",
                    materialId = entity.kind.name.lowercase(),
                ),
            )
        }
        scene = loaded
        fleet = FleetSystem(loaded)
        initialized = true
    }

    public fun resize(width: Int, height: Int) {
        renderer.resize(width, height)
    }

    /** Advances the camera and the fleet, culls, and records the frame in the renderer. */
    public fun frame(deltaSeconds: Float): MapFrameResult {
        check(initialized) { "runtime não inicializado" }
        camera.tick(deltaSeconds)
        fleet?.let { renderer.uploadDynamic(it.tick(deltaSeconds)) }
        val aspect = renderer.renderWidth.toFloat() / renderer.renderHeight
        val viewProjection = camera.viewProjection(aspect)
        lastViewProjection = viewProjection
        val plan = queue.buildPlan(camera.camera(aspect), aspect)
        renderer.render(viewProjection)
        return MapFrameResult(camera.mode, viewProjection, plan)
    }

    /** CPU picking of the scene entity under a screen position, using the last frame matrix. */
    public fun pick(pixelX: Float, pixelY: Float): SceneEntity? {
        val currentScene = scene
        val viewProjection = lastViewProjection
        if (!initialized || currentScene == null || viewProjection == null) return null
        return Raycast.pick(
            currentScene, viewProjection, camera.position,
            pixelX, pixelY, renderer.renderWidth, renderer.renderHeight,
        )
    }

    override fun close() {
        fleet = null
        scene = null
        initialized = false
        lastViewProjection = null
        renderer.close()
    }
}

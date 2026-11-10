package dev.instrum.ether.map.rendering

import dev.instrum.ether.map.scene.Scene
import dev.instrum.ether.math.Mat4

/**
 * Frame renderer abstraction owned by the map runtime.
 *
 * The real rasterization stays in the render HAL (ether-render-*); this interface keeps the
 * runtime testable with recording backends (for instance `ether-render-null`).
 */
public interface MapFrameRenderer : AutoCloseable {
    public val renderWidth: Int
    public val renderHeight: Int

    /** Uploads the static scene buffers (interleaved position+color vertices and indices). */
    public fun uploadScene(scene: Scene)

    /** Uploads the dynamic triangle soup (non-indexed, interleaved position+color). */
    public fun uploadDynamic(mesh: FloatArray)

    public fun resize(width: Int, height: Int)

    /**
     * Renders one frame with the given view-projection matrix.
     *
     * The matrix also stays available for CPU picking and frustum culling; uploading it to a GPU
     * uniform buffer is part of the render HAL milestone.
     */
    public fun render(viewProjection: Mat4)
}

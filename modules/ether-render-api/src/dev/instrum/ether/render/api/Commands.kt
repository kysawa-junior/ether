package dev.instrum.ether.render.api

/**
 * A portable command recorded by the render core and consumed by a backend.
 */
public sealed interface RenderCommand {
    /** Starts a render pass. */
    public data class BeginRenderPass(
        public val descriptor: RenderPassDescriptor,
    ) : RenderCommand

    /** Ends the current render pass. */
    public data object EndRenderPass : RenderCommand

    /** Binds a render pipeline. */
    public data class SetPipeline(
        public val pipeline: RenderPipelineHandle,
    ) : RenderCommand

    /** Binds a vertex buffer to [slot]. */
    public data class SetVertexBuffer(
        public val slot: Int,
        public val buffer: BufferHandle,
        public val offsetBytes: Long = 0L,
    ) : RenderCommand {
        init {
            require(slot >= 0) { "Vertex buffer slot must not be negative" }
            require(offsetBytes >= 0L) { "Vertex buffer offset must not be negative" }
        }
    }

    /** Binds an index buffer. */
    public data class SetIndexBuffer(
        public val buffer: BufferHandle,
        public val format: IndexFormat,
        public val offsetBytes: Long = 0L,
    ) : RenderCommand {
        init {
            require(offsetBytes >= 0L) { "Index buffer offset must not be negative" }
        }
    }

    /** Sets the viewport. */
    public data class SetViewport(
        public val viewport: Viewport,
    ) : RenderCommand

    /** Sets the scissor rectangle. */
    public data class SetScissor(
        public val scissor: ScissorRect,
    ) : RenderCommand

    /** Draws non-indexed geometry. */
    public data class Draw(
        public val vertexCount: Int,
        public val instanceCount: Int = 1,
        public val firstVertex: Int = 0,
        public val firstInstance: Int = 0,
    ) : RenderCommand {
        init {
            require(vertexCount > 0) { "Vertex count must be positive" }
            require(instanceCount > 0) { "Instance count must be positive" }
            require(firstVertex >= 0) { "First vertex must not be negative" }
            require(firstInstance >= 0) { "First instance must not be negative" }
        }
    }

    /** Draws indexed geometry. */
    public data class DrawIndexed(
        public val indexCount: Int,
        public val instanceCount: Int = 1,
        public val firstIndex: Int = 0,
        public val baseVertex: Int = 0,
        public val firstInstance: Int = 0,
    ) : RenderCommand {
        init {
            require(indexCount > 0) { "Index count must be positive" }
            require(instanceCount > 0) { "Instance count must be positive" }
            require(firstIndex >= 0) { "First index must not be negative" }
            require(firstInstance >= 0) { "First instance must not be negative" }
        }
    }

    /**
     * Copies a rectangular region from a buffer to a color texture mip level.
     *
     * @property buffer source buffer; must have [BufferUsage.CopySource].
     * @property bufferOffsetBytes offset of the first texel row.
     * @property bytesPerRow row pitch in the source buffer.
     * @property texture destination texture; must have [TextureUsage.CopyDestination].
     * @property mipLevel destination mip level.
     * @property originX destination X origin.
     * @property originY destination Y origin.
     * @property originZ destination Z origin or array layer.
     * @property width copy width in texels.
     * @property height copy height in texels.
     * @property depth copy depth in texels or layers.
     */
    public data class CopyBufferToTexture(
        public val buffer: BufferHandle,
        public val bufferOffsetBytes: Long,
        public val bytesPerRow: Int,
        public val texture: TextureHandle,
        public val mipLevel: Int = 0,
        public val originX: Int = 0,
        public val originY: Int = 0,
        public val originZ: Int = 0,
        public val width: Int,
        public val height: Int = 1,
        public val depth: Int = 1,
    ) : RenderCommand {
        init {
            require(bufferOffsetBytes >= 0L) { "Buffer offset must not be negative" }
            require(bytesPerRow > 0) { "Bytes per row must be positive" }
            require(mipLevel >= 0) { "Mip level must not be negative" }
            require(originX >= 0 && originY >= 0 && originZ >= 0) {
                "Copy origin must not be negative"
            }
            require(width > 0 && height > 0 && depth > 0) { "Copy size must be positive" }
        }
    }
}

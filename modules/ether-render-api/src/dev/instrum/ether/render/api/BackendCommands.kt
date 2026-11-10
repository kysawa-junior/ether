package dev.instrum.ether.render.api

/**
 * A command translated from portable handles to backend tokens.
 *
 * Backends consume [BackendCommand] lists, not [RenderCommand] lists, so that a backend never needs
 * to understand the core's handle tables. The core performs the translation after validating every
 * referenced resource.
 */
public sealed interface BackendCommand {
    /** Starts a render pass. */
    public data class BeginRenderPass(
        public val descriptor: BackendRenderPassDescriptor,
    ) : BackendCommand

    /** Ends the current render pass. */
    public data object EndRenderPass : BackendCommand

    /** Binds a render pipeline. */
    public data class SetPipeline(
        public val pipeline: Long,
    ) : BackendCommand

    /** Binds a vertex buffer to [slot]. */
    public data class SetVertexBuffer(
        public val slot: Int,
        public val buffer: Long,
        public val offsetBytes: Long = 0L,
    ) : BackendCommand

    /** Binds an index buffer. */
    public data class SetIndexBuffer(
        public val buffer: Long,
        public val format: IndexFormat,
        public val offsetBytes: Long = 0L,
    ) : BackendCommand

    /** Sets the viewport. */
    public data class SetViewport(
        public val viewport: Viewport,
    ) : BackendCommand

    /** Sets the scissor rectangle. */
    public data class SetScissor(
        public val scissor: ScissorRect,
    ) : BackendCommand

    /** Draws non-indexed geometry. */
    public data class Draw(
        public val vertexCount: Int,
        public val instanceCount: Int = 1,
        public val firstVertex: Int = 0,
        public val firstInstance: Int = 0,
    ) : BackendCommand

    /** Draws indexed geometry. */
    public data class DrawIndexed(
        public val indexCount: Int,
        public val instanceCount: Int = 1,
        public val firstIndex: Int = 0,
        public val baseVertex: Int = 0,
        public val firstInstance: Int = 0,
    ) : BackendCommand

    /** Copies a buffer region into a texture mip level. */
    public data class CopyBufferToTexture(
        public val buffer: Long,
        public val bufferOffsetBytes: Long,
        public val bytesPerRow: Int,
        public val texture: Long,
        public val mipLevel: Int,
        public val originX: Int,
        public val originY: Int,
        public val originZ: Int,
        public val width: Int,
        public val height: Int,
        public val depth: Int,
    ) : BackendCommand
}

/**
 * A render pass whose attachments are backend tokens.
 */
public data class BackendRenderPassDescriptor(
    public val label: String? = null,
    public val colorAttachments: List<BackendColorAttachment> = emptyList(),
    public val depthStencilAttachment: BackendDepthStencilAttachment? = null,
)

/**
 * A color attachment bound to a backend texture token.
 */
public data class BackendColorAttachment(
    public val texture: Long,
    public val resolveTarget: Long? = null,
    public val load: LoadOperation = LoadOperation.Load,
    public val store: StoreOp = StoreOp.Store,
)

/**
 * A depth/stencil attachment bound to a backend texture token.
 */
public data class BackendDepthStencilAttachment(
    public val texture: Long,
    public val depthLoad: DepthLoadOperation = DepthLoadOperation.Load,
    public val depthStore: StoreOp = StoreOp.Store,
)

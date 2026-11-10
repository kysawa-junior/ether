package dev.instrum.ether.render.api

/**
 * Floating-point RGBA clear color. Values are not clamped; HDR targets may exceed one.
 */
public data class ClearColor(
    public val red: Float,
    public val green: Float,
    public val blue: Float,
    public val alpha: Float,
) {
    public companion object {
        /** Opaque black. */
        public val Black: ClearColor = ClearColor(0f, 0f, 0f, 1f)

        /** Fully transparent black. */
        public val Transparent: ClearColor = ClearColor(0f, 0f, 0f, 0f)
    }
}

/**
 * How a color attachment is initialized at the start of a render pass.
 */
public sealed interface LoadOperation {
    /** Preserve the existing attachment contents. */
    public data object Load : LoadOperation

    /** Discard the existing contents. */
    public data object DontCare : LoadOperation

    /** Clear the attachment to [color]. */
    public data class Clear(val color: ClearColor) : LoadOperation
}

/**
 * How a depth attachment is initialized at the start of a render pass.
 */
public sealed interface DepthLoadOperation {
    /** Preserve the existing depth contents. */
    public data object Load : DepthLoadOperation

    /** Discard the existing contents. */
    public data object DontCare : DepthLoadOperation

    /** Clear depth to [depth], which must be in `[0, 1]`. */
    public data class Clear(val depth: Float) : DepthLoadOperation {
        init {
            require(depth in 0f..1f) { "Clear depth must be in [0, 1], was $depth" }
        }
    }
}

/**
 * Whether an attachment's contents are preserved after a render pass.
 */
public enum class StoreOp {
    /** Write the attachment contents back to memory. */
    Store,

    /** Discard the attachment contents. */
    Discard,
}

/**
 * Color attachment binding for a render pass.
 *
 * @property texture the color texture; must have [TextureUsage.RenderTarget].
 * @property resolveTarget optional single-sample resolve destination.
 * @property load how the attachment is initialized.
 * @property store whether contents are preserved.
 */
public data class RenderPassColorAttachment(
    public val texture: TextureHandle,
    public val resolveTarget: TextureHandle? = null,
    public val load: LoadOperation = LoadOperation.Load,
    public val store: StoreOp = StoreOp.Store,
)

/**
 * Depth attachment binding for a render pass.
 *
 * @property texture the depth texture; must have [TextureUsage.DepthStencil].
 * @property depthLoad how depth is initialized.
 * @property depthStore whether depth contents are preserved.
 */
public data class RenderPassDepthStencilAttachment(
    public val texture: TextureHandle,
    public val depthLoad: DepthLoadOperation = DepthLoadOperation.Load,
    public val depthStore: StoreOp = StoreOp.Store,
)

/**
 * Describes one render pass.
 *
 * @property label an optional debug label.
 * @property colorAttachments color attachments, in target order.
 * @property depthStencilAttachment optional depth attachment.
 */
public data class RenderPassDescriptor(
    public val label: String? = null,
    public val colorAttachments: List<RenderPassColorAttachment> = emptyList(),
    public val depthStencilAttachment: RenderPassDepthStencilAttachment? = null,
) {
    init {
        require(colorAttachments.isNotEmpty() || depthStencilAttachment != null) {
            "A render pass must have at least one attachment"
        }

        val handles = buildList {
            colorAttachments.forEach { attachment ->
                add(attachment.texture)
                attachment.resolveTarget?.let(::add)
            }
            depthStencilAttachment?.let { add(it.texture) }
        }
        require(handles.size == handles.toSet().size) {
            "A render pass cannot bind the same texture more than once"
        }
    }
}

/**
 * Floating-point viewport.
 *
 * @property x horizontal origin.
 * @property y vertical origin.
 * @property width viewport width; must be positive.
 * @property height viewport height; must be positive.
 * @property minDepth minimum depth value in `[0, 1]`.
 * @property maxDepth maximum depth value in `[0, 1]`.
 */
public data class Viewport(
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
    public val minDepth: Float = 0f,
    public val maxDepth: Float = 1f,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Viewport origin must be finite" }
        require(width.isFinite() && width > 0f) { "Viewport width must be finite and positive" }
        require(height.isFinite() && height > 0f) { "Viewport height must be finite and positive" }
        require(minDepth in 0f..1f) { "Viewport minDepth must be in [0, 1]" }
        require(maxDepth in 0f..1f) { "Viewport maxDepth must be in [0, 1]" }
        require(maxDepth >= minDepth) { "Viewport maxDepth must not be smaller than minDepth" }
    }
}

/**
 * Integer scissor rectangle.
 *
 * @property x horizontal origin; must not be negative.
 * @property y vertical origin; must not be negative.
 * @property width rectangle width; must be positive.
 * @property height rectangle height; must be positive.
 */
public data class ScissorRect(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        require(x >= 0 && y >= 0) { "Scissor origin must not be negative" }
        require(width > 0 && height > 0) { "Scissor size must be positive" }
    }
}

package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.core.error.RenderGraphException

/** One declared pass in a [RenderGraph]. */
internal class RenderGraphPass(
    val name: String,
    val descriptor: RenderPassDescriptor,
    val reads: Set<TextureHandle>,
    val record: (CommandEncoder) -> Unit,
)

/**
 * A linear render graph: an ordered list of passes with explicit texture reads and writes.
 *
 * The first implementation executes passes in declaration order. It validates that every read is
 * either imported or produced by an earlier pass, that a pass does not read a texture it is
 * currently writing, and that pass names are unique. Transient allocation, pass culling, and barrier
 * derivation are not implemented yet.
 *
 * Create graphs with [RenderGraphBuilder].
 */
public class RenderGraph internal constructor(
    /** Debug name of the graph. */
    public val name: String,
    private val ownerDeviceId: Long,
    internal val passes: List<RenderGraphPass>,
) {
    /** The pass names in execution order. */
    public val passNames: List<String>
        get() = passes.map { it.name }

    /** Number of passes in the graph. */
    public val passCount: Int
        get() = passes.size

    /**
     * Records every pass into [encoder] in declaration order.
     *
     * @throws RenderGraphException if the encoder belongs to a different device.
     */
    public fun execute(encoder: CommandEncoder) {
        if (encoder.ownerDeviceId != ownerDeviceId) {
            throw RenderGraphException("Render graph '$name' cannot execute on a different device")
        }
        passes.forEach { pass ->
            encoder.beginRenderPass(pass.descriptor)
            pass.record(encoder)
            encoder.endRenderPass()
        }
    }

    /**
     * Records the graph into a new command buffer on [device].
     */
    public fun encode(device: GraphicsDevice): CommandBuffer {
        val encoder = device.createCommandEncoder("graph:$name")
        execute(encoder)
        return encoder.finish()
    }
}

/**
 * Builds a linear [RenderGraph] for one device.
 *
 * A builder is single-use. Texture handles are borrowed; the graph never destroys them.
 */
public class RenderGraphBuilder internal constructor(
    private val device: GraphicsDevice,
    private val name: String,
) {
    private val passes: MutableList<RenderGraphPass> = ArrayList()
    private val importedTextures: MutableSet<TextureHandle> = LinkedHashSet()
    private val producedTextures: MutableSet<TextureHandle> = LinkedHashSet()
    private val passNames: MutableSet<String> = LinkedHashSet()
    private var built: Boolean = false

    /**
     * Marks [texture] as an external resource that any pass may read.
     */
    public fun importTexture(texture: TextureHandle): RenderGraphBuilder {
        ensureBuilding()
        device.resolveTexture(texture)
        importedTextures += texture
        return this
    }

    /**
     * Adds a pass.
     *
     * Attachment textures are treated as writes. [reads] must be imported or produced by an
     * earlier pass. [record] runs while the pass is active during [RenderGraph.execute].
     *
     * @throws RenderGraphException for duplicate pass names, unknown read dependencies, or
     * feedback loops where a pass reads a texture it also writes.
     */
    public fun pass(
        name: String,
        descriptor: RenderPassDescriptor,
        reads: Set<TextureHandle> = emptySet(),
        record: (CommandEncoder) -> Unit = {},
    ): RenderGraphBuilder {
        ensureBuilding()
        if (name.isBlank()) {
            throw RenderGraphException("Render graph pass names must not be blank")
        }
        if (name in passNames) {
            throw RenderGraphException("Render graph '$name' already has a pass named '$name'")
        }
        if (descriptor.colorAttachments.size > device.capabilities.limits.maxColorAttachments) {
            throw RenderGraphException(
                "Pass '$name' has ${descriptor.colorAttachments.size} color attachments, " +
                    "maxColorAttachments is ${device.capabilities.limits.maxColorAttachments}",
            )
        }

        val writes = buildSet {
            descriptor.colorAttachments.forEach { attachment ->
                add(attachment.texture)
                attachment.resolveTarget?.let(::add)
            }
            descriptor.depthStencilAttachment?.let { add(it.texture) }
        }
        writes.forEach { texture -> device.resolveTexture(texture) }

        reads.forEach { texture ->
            device.resolveTexture(texture)
            if (texture in writes) {
                throw RenderGraphException(
                    "Pass '$name' reads texture it also writes; feedback loops are not allowed",
                )
            }
            if (texture !in importedTextures && texture !in producedTextures) {
                throw RenderGraphException(
                    "Pass '$name' reads a texture that is neither imported nor produced by an " +
                        "earlier pass",
                )
            }
        }

        passes += RenderGraphPass(name, descriptor, reads, record)
        passNames += name
        producedTextures += writes
        return this
    }

    /**
     * Finishes the graph.
     *
     * @throws RenderGraphException if the graph has no passes or [build] was already called.
     */
    public fun build(): RenderGraph {
        ensureBuilding()
        if (passes.isEmpty()) {
            throw RenderGraphException("Render graph '$name' has no passes")
        }
        built = true
        return RenderGraph(name, device.id, passes.toList())
    }

    private fun ensureBuilding() {
        if (built) {
            throw RenderGraphException("Render graph '$name' has already been built")
        }
    }
}

/**
 * Creates a render graph builder for [device].
 */
public fun GraphicsDevice.createRenderGraph(
    name: String,
    configure: RenderGraphBuilder.() -> Unit,
): RenderGraph {
    val builder = RenderGraphBuilder(this, name)
    builder.configure()
    return builder.build()
}

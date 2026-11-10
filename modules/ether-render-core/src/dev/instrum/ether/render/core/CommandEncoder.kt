package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BufferHandle
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.IndexFormat
import dev.instrum.ether.render.api.RenderCommand
import dev.instrum.ether.render.api.RenderPassColorAttachment
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.RenderPassDepthStencilAttachment
import dev.instrum.ether.render.api.RenderPipelineHandle
import dev.instrum.ether.render.api.ScissorRect
import dev.instrum.ether.render.api.TextureDimension
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.api.VertexStepMode
import dev.instrum.ether.render.api.Viewport
import dev.instrum.ether.render.core.error.RenderValidationException

/**
 * Records validated render commands for one device.
 *
 * An encoder is single-use and single-threaded. Every method validates handle liveness, resource
 * usage, capability support, pass state, and buffer ranges before appending a command. Invalid
 * recordings fail immediately instead of reaching a backend.
 */
public class CommandEncoder internal constructor(
    private val device: GraphicsDevice,
    /** Optional debug label carried into the finished [CommandBuffer]. */
    public val label: String? = null,
) {
    internal val ownerDeviceId: Long
        get() = device.id
    private class BoundBuffer(
        val resource: BufferResource,
        val offsetBytes: Long,
        val indexFormat: IndexFormat? = null,
    )

    private data class ActivePass(
        val descriptor: RenderPassDescriptor,
        val colorFormats: List<TextureFormat>,
        val depthFormat: TextureFormat?,
        val sampleCount: Int,
        val width: Int,
        val height: Int,
    )

    private val commands: MutableList<RenderCommand> = ArrayList()
    private var finished: Boolean = false
    private var activePass: ActivePass? = null
    private var pipeline: RenderPipelineResource? = null
    private val vertexBuffers: MutableMap<Int, BoundBuffer> = LinkedHashMap()
    private var indexBuffer: BoundBuffer? = null
    private var drawCalls: Int = 0

    /** Whether [finish] has been called. */
    public val isFinished: Boolean
        get() = finished

    /**
     * Begins a render pass.
     *
     * @throws RenderValidationException if a pass is already active or the attachments violate
     * usage, format, sample-count, dimension, or capability rules.
     */
    public fun beginRenderPass(descriptor: RenderPassDescriptor) {
        ensureRecording()
        if (activePass != null) {
            throw RenderValidationException("Cannot begin a render pass while another pass is active")
        }

        val capabilities = device.capabilities
        val limits = capabilities.limits
        if (descriptor.colorAttachments.size > limits.maxColorAttachments) {
            throw RenderValidationException(
                "Render pass has ${descriptor.colorAttachments.size} color attachments, " +
                    "maxColorAttachments is ${limits.maxColorAttachments}",
            )
        }

        val colorTextures = descriptor.colorAttachments.map { attachment ->
            validateColorAttachment(attachment)
        }
        val depthTexture = descriptor.depthStencilAttachment?.let { attachment ->
            validateDepthAttachment(attachment)
        }

        val allTextures = colorTextures + listOfNotNull(depthTexture)
        val first = allTextures.first()
        val sampleCount = first.descriptor.sampleCount
        val width = first.descriptor.width
        val height = first.descriptor.height
        allTextures.forEach { texture ->
            val textureDescriptor = texture.descriptor
            if (textureDescriptor.sampleCount != sampleCount) {
                throw RenderValidationException(
                    "All render pass attachments must use the same sample count; found " +
                        "${textureDescriptor.sampleCount} and $sampleCount",
                )
            }
            if (textureDescriptor.width != width || textureDescriptor.height != height) {
                throw RenderValidationException(
                    "All render pass attachments must have the same dimensions; found " +
                        "${textureDescriptor.width}x${textureDescriptor.height} and ${width}x$height",
                )
            }
        }

        val colorFormats = colorTextures.map { it.descriptor.format }
        activePass = ActivePass(
            descriptor = descriptor,
            colorFormats = colorFormats,
            depthFormat = depthTexture?.descriptor?.format,
            sampleCount = sampleCount,
            width = width,
            height = height,
        )
        commands += RenderCommand.BeginRenderPass(descriptor)
    }

    /**
     * Ends the active render pass.
     */
    public fun endRenderPass() {
        ensureRecording()
        ensureInPass("end a render pass")
        activePass = null
        pipeline = null
        vertexBuffers.clear()
        indexBuffer = null
        commands += RenderCommand.EndRenderPass
    }

    /**
     * Binds a render pipeline inside the active pass.
     */
    public fun setPipeline(pipelineHandle: RenderPipelineHandle) {
        ensureRecording()
        val pass = ensureInPass("bind a pipeline")
        val resource = device.resolveRenderPipeline(pipelineHandle)
        val descriptor = resource.descriptor

        val pipelineTargets = descriptor.fragment?.targets.orEmpty()
        if (descriptor.fragment == null && pass.colorFormats.isNotEmpty()) {
            throw RenderValidationException(
                "Pipeline '${descriptor.label ?: "<unlabelled>"}' has no fragment stage but " +
                    "the pass has ${pass.colorFormats.size} color attachment(s)",
            )
        }
        if (pipelineTargets.size != pass.colorFormats.size) {
            throw RenderValidationException(
                "Pipeline declares ${pipelineTargets.size} color targets but the pass has " +
                    "${pass.colorFormats.size} color attachments",
            )
        }
        pipelineTargets.forEachIndexed { index, target ->
            if (target.format != pass.colorFormats[index]) {
                throw RenderValidationException(
                    "Pipeline color target $index format ${target.format} does not match pass " +
                        "attachment format ${pass.colorFormats[index]}",
                )
            }
        }

        val pipelineDepth = descriptor.depthStencil
        if (pass.depthFormat == null && pipelineDepth != null) {
            throw RenderValidationException(
                "Pipeline declares depth format ${pipelineDepth.format} but the pass has no " +
                    "depth attachment",
            )
        }
        if (pass.depthFormat != null && pipelineDepth == null) {
            throw RenderValidationException(
                "Pass has depth format ${pass.depthFormat} but the pipeline declares no depth state",
            )
        }
        if (pass.depthFormat != null && pipelineDepth != null &&
            pipelineDepth.format != pass.depthFormat
        ) {
            throw RenderValidationException(
                "Pipeline depth format ${pipelineDepth.format} does not match pass depth format " +
                    pass.depthFormat,
            )
        }
        if (descriptor.multisample.count != pass.sampleCount) {
            throw RenderValidationException(
                "Pipeline sample count ${descriptor.multisample.count} does not match pass sample " +
                    "count ${pass.sampleCount}",
            )
        }

        val boundIndexFormat = indexBuffer?.indexFormat
        val stripFormat = descriptor.primitive.stripIndexFormat
        if (stripFormat != null && boundIndexFormat != null && boundIndexFormat != stripFormat) {
            throw RenderValidationException(
                "Bound index format $boundIndexFormat does not match pipeline stripIndexFormat " +
                    stripFormat,
            )
        }

        this.pipeline = resource
        commands += RenderCommand.SetPipeline(pipelineHandle)
    }

    /**
     * Binds a vertex buffer to [slot].
     */
    public fun setVertexBuffer(
        slot: Int,
        buffer: BufferHandle,
        offsetBytes: Long = 0L,
    ) {
        ensureRecording()
        ensureInPass("bind a vertex buffer")
        val limits = device.capabilities.limits
        if (slot < 0 || slot >= limits.maxVertexBuffers) {
            throw RenderValidationException(
                "Vertex buffer slot $slot is outside [0, ${limits.maxVertexBuffers})",
            )
        }
        val resource = device.resolveBuffer(buffer)
        requireUsage(resource, BufferUsage.Vertex, "vertex buffer")
        if (offsetBytes < 0L || offsetBytes >= resource.descriptor.sizeBytes) {
            throw RenderValidationException(
                "Vertex buffer offset $offsetBytes is outside buffer size " +
                    resource.descriptor.sizeBytes,
            )
        }
        vertexBuffers[slot] = BoundBuffer(resource, offsetBytes)
        commands += RenderCommand.SetVertexBuffer(slot, buffer, offsetBytes)
    }

    /**
     * Binds an index buffer.
     */
    public fun setIndexBuffer(
        buffer: BufferHandle,
        format: IndexFormat,
        offsetBytes: Long = 0L,
    ) {
        ensureRecording()
        ensureInPass("bind an index buffer")
        val resource = device.resolveBuffer(buffer)
        requireUsage(resource, BufferUsage.Index, "index buffer")
        if (offsetBytes < 0L || offsetBytes >= resource.descriptor.sizeBytes) {
            throw RenderValidationException(
                "Index buffer offset $offsetBytes is outside buffer size " +
                    resource.descriptor.sizeBytes,
            )
        }
        val stripFormat = pipeline?.descriptor?.primitive?.stripIndexFormat
        if (stripFormat != null && stripFormat != format) {
            throw RenderValidationException(
                "Index format $format does not match pipeline stripIndexFormat $stripFormat",
            )
        }
        indexBuffer = BoundBuffer(resource, offsetBytes, format)
        commands += RenderCommand.SetIndexBuffer(buffer, format, offsetBytes)
    }

    /**
     * Sets the viewport. The rectangle must fit inside the active pass attachments.
     */
    public fun setViewport(viewport: Viewport) {
        ensureRecording()
        val pass = ensureInPass("set a viewport")
        if (viewport.x < 0f || viewport.y < 0f ||
            viewport.x + viewport.width > pass.width.toFloat() ||
            viewport.y + viewport.height > pass.height.toFloat()
        ) {
            throw RenderValidationException(
                "Viewport ${viewport.x},${viewport.y} ${viewport.width}x${viewport.height} does " +
                    "not fit inside ${pass.width}x${pass.height}",
            )
        }
        commands += RenderCommand.SetViewport(viewport)
    }

    /**
     * Sets the scissor rectangle. The rectangle must fit inside the active pass attachments.
     */
    public fun setScissor(scissor: ScissorRect) {
        ensureRecording()
        val pass = ensureInPass("set a scissor rectangle")
        if (scissor.x + scissor.width > pass.width || scissor.y + scissor.height > pass.height) {
            throw RenderValidationException(
                "Scissor ${scissor.x},${scissor.y} ${scissor.width}x${scissor.height} does not " +
                    "fit inside ${pass.width}x${pass.height}",
            )
        }
        commands += RenderCommand.SetScissor(scissor)
    }

    /**
     * Records a non-indexed draw.
     */
    public fun draw(
        vertexCount: Int,
        instanceCount: Int = 1,
        firstVertex: Int = 0,
        firstInstance: Int = 0,
    ) {
        ensureRecording()
        if (vertexCount <= 0) {
            throw RenderValidationException("Vertex count must be positive")
        }
        if (instanceCount <= 0) {
            throw RenderValidationException("Instance count must be positive")
        }
        if (firstVertex < 0 || firstInstance < 0) {
            throw RenderValidationException("Draw offsets must not be negative")
        }
        val activePipeline = ensurePipelineForDraw()
        if (instanceCount > 1) {
            requireFeature(GraphicsFeature.InstancedDrawing, "Instanced drawing")
        }
        validateDrawBudget()
        validateVertexBindings(
            activePipeline = activePipeline,
            firstVertex = firstVertex,
            vertexCount = vertexCount,
            firstInstance = firstInstance,
            instanceCount = instanceCount,
            indexed = false,
        )
        commands += RenderCommand.Draw(vertexCount, instanceCount, firstVertex, firstInstance)
        drawCalls++
    }

    /**
     * Records an indexed draw.
     */
    public fun drawIndexed(
        indexCount: Int,
        instanceCount: Int = 1,
        firstIndex: Int = 0,
        baseVertex: Int = 0,
        firstInstance: Int = 0,
    ) {
        ensureRecording()
        if (indexCount <= 0) {
            throw RenderValidationException("Index count must be positive")
        }
        if (instanceCount <= 0) {
            throw RenderValidationException("Instance count must be positive")
        }
        if (firstIndex < 0 || firstInstance < 0) {
            throw RenderValidationException("Indexed draw offsets must not be negative")
        }
        val activePipeline = ensurePipelineForDraw()
        if (instanceCount > 1) {
            requireFeature(GraphicsFeature.InstancedDrawing, "Instanced drawing")
        }
        val indices = indexBuffer
            ?: throw RenderValidationException("Cannot record an indexed draw without an index buffer")
        val indexFormat = indices.indexFormat
            ?: throw RenderValidationException("Index buffer has no format")
        val requiredBytes = (firstIndex.toLong() + indexCount.toLong()) * indexFormat.byteSize
        if (indices.offsetBytes + requiredBytes > indices.resource.descriptor.sizeBytes) {
            throw RenderValidationException(
                "Indexed draw reads $requiredBytes bytes at offset ${indices.offsetBytes} from a " +
                    "${indices.resource.descriptor.sizeBytes}-byte buffer",
            )
        }
        validateDrawBudget()
        validateVertexBindings(
            activePipeline = activePipeline,
            firstVertex = 0,
            vertexCount = indexCount,
            firstInstance = firstInstance,
            instanceCount = instanceCount,
            indexed = true,
        )
        commands += RenderCommand.DrawIndexed(
            indexCount = indexCount,
            instanceCount = instanceCount,
            firstIndex = firstIndex,
            baseVertex = baseVertex,
            firstInstance = firstInstance,
        )
        drawCalls++
    }

    /**
     * Copies a buffer region into a texture mip level. Copies are only recorded outside a pass.
     */
    public fun copyBufferToTexture(
        buffer: BufferHandle,
        bufferOffsetBytes: Long,
        bytesPerRow: Int,
        texture: TextureHandle,
        mipLevel: Int = 0,
        originX: Int = 0,
        originY: Int = 0,
        originZ: Int = 0,
        width: Int,
        height: Int = 1,
        depth: Int = 1,
    ) {
        ensureRecording()
        if (activePass != null) {
            throw RenderValidationException("Buffer-to-texture copies cannot be recorded inside a pass")
        }
        if (bufferOffsetBytes < 0L) {
            throw RenderValidationException("Copy buffer offset must not be negative")
        }
        if (bytesPerRow <= 0) {
            throw RenderValidationException("Copy bytesPerRow must be positive")
        }
        if (mipLevel < 0) {
            throw RenderValidationException("Copy mip level must not be negative")
        }
        if (originX < 0 || originY < 0 || originZ < 0) {
            throw RenderValidationException("Copy origin must not be negative")
        }
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw RenderValidationException("Copy size must be positive")
        }

        val source = device.resolveBuffer(buffer)
        requireUsage(source, BufferUsage.CopySource, "copy source")
        val destination = device.resolveTexture(texture)
        requireUsage(destination, TextureUsage.CopyDestination, "copy destination")
        val descriptor = destination.descriptor
        if (descriptor.sampleCount != 1) {
            throw RenderValidationException("Cannot copy into a multisampled texture")
        }
        if (mipLevel >= descriptor.mipLevels) {
            throw RenderValidationException(
                "Copy mip level $mipLevel is outside texture mip count ${descriptor.mipLevels}",
            )
        }

        val mipWidth = maxOf(1, descriptor.width shr mipLevel)
        val mipHeight = maxOf(1, descriptor.height shr mipLevel)
        val availableDepth = when (descriptor.dimension) {
            TextureDimension.D1 -> 1
            TextureDimension.D2 -> descriptor.depthOrLayers
            TextureDimension.D3 -> maxOf(1, descriptor.depthOrLayers shr mipLevel)
        }
        when (descriptor.dimension) {
            TextureDimension.D1 -> {
                if (height != 1 || depth != 1 || originY != 0 || originZ != 0) {
                    throw RenderValidationException(
                        "1D texture copies must have height 1, depth 1, and Y/Z origin 0",
                    )
                }
            }

            TextureDimension.D2, TextureDimension.D3 -> Unit
        }
        if (originX + width > mipWidth || originY + height > mipHeight ||
            originZ + depth > availableDepth
        ) {
            throw RenderValidationException(
                "Copy region $originX,$originY,$originZ ${width}x${height}x$depth exceeds mip " +
                    "dimensions ${mipWidth}x${mipHeight}x$availableDepth",
            )
        }

        val rowBytes = width.toLong() * descriptor.format.bytesPerPixel
        if (bytesPerRow.toLong() < rowBytes) {
            throw RenderValidationException(
                "Copy bytesPerRow $bytesPerRow is smaller than one row of $rowBytes bytes",
            )
        }
        val requiredBytes = bufferOffsetBytes +
            (height.toLong() * depth.toLong() - 1L) * bytesPerRow.toLong() +
            rowBytes
        if (requiredBytes > source.descriptor.sizeBytes) {
            throw RenderValidationException(
                "Copy reads $requiredBytes bytes from a ${source.descriptor.sizeBytes}-byte buffer",
            )
        }

        commands += RenderCommand.CopyBufferToTexture(
            buffer = buffer,
            bufferOffsetBytes = bufferOffsetBytes,
            bytesPerRow = bytesPerRow,
            texture = texture,
            mipLevel = mipLevel,
            originX = originX,
            originY = originY,
            originZ = originZ,
            width = width,
            height = height,
            depth = depth,
        )
    }

    /**
     * Ends recording and returns an immutable command buffer.
     *
     * @throws RenderValidationException if a render pass is still active or the encoder has already
     * been finished.
     */
    public fun finish(): CommandBuffer {
        ensureRecording()
        if (activePass != null) {
            throw RenderValidationException("Cannot finish an encoder while a render pass is active")
        }
        finished = true
        return CommandBuffer(
            handle = device.allocateCommandBufferHandle(),
            label = label,
            commands = commands.toList(),
            ownerDeviceId = device.id,
        )
    }

    private fun validateColorAttachment(attachment: RenderPassColorAttachment): TextureResource {
        val resource = device.resolveTexture(attachment.texture)
        val descriptor = resource.descriptor
        if (TextureUsage.RenderTarget !in descriptor.usage) {
            throw RenderValidationException(
                "Color attachment texture does not have RenderTarget usage",
            )
        }
        if (descriptor.format.isDepthStencil) {
            throw RenderValidationException(
                "Color attachment texture uses depth format ${descriptor.format}",
            )
        }
        validateAttachmentShape(descriptor.width, descriptor.height, descriptor.dimension, descriptor.depthOrLayers, "color")
        attachment.resolveTarget?.let { resolveHandle ->
            val resolve = device.resolveTexture(resolveHandle)
            val resolveDescriptor = resolve.descriptor
            if (TextureUsage.RenderTarget !in resolveDescriptor.usage) {
                throw RenderValidationException(
                    "Resolve target texture does not have RenderTarget usage",
                )
            }
            if (resolveDescriptor.format != descriptor.format) {
                throw RenderValidationException(
                    "Resolve target format ${resolveDescriptor.format} does not match color " +
                        "attachment format ${descriptor.format}",
                )
            }
            if (resolveDescriptor.sampleCount != 1) {
                throw RenderValidationException("Resolve target must be single-sampled")
            }
            if (resolveDescriptor.width != descriptor.width ||
                resolveDescriptor.height != descriptor.height
            ) {
                throw RenderValidationException(
                    "Resolve target dimensions ${resolveDescriptor.width}x${resolveDescriptor.height} " +
                        "do not match attachment ${descriptor.width}x${descriptor.height}",
                )
            }
            if (descriptor.sampleCount <= 1) {
                throw RenderValidationException(
                    "Resolve target requires a multisampled color attachment",
                )
            }
        }
        return resource
    }

    private fun validateDepthAttachment(
        attachment: RenderPassDepthStencilAttachment,
    ): TextureResource {
        val resource = device.resolveTexture(attachment.texture)
        val descriptor = resource.descriptor
        if (TextureUsage.DepthStencil !in descriptor.usage) {
            throw RenderValidationException(
                "Depth attachment texture does not have DepthStencil usage",
            )
        }
        if (!descriptor.format.isDepthStencil) {
            throw RenderValidationException(
                "Depth attachment texture uses color format ${descriptor.format}",
            )
        }
        validateAttachmentShape(
            descriptor.width,
            descriptor.height,
            descriptor.dimension,
            descriptor.depthOrLayers,
            "depth",
        )
        return resource
    }

    private fun validateAttachmentShape(
        width: Int,
        height: Int,
        dimension: TextureDimension,
        depthOrLayers: Int,
        role: String,
    ) {
        if (dimension != TextureDimension.D2 || depthOrLayers != 1) {
            throw RenderValidationException(
                "Render pass $role attachments must be non-array 2D textures",
            )
        }
        if (width <= 0 || height <= 0) {
            throw RenderValidationException("Render pass $role attachment has invalid dimensions")
        }
    }

    private fun ensurePipelineForDraw(): RenderPipelineResource {
        ensureInPass("record a draw")
        return pipeline
            ?: throw RenderValidationException("Cannot record a draw without a bound pipeline")
    }

    private fun validateDrawBudget() {
        val maximum = device.capabilities.limits.maxDrawCallsPerFrame
        if (drawCalls >= maximum) {
            throw RenderValidationException(
                "Draw call budget of $maximum per command buffer is exhausted",
            )
        }
    }

    private fun validateVertexBindings(
        activePipeline: RenderPipelineResource,
        firstVertex: Int,
        vertexCount: Int,
        firstInstance: Int,
        instanceCount: Int,
        indexed: Boolean,
    ) {
        activePipeline.descriptor.vertex.buffers.forEachIndexed { slot, layout ->
            val bound = vertexBuffers[slot]
                ?: throw RenderValidationException(
                    "Pipeline expects vertex buffer at slot $slot but none is bound",
                )
            val count = when (layout.stepMode) {
                VertexStepMode.Vertex ->
                    if (indexed) null else firstVertex.toLong() + vertexCount.toLong()

                VertexStepMode.Instance ->
                    firstInstance.toLong() + instanceCount.toLong()
            }
            if (count != null) {
                val requiredBytes = count * layout.strideBytes
                if (bound.offsetBytes + requiredBytes > bound.resource.descriptor.sizeBytes) {
                    throw RenderValidationException(
                        "Draw reads $requiredBytes bytes from vertex slot $slot at offset " +
                            "${bound.offsetBytes}, but the buffer is " +
                            "${bound.resource.descriptor.sizeBytes} bytes",
                    )
                }
            }
        }
    }

    private fun requireUsage(
        resource: BufferResource,
        usage: BufferUsage,
        role: String,
    ) {
        if (usage !in resource.descriptor.usage) {
            throw RenderValidationException("$role buffer is missing $usage usage")
        }
    }

    private fun requireUsage(
        resource: TextureResource,
        usage: TextureUsage,
        role: String,
    ) {
        if (usage !in resource.descriptor.usage) {
            throw RenderValidationException("$role texture is missing $usage usage")
        }
    }

    private fun requireFeature(feature: GraphicsFeature, operation: String) {
        if (!device.capabilities.supports(feature)) {
            throw RenderValidationException(
                "$operation is not supported by ${device.capabilities.backend}",
            )
        }
    }

    private fun ensureInPass(operation: String): ActivePass =
        activePass ?: throw RenderValidationException("Cannot $operation outside a render pass")

    private fun ensureRecording() {
        if (finished) {
            throw RenderValidationException("This command encoder has already been finished")
        }
    }
}

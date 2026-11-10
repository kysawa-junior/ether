package dev.instrum.ether.render.lwjgl.internal

import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BackendRenderPassDescriptor
import dev.instrum.ether.render.api.DepthLoadOperation
import dev.instrum.ether.render.api.LoadOperation
import dev.instrum.ether.render.api.StoreOp
import dev.instrum.ether.render.api.TextureDimension
import dev.instrum.ether.render.lwjgl.LwjglVulkanException
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkBufferCopy
import org.lwjgl.vulkan.VkBufferImageCopy
import org.lwjgl.vulkan.VkBufferMemoryBarrier
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import org.lwjgl.vulkan.VkViewport

/**
 * Records translated backend commands into a Vulkan command buffer.
 *
 * Images always live in [VK10.VK_IMAGE_LAYOUT_GENERAL] outside a rendering scope. The recorder inserts
 * the layout transitions around every attachment and copy so a command buffer is self contained and
 * does not depend on the layout left behind by another command buffer.
 */
internal class LwjglCommandRecorder(
    private val resolveBuffer: (Long) -> LwjglBufferResource,
    private val resolveTexture: (Long) -> LwjglTextureResource,
    private val resolvePipeline: (Long) -> LwjglPipelineResource,
) {
    private var activeAttachments: List<LwjglTextureResource> = emptyList()

    /**
     * Records [commands] into [commandBuffer].
     *
     * @throws LwjglVulkanException if a command references an unknown resource or uses an unsupported
     * combination of state.
     */
    fun record(commandBuffer: VkCommandBuffer, commands: List<BackendCommand>) {
        fun begin(commandBuffer: VkCommandBuffer) {
            MemoryStack.stackPush().use { stack ->
                val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                beginInfo.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                beginInfo.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
                checkVkResult(
                    VK10.vkBeginCommandBuffer(commandBuffer, beginInfo),
                    "vkBeginCommandBuffer",
                )
            }
        }

        begin(commandBuffer)
        for (command in commands) {
            recordCommand(commandBuffer, command)
        }
        checkVkResult(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")
    }

    /**
     * Begins the recording on [commandBuffer], copies [source] into [destination], and ends it,
     * with explicit memory dependencies.
     *
     * The host write into the staging buffer is made visible to the transfer, and the transfer
     * write is made visible to every later command that reads the destination buffer.
     *
     * Unlike the Kotlin/Native driver, this call owns the begin/end bracket: submitting an
     * unfinished command buffer is invalid, and the staging upload reaches the command buffer
     * unbegun there.
     */
    fun recordBufferUpload(
        commandBuffer: VkCommandBuffer,
        source: LwjglBufferResource,
        destination: LwjglBufferResource,
        destinationOffset: Long,
        size: Int,
    ) {
        MemoryStack.stackPush().use { stack ->
            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
            beginInfo.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            beginInfo.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            checkVkResult(
                VK10.vkBeginCommandBuffer(commandBuffer, beginInfo),
                "vkBeginCommandBuffer",
            )

            bufferBarrier(
                commandBuffer = commandBuffer,
                stack = stack,
                srcStageMask = VK10.VK_PIPELINE_STAGE_HOST_BIT,
                dstStageMask = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                srcAccessMask = VK10.VK_ACCESS_HOST_WRITE_BIT,
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_READ_BIT,
                buffer = source.buffer,
                offset = 0L,
                size = source.size,
            )

            val regions = VkBufferCopy.calloc(1, stack)
            regions[0].srcOffset(0L)
                .dstOffset(destinationOffset)
                .size(size.toLong())
            VK10.vkCmdCopyBuffer(commandBuffer, source.buffer, destination.buffer, regions)

            bufferBarrier(
                commandBuffer = commandBuffer,
                stack = stack,
                srcStageMask = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                dstStageMask = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                dstAccessMask = VK10.VK_ACCESS_MEMORY_READ_BIT,
                buffer = destination.buffer,
                offset = destinationOffset,
                size = size.toLong(),
            )

            checkVkResult(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")
        }
    }

    /**
     * Begins the recording on [commandBuffer], copies the base mip level of [texture] into
     * [buffer], and ends it.
     *
     * The image is moved to the transfer source layout for the copy and back to the general layout
     * afterwards, so the command buffer stays self contained.
     */
    fun recordTextureReadback(
        commandBuffer: VkCommandBuffer,
        texture: LwjglTextureResource,
        buffer: LwjglBufferResource,
    ) {
        MemoryStack.stackPush().use { stack ->
            val beginInfo = VkCommandBufferBeginInfo.calloc(stack)
            beginInfo.sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            beginInfo.flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            checkVkResult(
                VK10.vkBeginCommandBuffer(commandBuffer, beginInfo),
                "vkBeginCommandBuffer",
            )

            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = texture.aspectMask,
                newLayout = VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dstStageMask = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                dstAccessMask = VK10.VK_ACCESS_TRANSFER_READ_BIT,
            )

            val regions = VkBufferImageCopy.calloc(1, stack)
            val region = regions[0]
            region.bufferOffset(0L)
                .bufferRowLength(0)
                .bufferImageHeight(0)
            region.imageSubresource { subresource ->
                subresource.aspectMask(texture.aspectMask)
                    .mipLevel(0)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }
            region.imageOffset { offset -> offset.x(0).y(0).z(0) }
            region.imageExtent { extent ->
                extent.width(texture.descriptor.width)
                    .height(texture.descriptor.height)
                    .depth(1)
            }

            VK10.vkCmdCopyImageToBuffer(
                commandBuffer,
                texture.image,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                buffer.buffer,
                regions,
            )

            // The copy write must be visible to the host read that follows the fence wait.
            bufferBarrier(
                commandBuffer = commandBuffer,
                stack = stack,
                srcStageMask = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                dstStageMask = VK10.VK_PIPELINE_STAGE_HOST_BIT,
                srcAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                dstAccessMask = VK10.VK_ACCESS_HOST_READ_BIT,
                buffer = buffer.buffer,
                offset = 0L,
                size = buffer.size,
            )

            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = texture.aspectMask,
                newLayout = VK10.VK_IMAGE_LAYOUT_GENERAL,
                dstStageMask = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstAccessMask = VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT,
            )

            checkVkResult(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")
        }
    }

    private fun recordCommand(commandBuffer: VkCommandBuffer, command: BackendCommand) {
        when (command) {
            is BackendCommand.BeginRenderPass -> beginRendering(commandBuffer, command.descriptor)
            BackendCommand.EndRenderPass -> endRendering(commandBuffer)
            is BackendCommand.SetPipeline -> {
                val pipeline = resolvePipeline(command.pipeline)
                VK10.vkCmdBindPipeline(
                    commandBuffer,
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline.pipeline,
                )
            }

            is BackendCommand.SetVertexBuffer -> {
                val buffer = resolveBuffer(command.buffer)
                MemoryStack.stackPush().use { stack ->
                    val handles = stack.longs(buffer.buffer)
                    val offsets = stack.longs(command.offsetBytes)
                    VK10.vkCmdBindVertexBuffers(commandBuffer, command.slot, handles, offsets)
                }
            }

            is BackendCommand.SetIndexBuffer -> {
                val buffer = resolveBuffer(command.buffer)
                VK10.vkCmdBindIndexBuffer(
                    commandBuffer,
                    buffer.buffer,
                    command.offsetBytes,
                    command.format.toVkIndexType(),
                )
            }

            is BackendCommand.SetViewport -> {
                MemoryStack.stackPush().use { stack ->
                    val viewports = VkViewport.calloc(1, stack)
                    viewports.x(command.viewport.x)
                        .y(command.viewport.y)
                        .width(command.viewport.width)
                        .height(command.viewport.height)
                        .minDepth(command.viewport.minDepth)
                        .maxDepth(command.viewport.maxDepth)
                    VK10.vkCmdSetViewport(commandBuffer, 0, viewports)
                }
            }

            is BackendCommand.SetScissor -> {
                MemoryStack.stackPush().use { stack ->
                    val scissors = VkRect2D.calloc(1, stack)
                    scissors.offset { offset -> offset.x(command.scissor.x).y(command.scissor.y) }
                    scissors.extent { extent ->
                        extent.width(command.scissor.width).height(command.scissor.height)
                    }
                    VK10.vkCmdSetScissor(commandBuffer, 0, scissors)
                }
            }

            is BackendCommand.Draw -> {
                VK10.vkCmdDraw(
                    commandBuffer,
                    command.vertexCount,
                    command.instanceCount,
                    command.firstVertex,
                    command.firstInstance,
                )
            }

            is BackendCommand.DrawIndexed -> {
                VK10.vkCmdDrawIndexed(
                    commandBuffer,
                    command.indexCount,
                    command.instanceCount,
                    command.firstIndex,
                    command.baseVertex,
                    command.firstInstance,
                )
            }

            is BackendCommand.CopyBufferToTexture -> {
                copyBufferToTexture(commandBuffer, command)
            }
        }
    }

    private fun beginRendering(
        commandBuffer: VkCommandBuffer,
        descriptor: BackendRenderPassDescriptor,
    ) {
        if (activeAttachments.isNotEmpty()) {
            throw LwjglVulkanException("A render pass is already active in this command buffer")
        }

        val colorAttachments = descriptor.colorAttachments.map { attachment ->
            val texture = resolveTexture(attachment.texture)
            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                newLayout = VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                dstStageMask = VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                dstAccessMask = VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            )
            texture
        }
        val resolveTargets = descriptor.colorAttachments.mapNotNull { attachment ->
            attachment.resolveTarget?.let { token ->
                val texture = resolveTexture(token)
                transitionImage(
                    commandBuffer = commandBuffer,
                    image = texture.image,
                    aspectMask = VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    newLayout = VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    dstStageMask = VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    dstAccessMask = VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                )
                texture
            }
        }
        val depthTexture = descriptor.depthStencilAttachment?.let { attachment ->
            val texture = resolveTexture(attachment.texture)
            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = texture.aspectMask,
                newLayout = VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                dstStageMask = VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                dstAccessMask = VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            )
            texture
        }

        activeAttachments = colorAttachments + resolveTargets + listOfNotNull(depthTexture)

        MemoryStack.stackPush().use { stack ->
            val attachmentInfos = if (colorAttachments.isEmpty()) {
                null
            } else {
                VkRenderingAttachmentInfo.calloc(colorAttachments.size, stack)
            }
            colorAttachments.forEachIndexed { index, texture ->
                val attachment = descriptor.colorAttachments[index]
                val info = attachmentInfos!![index]
                info.sType(VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                info.imageView(texture.view)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(attachment.load.toVkLoadOp())
                    .storeOp(attachment.store.toVkStoreOp())
                    .resolveMode(
                        if (attachment.resolveTarget != null) {
                            VK13.VK_RESOLVE_MODE_AVERAGE_BIT
                        } else {
                            0
                        },
                    )
                attachment.resolveTarget?.let { token ->
                    info.resolveImageView(resolveTexture(token).view)
                        .resolveImageLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                }
                if (attachment.load is LoadOperation.Clear) {
                    val clear = attachment.load as LoadOperation.Clear
                    info.clearValue { value ->
                        value.color().float32(0, clear.color.red)
                            .float32(1, clear.color.green)
                            .float32(2, clear.color.blue)
                            .float32(3, clear.color.alpha)
                    }
                }
            }

            val depthInfo: VkRenderingAttachmentInfo? = depthTexture?.let { texture ->
                VkRenderingAttachmentInfo.calloc(stack).also { info ->
                    info.sType(VK13.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    info.imageView(texture.view)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    val depthAttachment = descriptor.depthStencilAttachment!!
                    info.loadOp(depthAttachment.depthLoad.toVkLoadOp())
                        .storeOp(depthAttachment.depthStore.toVkDepthStoreOp())
                        .resolveMode(0)
                    if (depthAttachment.depthLoad is DepthLoadOperation.Clear) {
                        val clear = depthAttachment.depthLoad as DepthLoadOperation.Clear
                        info.clearValue { value ->
                            value.depthStencil().depth(clear.depth).stencil(0)
                        }
                    }
                }
            }

            val renderingInfo = VkRenderingInfo.calloc(stack)
                .sType(VK13.VK_STRUCTURE_TYPE_RENDERING_INFO)
            renderingInfo.renderArea { area ->
                area.offset { offset -> offset.x(0).y(0) }
                val firstAttachment = activeAttachments.first()
                area.extent { extent ->
                    extent.width(firstAttachment.descriptor.width)
                        .height(firstAttachment.descriptor.height)
                }
            }
            renderingInfo.layerCount(1)
                .viewMask(0)
                .pColorAttachments(attachmentInfos)
                .pDepthAttachment(depthInfo)

            VK13.vkCmdBeginRendering(commandBuffer, renderingInfo)
        }

        // Dynamic viewport and scissor state must be valid before the first draw. Defaulting them
        // to the render area keeps draws correct even when the engine never sets them.
        MemoryStack.stackPush().use { stack ->
            val firstAttachment = activeAttachments.first()
            val viewports = VkViewport.calloc(1, stack)
            viewports.x(0f)
                .y(0f)
                .width(firstAttachment.descriptor.width.toFloat())
                .height(firstAttachment.descriptor.height.toFloat())
                .minDepth(0f)
                .maxDepth(1f)
            VK10.vkCmdSetViewport(commandBuffer, 0, viewports)

            val scissors = VkRect2D.calloc(1, stack)
            scissors.offset { offset -> offset.x(0).y(0) }
            scissors.extent { extent ->
                extent.width(firstAttachment.descriptor.width)
                    .height(firstAttachment.descriptor.height)
            }
            VK10.vkCmdSetScissor(commandBuffer, 0, scissors)
        }
    }

    private fun endRendering(commandBuffer: VkCommandBuffer) {
        if (activeAttachments.isEmpty()) {
            throw LwjglVulkanException("No render pass is active in this command buffer")
        }
        VK13.vkCmdEndRendering(commandBuffer)
        activeAttachments.forEach { texture ->
            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = if (texture.aspectMask and VK10.VK_IMAGE_ASPECT_DEPTH_BIT != 0) {
                    texture.aspectMask
                } else {
                    VK10.VK_IMAGE_ASPECT_COLOR_BIT
                },
                newLayout = VK10.VK_IMAGE_LAYOUT_GENERAL,
                dstStageMask = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstAccessMask = VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT,
            )
        }
        activeAttachments = emptyList()
    }

    private fun copyBufferToTexture(
        commandBuffer: VkCommandBuffer,
        command: BackendCommand.CopyBufferToTexture,
    ) {
        val buffer = resolveBuffer(command.buffer)
        val texture = resolveTexture(command.texture)
        val bytesPerPixel = texture.descriptor.format.bytesPerPixel
        if (command.bytesPerRow % bytesPerPixel != 0) {
            throw LwjglVulkanException(
                "Copy bytesPerRow ${command.bytesPerRow} is not a multiple of the " +
                    "$bytesPerPixel-byte texel size",
            )
        }

        transitionImage(
            commandBuffer = commandBuffer,
            image = texture.image,
            aspectMask = texture.aspectMask,
            newLayout = VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            dstStageMask = VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
            dstAccessMask = VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
        )

        MemoryStack.stackPush().use { stack ->
            val regions = VkBufferImageCopy.calloc(1, stack)
            val region = regions[0]
            region.bufferOffset(command.bufferOffsetBytes)
                .bufferRowLength(command.bytesPerRow / bytesPerPixel)
                .bufferImageHeight(0)
            region.imageSubresource { subresource ->
                subresource.aspectMask(texture.aspectMask)
                    .mipLevel(command.mipLevel)
                    .layerCount(1)
            }
            region.imageOffset { offset -> offset.x(command.originX).y(command.originY).z(0) }
            region.imageExtent { extent ->
                extent.width(command.width).height(command.height).depth(1)
            }

            if (texture.descriptor.dimension == TextureDimension.D3) {
                region.imageOffset { offset -> offset.z(command.originZ) }
                region.imageExtent { extent -> extent.depth(command.depth) }
            } else {
                region.imageSubresource { subresource ->
                    subresource.baseArrayLayer(command.originZ)
                        .layerCount(command.depth)
                }
            }

            VK10.vkCmdCopyBufferToImage(
                commandBuffer,
                buffer.buffer,
                texture.image,
                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                regions,
            )
        }

        transitionImage(
            commandBuffer = commandBuffer,
            image = texture.image,
            aspectMask = texture.aspectMask,
            newLayout = VK10.VK_IMAGE_LAYOUT_GENERAL,
            dstStageMask = VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            dstAccessMask = VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT,
        )
    }

    private fun bufferBarrier(
        commandBuffer: VkCommandBuffer,
        stack: MemoryStack,
        srcStageMask: Int,
        dstStageMask: Int,
        srcAccessMask: Int,
        dstAccessMask: Int,
        buffer: Long,
        offset: Long,
        size: Long,
    ) {
        val barriers = VkBufferMemoryBarrier.calloc(1, stack)
            .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
        barriers.srcAccessMask(srcAccessMask)
            .dstAccessMask(dstAccessMask)
            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
            .buffer(buffer)
            .offset(offset)
            .size(size)
        VK10.vkCmdPipelineBarrier(
            commandBuffer,
            srcStageMask,
            dstStageMask,
            0,
            null,
            barriers,
            null,
        )
    }

    private fun transitionImage(
        commandBuffer: VkCommandBuffer,
        image: Long,
        aspectMask: Int,
        newLayout: Int,
        dstStageMask: Int,
        dstAccessMask: Int,
    ) {
        MemoryStack.stackPush().use { stack ->
            val barriers = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            barriers.srcAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(dstAccessMask)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image)
            barriers.subresourceRange { range ->
                range.aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1)
            }

            VK10.vkCmdPipelineBarrier(
                commandBuffer,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstStageMask,
                0,
                null,
                null,
                barriers,
            )
        }
    }
}

/**
 * Maps a portable load operation to its Vulkan load operation.
 */
internal fun LoadOperation.toVkLoadOp(): Int = when (this) {
    LoadOperation.Load -> VK10.VK_ATTACHMENT_LOAD_OP_LOAD
    LoadOperation.DontCare -> VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE
    is LoadOperation.Clear -> VK10.VK_ATTACHMENT_LOAD_OP_CLEAR
}

/**
 * Maps a portable depth load operation to its Vulkan load operation.
 */
internal fun DepthLoadOperation.toVkLoadOp(): Int = when (this) {
    DepthLoadOperation.Load -> VK10.VK_ATTACHMENT_LOAD_OP_LOAD
    DepthLoadOperation.DontCare -> VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE
    is DepthLoadOperation.Clear -> VK10.VK_ATTACHMENT_LOAD_OP_CLEAR
}

/**
 * Maps a portable store operation to its Vulkan color attachment store operation.
 */
internal fun StoreOp.toVkStoreOp(): Int = when (this) {
    StoreOp.Store -> VK10.VK_ATTACHMENT_STORE_OP_STORE
    StoreOp.Discard -> VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE
}

/**
 * Maps a portable store operation to its Vulkan depth attachment store operation.
 */
internal fun StoreOp.toVkDepthStoreOp(): Int = when (this) {
    StoreOp.Store -> VK10.VK_ATTACHMENT_STORE_OP_STORE
    StoreOp.Discard -> VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE
}

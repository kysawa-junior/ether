@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BackendRenderPassDescriptor
import dev.instrum.ether.render.api.LoadOperation
import dev.instrum.ether.render.api.StoreOp
import dev.instrum.ether.render.api.TextureDimension
import dev.instrum.ether.render.vulkan.VulkanException
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_HOST_READ_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_HOST_WRITE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_MEMORY_READ_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_MEMORY_WRITE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_TRANSFER_READ_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ACCESS_TRANSFER_WRITE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ATTACHMENT_LOAD_OP_CLEAR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ATTACHMENT_LOAD_OP_DONT_CARE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ATTACHMENT_LOAD_OP_LOAD
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ATTACHMENT_STORE_OP_DONT_CARE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_ATTACHMENT_STORE_OP_STORE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_ASPECT_COLOR_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_ASPECT_DEPTH_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_ASPECT_STENCIL_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_LAYOUT_GENERAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PIPELINE_BIND_POINT_GRAPHICS
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PIPELINE_STAGE_HOST_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PIPELINE_STAGE_TRANSFER_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_QUEUE_FAMILY_IGNORED
import dev.instrum.ether.render.vulkan.internal.vkc.VK_RESOLVE_MODE_AVERAGE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_RENDERING_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VkBufferImageCopy
import dev.instrum.ether.render.vulkan.internal.vkc.VkBufferCopy
import dev.instrum.ether.render.vulkan.internal.vkc.VkBufferMemoryBarrier
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageMemoryBarrier
import dev.instrum.ether.render.vulkan.internal.vkc.VkRect2D
import dev.instrum.ether.render.vulkan.internal.vkc.VkRenderingAttachmentInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkRenderingInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkViewport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.invoke
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value

/**
 * Records translated backend commands into a Vulkan command buffer.
 *
 * Images always live in [VK_IMAGE_LAYOUT_GENERAL] outside a rendering scope. The recorder inserts
 * the layout transitions around every attachment and copy so a command buffer is self contained and
 * does not depend on the layout left behind by another command buffer.
 */
internal class VulkanCommandRecorder(
    private val api: VulkanDeviceApi,
    private val resolveBuffer: (Long) -> VulkanBufferResource,
    private val resolveTexture: (Long) -> VulkanTextureResource,
    private val resolvePipeline: (Long) -> VulkanPipelineResource,
) {
    private var activeAttachments: List<VulkanTextureResource> = emptyList()

    /**
     * Records [commands] into [commandBuffer].
     *
     * @throws VulkanException if a command references an unknown resource or uses an unsupported
     * combination of state.
     */
    fun record(commandBuffer: VkCommandBuffer, commands: List<BackendCommand>) {
        memScoped {
            val beginInfo = zeroed<
                dev.instrum.ether.render.vulkan.internal.vkc.VkCommandBufferBeginInfo,
            >()
            beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
            checkVkResult(
                api.beginCommandBuffer.invoke(commandBuffer, beginInfo.ptr),
                "vkBeginCommandBuffer",
            )

            for (command in commands) {
                recordCommand(commandBuffer, command)
            }

            checkVkResult(api.endCommandBuffer.invoke(commandBuffer), "vkEndCommandBuffer")
        }
    }

    /**
     * Records a copy of [source] into [destination] with explicit memory dependencies.
     *
     * The host write into the staging buffer is made visible to the transfer, and the transfer
     * write is made visible to every later command that reads the destination buffer.
     */
    fun recordBufferUpload(
        commandBuffer: VkCommandBuffer,
        source: VulkanBufferResource,
        destination: VulkanBufferResource,
        destinationOffset: Long,
        size: Int,
    ) {
        memScoped {
            val hostBarrier = zeroed<VkBufferMemoryBarrier>()
            hostBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
            hostBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT
            hostBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT
            hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            hostBarrier.buffer = source.buffer
            hostBarrier.offset = 0uL
            hostBarrier.size = source.size

            api.cmdPipelineBarrier.invoke(
                commandBuffer,
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0u,
                0u,
                null,
                1u,
                hostBarrier.ptr,
                0u,
                null,
            )

            val region = zeroed<VkBufferCopy>()
            region.srcOffset = 0uL
            region.dstOffset = destinationOffset.toULong()
            region.size = size.toULong()

            api.cmdCopyBuffer.invoke(commandBuffer, source.buffer, destination.buffer, 1u, region.ptr)

            val transferBarrier = zeroed<VkBufferMemoryBarrier>()
            transferBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
            transferBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT
            transferBarrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT
            transferBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            transferBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            transferBarrier.buffer = destination.buffer
            transferBarrier.offset = destinationOffset.toULong()
            transferBarrier.size = size.toULong()

            api.cmdPipelineBarrier.invoke(
                commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0u,
                0u,
                null,
                1u,
                transferBarrier.ptr,
                0u,
                null,
            )
        }
    }

    /**
     * Records a copy of the base mip level of [texture] into [buffer].
     *
     * The image is moved to the transfer source layout for the copy and back to the general layout
     * afterwards, so the command buffer stays self contained.
     */
    fun recordTextureReadback(
        commandBuffer: VkCommandBuffer,
        texture: VulkanTextureResource,
        buffer: VulkanBufferResource,
    ) {
        memScoped {
            val beginInfo = zeroed<
                dev.instrum.ether.render.vulkan.internal.vkc.VkCommandBufferBeginInfo,
            >()
            beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
            checkVkResult(
                api.beginCommandBuffer.invoke(commandBuffer, beginInfo.ptr),
                "vkBeginCommandBuffer",
            )

            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = texture.aspectMask,
                newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
                dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
            )

            val region = zeroed<VkBufferImageCopy>()
            region.bufferOffset = 0uL
            region.bufferRowLength = 0u
            region.bufferImageHeight = 0u
            region.imageSubresource.aspectMask = texture.aspectMask
            region.imageSubresource.mipLevel = 0u
            region.imageSubresource.baseArrayLayer = 0u
            region.imageSubresource.layerCount = 1u
            region.imageOffset.x = 0
            region.imageOffset.y = 0
            region.imageOffset.z = 0
            region.imageExtent.width = texture.descriptor.width.toUInt()
            region.imageExtent.height = texture.descriptor.height.toUInt()
            region.imageExtent.depth = 1u

            api.cmdCopyImageToBuffer.invoke(
                commandBuffer,
                texture.image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                buffer.buffer,
                1u,
                region.ptr,
            )

            // The copy write must be visible to the host read that follows the fence wait.
            val hostBarrier = zeroed<VkBufferMemoryBarrier>()
            hostBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER
            hostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT
            hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT
            hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            hostBarrier.buffer = buffer.buffer
            hostBarrier.offset = 0uL
            hostBarrier.size = buffer.size

            api.cmdPipelineBarrier.invoke(
                commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_HOST_BIT,
                0u,
                0u,
                null,
                1u,
                hostBarrier.ptr,
                0u,
                null,
            )

            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = texture.aspectMask,
                newLayout = VK_IMAGE_LAYOUT_GENERAL,
                dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstAccessMask = VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT,
            )

            checkVkResult(api.endCommandBuffer.invoke(commandBuffer), "vkEndCommandBuffer")
        }
    }

    private fun recordCommand(commandBuffer: VkCommandBuffer, command: BackendCommand) {
        when (command) {
            is BackendCommand.BeginRenderPass -> beginRendering(commandBuffer, command.descriptor)
            BackendCommand.EndRenderPass -> endRendering(commandBuffer)
            is BackendCommand.SetPipeline -> {
                val pipeline = resolvePipeline(command.pipeline)
                api.cmdBindPipeline.invoke(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)
            }

            is BackendCommand.SetVertexBuffer -> {
                val buffer = resolveBuffer(command.buffer)
                memScoped {
                    val buffers = zeroedArray<
                        dev.instrum.ether.render.vulkan.internal.vkc.VkBufferVar,
                    >(1)
                    buffers[0] = buffer.buffer
                    val offsets = zeroedArray<ULongVar>(1)
                    offsets[0] = command.offsetBytes.toULong()
                    api.cmdBindVertexBuffers.invoke(commandBuffer, command.slot.toUInt(), 1u, buffers, offsets)
                }
            }

            is BackendCommand.SetIndexBuffer -> {
                val buffer = resolveBuffer(command.buffer)
                api.cmdBindIndexBuffer.invoke(
                    commandBuffer,
                    buffer.buffer,
                    command.offsetBytes.toULong(),
                    command.format.toVkIndexType(),
                )
            }

            is BackendCommand.SetViewport -> {
                memScoped {
                    val viewport = zeroed<VkViewport>()
                    viewport.x = command.viewport.x
                    viewport.y = command.viewport.y
                    viewport.width = command.viewport.width
                    viewport.height = command.viewport.height
                    viewport.minDepth = command.viewport.minDepth
                    viewport.maxDepth = command.viewport.maxDepth
                    api.cmdSetViewport.invoke(commandBuffer, 0u, 1u, viewport.ptr)
                }
            }

            is BackendCommand.SetScissor -> {
                memScoped {
                    val scissor = zeroed<VkRect2D>()
                    scissor.offset.x = command.scissor.x
                    scissor.offset.y = command.scissor.y
                    scissor.extent.width = command.scissor.width.toUInt()
                    scissor.extent.height = command.scissor.height.toUInt()
                    api.cmdSetScissor.invoke(commandBuffer, 0u, 1u, scissor.ptr)
                }
            }

            is BackendCommand.Draw -> {
                api.cmdDraw.invoke(
                    commandBuffer,
                    command.vertexCount.toUInt(),
                    command.instanceCount.toUInt(),
                    command.firstVertex.toUInt(),
                    command.firstInstance.toUInt(),
                )
            }

            is BackendCommand.DrawIndexed -> {
                api.cmdDrawIndexed.invoke(
                    commandBuffer,
                    command.indexCount.toUInt(),
                    command.instanceCount.toUInt(),
                    command.firstIndex.toUInt(),
                    command.baseVertex,
                    command.firstInstance.toUInt(),
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
            throw VulkanException("A render pass is already active in this command buffer")
        }

        val colorAttachments = descriptor.colorAttachments.map { attachment ->
            val texture = resolveTexture(attachment.texture)
            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            )
            texture
        }
        val resolveTargets = descriptor.colorAttachments.mapNotNull { attachment ->
            attachment.resolveTarget?.let { token ->
                val texture = resolveTexture(token)
                transitionImage(
                    commandBuffer = commandBuffer,
                    image = texture.image,
                    aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                    newLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
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
                newLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                dstStageMask = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                dstAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
            )
            texture
        }

        activeAttachments = colorAttachments + resolveTargets + listOfNotNull(depthTexture)

        memScoped {
            val attachmentInfos = if (colorAttachments.isEmpty()) {
                null
            } else {
                zeroedArray<VkRenderingAttachmentInfo>(colorAttachments.size)
            }
            colorAttachments.forEachIndexed { index, texture ->
                val attachment = descriptor.colorAttachments[index]
                val info = attachmentInfos!![index]
                info.sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO
                info.imageView = texture.view
                info.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                info.loadOp = attachment.load.toVkLoadOp()
                info.storeOp = attachment.store.toVkStoreOp()
                info.resolveMode = if (attachment.resolveTarget != null) {
                    VK_RESOLVE_MODE_AVERAGE_BIT
                } else {
                    0u
                }
                attachment.resolveTarget?.let { token ->
                    info.resolveImageView = resolveTexture(token).view
                    info.resolveImageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                }
                if (attachment.load is LoadOperation.Clear) {
                    val clear = attachment.load as LoadOperation.Clear
                    info.clearValue.color.float32[0] = clear.color.red
                    info.clearValue.color.float32[1] = clear.color.green
                    info.clearValue.color.float32[2] = clear.color.blue
                    info.clearValue.color.float32[3] = clear.color.alpha
                }
            }

            val depthInfo = if (depthTexture == null) {
                null
            } else {
                zeroed<VkRenderingAttachmentInfo>().also { info ->
                    info.sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO
                    info.imageView = depthTexture.view
                    info.imageLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                    val depthAttachment = descriptor.depthStencilAttachment!!
                    info.loadOp = depthAttachment.depthLoad.toVkLoadOp()
                    info.storeOp = depthAttachment.depthStore.toVkDepthStoreOp()
                    info.resolveMode = 0u
                    if (depthAttachment.depthLoad is dev.instrum.ether.render.api.DepthLoadOperation.Clear) {
                        val clear = depthAttachment.depthLoad as
                            dev.instrum.ether.render.api.DepthLoadOperation.Clear
                        info.clearValue.depthStencil.depth = clear.depth
                        info.clearValue.depthStencil.stencil = 0u
                    }
                }
            }

            val renderingInfo = zeroed<VkRenderingInfo>()
            renderingInfo.sType = VK_STRUCTURE_TYPE_RENDERING_INFO
            renderingInfo.renderArea.offset.x = 0
            renderingInfo.renderArea.offset.y = 0
            val firstAttachment = activeAttachments.first()
            renderingInfo.renderArea.extent.width = firstAttachment.descriptor.width.toUInt()
            renderingInfo.renderArea.extent.height = firstAttachment.descriptor.height.toUInt()
            renderingInfo.layerCount = 1u
            renderingInfo.viewMask = 0u
            renderingInfo.colorAttachmentCount = colorAttachments.size.toUInt()
            renderingInfo.pColorAttachments = attachmentInfos
            renderingInfo.pDepthAttachment = depthInfo?.ptr

            api.cmdBeginRendering.invoke(commandBuffer, renderingInfo.ptr)
        }

        // Dynamic viewport and scissor state must be valid before the first draw. Defaulting them
        // to the render area keeps draws correct even when the engine never sets them.
        memScoped {
            val viewport = zeroed<VkViewport>()
            viewport.x = 0f
            viewport.y = 0f
            viewport.width = activeAttachments.first().descriptor.width.toFloat()
            viewport.height = activeAttachments.first().descriptor.height.toFloat()
            viewport.minDepth = 0f
            viewport.maxDepth = 1f
            api.cmdSetViewport.invoke(commandBuffer, 0u, 1u, viewport.ptr)

            val scissor = zeroed<VkRect2D>()
            scissor.offset.x = 0
            scissor.offset.y = 0
            scissor.extent.width = activeAttachments.first().descriptor.width.toUInt()
            scissor.extent.height = activeAttachments.first().descriptor.height.toUInt()
            api.cmdSetScissor.invoke(commandBuffer, 0u, 1u, scissor.ptr)
        }
    }

    private fun endRendering(commandBuffer: VkCommandBuffer) {
        if (activeAttachments.isEmpty()) {
            throw VulkanException("No render pass is active in this command buffer")
        }
        api.cmdEndRendering.invoke(commandBuffer)
        activeAttachments.forEach { texture ->
            transitionImage(
                commandBuffer = commandBuffer,
                image = texture.image,
                aspectMask = if (texture.aspectMask and VK_IMAGE_ASPECT_DEPTH_BIT != 0u) {
                    texture.aspectMask
                } else {
                    VK_IMAGE_ASPECT_COLOR_BIT
                },
                newLayout = VK_IMAGE_LAYOUT_GENERAL,
                dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstAccessMask = VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT,
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
            throw VulkanException(
                "Copy bytesPerRow ${command.bytesPerRow} is not a multiple of the " +
                    "$bytesPerPixel-byte texel size",
            )
        }

        transitionImage(
            commandBuffer = commandBuffer,
            image = texture.image,
            aspectMask = texture.aspectMask,
            newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT,
            dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        )

        memScoped {
            val region = zeroed<VkBufferImageCopy>()
            region.bufferOffset = command.bufferOffsetBytes.toULong()
            region.bufferRowLength = (command.bytesPerRow / bytesPerPixel).toUInt()
            region.bufferImageHeight = 0u
            region.imageSubresource.aspectMask = texture.aspectMask
            region.imageSubresource.mipLevel = command.mipLevel.toUInt()
            region.imageSubresource.baseArrayLayer = 0u
            region.imageSubresource.layerCount = 1u
            region.imageOffset.x = command.originX
            region.imageOffset.y = command.originY
            region.imageOffset.z = 0
            region.imageExtent.width = command.width.toUInt()
            region.imageExtent.height = command.height.toUInt()
            region.imageExtent.depth = 1u

            if (texture.descriptor.dimension == TextureDimension.D3) {
                region.imageOffset.z = command.originZ
                region.imageExtent.depth = command.depth.toUInt()
            } else {
                region.imageSubresource.baseArrayLayer = command.originZ.toUInt()
                region.imageSubresource.layerCount = command.depth.toUInt()
            }

            api.cmdCopyBufferToImage.invoke(
                commandBuffer,
                buffer.buffer,
                texture.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1u,
                region.ptr,
            )
        }

        transitionImage(
            commandBuffer = commandBuffer,
            image = texture.image,
            aspectMask = texture.aspectMask,
            newLayout = VK_IMAGE_LAYOUT_GENERAL,
            dstStageMask = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            dstAccessMask = VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT,
        )
    }

    private fun transitionImage(
        commandBuffer: VkCommandBuffer,
        image: dev.instrum.ether.render.vulkan.internal.vkc.VkImage,
        aspectMask: UInt,
        newLayout: UInt,
        dstStageMask: UInt,
        dstAccessMask: UInt,
    ) {
        memScoped {
            val barrier = zeroed<VkImageMemoryBarrier>()
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
            barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT or VK_ACCESS_MEMORY_WRITE_BIT
            barrier.dstAccessMask = dstAccessMask
            barrier.oldLayout = VK_IMAGE_LAYOUT_GENERAL
            barrier.newLayout = newLayout
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED
            barrier.image = image
            barrier.subresourceRange.aspectMask = aspectMask
            barrier.subresourceRange.baseMipLevel = 0u
            barrier.subresourceRange.levelCount = 1u
            barrier.subresourceRange.baseArrayLayer = 0u
            barrier.subresourceRange.layerCount = 1u

            api.cmdPipelineBarrier.invoke(
                commandBuffer,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                dstStageMask,
                0u,
                0u,
                null,
                0u,
                null,
                1u,
                barrier.ptr,
            )
        }
    }
}

/**
 * Maps a portable load operation to its Vulkan load operation.
 */
internal fun LoadOperation.toVkLoadOp(): UInt = when (this) {
    LoadOperation.Load -> VK_ATTACHMENT_LOAD_OP_LOAD
    LoadOperation.DontCare -> VK_ATTACHMENT_LOAD_OP_DONT_CARE
    is LoadOperation.Clear -> VK_ATTACHMENT_LOAD_OP_CLEAR
}

/**
 * Maps a portable depth load operation to its Vulkan load operation.
 */
internal fun dev.instrum.ether.render.api.DepthLoadOperation.toVkLoadOp(): UInt = when (this) {
    dev.instrum.ether.render.api.DepthLoadOperation.Load -> VK_ATTACHMENT_LOAD_OP_LOAD
    dev.instrum.ether.render.api.DepthLoadOperation.DontCare -> VK_ATTACHMENT_LOAD_OP_DONT_CARE
    is dev.instrum.ether.render.api.DepthLoadOperation.Clear -> VK_ATTACHMENT_LOAD_OP_CLEAR
}

/**
 * Maps a portable store operation to its Vulkan color attachment store operation.
 */
internal fun StoreOp.toVkStoreOp(): UInt = when (this) {
    StoreOp.Store -> VK_ATTACHMENT_STORE_OP_STORE
    StoreOp.Discard -> VK_ATTACHMENT_STORE_OP_DONT_CARE
}

/**
 * Maps a portable store operation to its Vulkan depth attachment store operation.
 */
internal fun StoreOp.toVkDepthStoreOp(): UInt = when (this) {
    StoreOp.Store -> VK_ATTACHMENT_STORE_OP_STORE
    StoreOp.Discard -> VK_ATTACHMENT_STORE_OP_DONT_CARE
}

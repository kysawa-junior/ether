@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkAllocateCommandBuffers
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkAllocateMemory
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkBeginCommandBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkBindBufferMemory
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkBindImageMemory
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdBeginRendering
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdBindIndexBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdBindPipeline
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdBindVertexBuffers
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdCopyBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdCopyBufferToImage
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdCopyImageToBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdDraw
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdDrawIndexed
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdEndRendering
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdPipelineBarrier
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdSetScissor
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCmdSetViewport
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateCommandPool
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateFence
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateGraphicsPipelines
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateImage
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateImageView
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreatePipelineLayout
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateSampler
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateShaderModule
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyCommandPool
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyFence
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyImage
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyImageView
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyPipeline
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyPipelineLayout
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroySampler
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyShaderModule
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDeviceWaitIdle
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkEndCommandBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkFlushMappedMemoryRanges
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkFreeCommandBuffers
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkFreeMemory
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetBufferMemoryRequirements
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetDeviceQueue
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetFenceStatus
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetImageMemoryRequirements
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkMapMemory
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkInvalidateMappedMemoryRanges
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkQueueSubmit
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkQueueWaitIdle
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkResetCommandBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkResetFences
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkUnmapMemory
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkWaitForFences
import dev.instrum.ether.render.vulkan.internal.vkc.VkDevice

/**
 * Vulkan entry points that require a logical device.
 */
internal class VulkanDeviceApi(
    private val getDeviceProcAddr: (String) -> dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkVoidFunction?,
) {
    /** `vkGetDeviceQueue`. */
    val getDeviceQueue: PFN_vkGetDeviceQueue = required("vkGetDeviceQueue")

    /** `vkDeviceWaitIdle`. */
    val deviceWaitIdle: PFN_vkDeviceWaitIdle = required("vkDeviceWaitIdle")

    /** `vkCreateBuffer`. */
    val createBuffer: PFN_vkCreateBuffer = required("vkCreateBuffer")

    /** `vkDestroyBuffer`. */
    val destroyBuffer: PFN_vkDestroyBuffer = required("vkDestroyBuffer")

    /** `vkGetBufferMemoryRequirements`. */
    val getBufferMemoryRequirements: PFN_vkGetBufferMemoryRequirements =
        required("vkGetBufferMemoryRequirements")

    /** `vkBindBufferMemory`. */
    val bindBufferMemory: PFN_vkBindBufferMemory = required("vkBindBufferMemory")

    /** `vkAllocateMemory`. */
    val allocateMemory: PFN_vkAllocateMemory = required("vkAllocateMemory")

    /** `vkFreeMemory`. */
    val freeMemory: PFN_vkFreeMemory = required("vkFreeMemory")

    /** `vkMapMemory`. */
    val mapMemory: PFN_vkMapMemory = required("vkMapMemory")

    /** `vkUnmapMemory`. */
    val unmapMemory: PFN_vkUnmapMemory = required("vkUnmapMemory")

    /** `vkFlushMappedMemoryRanges`. */
    val flushMappedMemoryRanges: PFN_vkFlushMappedMemoryRanges =
        required("vkFlushMappedMemoryRanges")

    /** `vkInvalidateMappedMemoryRanges`. */
    val invalidateMappedMemoryRanges: PFN_vkInvalidateMappedMemoryRanges =
        required("vkInvalidateMappedMemoryRanges")

    /** `vkCreateImage`. */
    val createImage: PFN_vkCreateImage = required("vkCreateImage")

    /** `vkDestroyImage`. */
    val destroyImage: PFN_vkDestroyImage = required("vkDestroyImage")

    /** `vkGetImageMemoryRequirements`. */
    val getImageMemoryRequirements: PFN_vkGetImageMemoryRequirements =
        required("vkGetImageMemoryRequirements")

    /** `vkBindImageMemory`. */
    val bindImageMemory: PFN_vkBindImageMemory = required("vkBindImageMemory")

    /** `vkCreateImageView`. */
    val createImageView: PFN_vkCreateImageView = required("vkCreateImageView")

    /** `vkDestroyImageView`. */
    val destroyImageView: PFN_vkDestroyImageView = required("vkDestroyImageView")

    /** `vkCreateSampler`. */
    val createSampler: PFN_vkCreateSampler = required("vkCreateSampler")

    /** `vkDestroySampler`. */
    val destroySampler: PFN_vkDestroySampler = required("vkDestroySampler")

    /** `vkCreateShaderModule`. */
    val createShaderModule: PFN_vkCreateShaderModule = required("vkCreateShaderModule")

    /** `vkDestroyShaderModule`. */
    val destroyShaderModule: PFN_vkDestroyShaderModule = required("vkDestroyShaderModule")

    /** `vkCreatePipelineLayout`. */
    val createPipelineLayout: PFN_vkCreatePipelineLayout = required("vkCreatePipelineLayout")

    /** `vkDestroyPipelineLayout`. */
    val destroyPipelineLayout: PFN_vkDestroyPipelineLayout = required("vkDestroyPipelineLayout")

    /** `vkCreateGraphicsPipelines`. */
    val createGraphicsPipelines: PFN_vkCreateGraphicsPipelines =
        required("vkCreateGraphicsPipelines")

    /** `vkDestroyPipeline`. */
    val destroyPipeline: PFN_vkDestroyPipeline = required("vkDestroyPipeline")

    /** `vkCreateCommandPool`. */
    val createCommandPool: PFN_vkCreateCommandPool = required("vkCreateCommandPool")

    /** `vkDestroyCommandPool`. */
    val destroyCommandPool: PFN_vkDestroyCommandPool = required("vkDestroyCommandPool")

    /** `vkAllocateCommandBuffers`. */
    val allocateCommandBuffers: PFN_vkAllocateCommandBuffers = required("vkAllocateCommandBuffers")

    /** `vkFreeCommandBuffers`. */
    val freeCommandBuffers: PFN_vkFreeCommandBuffers = required("vkFreeCommandBuffers")

    /** `vkBeginCommandBuffer`. */
    val beginCommandBuffer: PFN_vkBeginCommandBuffer = required("vkBeginCommandBuffer")

    /** `vkEndCommandBuffer`. */
    val endCommandBuffer: PFN_vkEndCommandBuffer = required("vkEndCommandBuffer")

    /** `vkResetCommandBuffer`. */
    val resetCommandBuffer: PFN_vkResetCommandBuffer = required("vkResetCommandBuffer")

    /** `vkCmdBeginRendering`. */
    val cmdBeginRendering: PFN_vkCmdBeginRendering = required("vkCmdBeginRendering")

    /** `vkCmdEndRendering`. */
    val cmdEndRendering: PFN_vkCmdEndRendering = required("vkCmdEndRendering")

    /** `vkCmdBindPipeline`. */
    val cmdBindPipeline: PFN_vkCmdBindPipeline = required("vkCmdBindPipeline")

    /** `vkCmdBindVertexBuffers`. */
    val cmdBindVertexBuffers: PFN_vkCmdBindVertexBuffers = required("vkCmdBindVertexBuffers")

    /** `vkCmdBindIndexBuffer`. */
    val cmdBindIndexBuffer: PFN_vkCmdBindIndexBuffer = required("vkCmdBindIndexBuffer")

    /** `vkCmdSetViewport`. */
    val cmdSetViewport: PFN_vkCmdSetViewport = required("vkCmdSetViewport")

    /** `vkCmdSetScissor`. */
    val cmdSetScissor: PFN_vkCmdSetScissor = required("vkCmdSetScissor")

    /** `vkCmdDraw`. */
    val cmdDraw: PFN_vkCmdDraw = required("vkCmdDraw")

    /** `vkCmdDrawIndexed`. */
    val cmdDrawIndexed: PFN_vkCmdDrawIndexed = required("vkCmdDrawIndexed")

    /** `vkCmdCopyBufferToImage`. */
    val cmdCopyBufferToImage: PFN_vkCmdCopyBufferToImage = required("vkCmdCopyBufferToImage")

    /** `vkCmdCopyBuffer`. */
    val cmdCopyBuffer: PFN_vkCmdCopyBuffer = required("vkCmdCopyBuffer")

    /** `vkCmdCopyImageToBuffer`. */
    val cmdCopyImageToBuffer: PFN_vkCmdCopyImageToBuffer = required("vkCmdCopyImageToBuffer")

    /** `vkCmdPipelineBarrier`. */
    val cmdPipelineBarrier: PFN_vkCmdPipelineBarrier = required("vkCmdPipelineBarrier")

    /** `vkQueueSubmit`. */
    val queueSubmit: PFN_vkQueueSubmit = required("vkQueueSubmit")

    /** `vkQueueWaitIdle`. */
    val queueWaitIdle: PFN_vkQueueWaitIdle = required("vkQueueWaitIdle")

    /** `vkCreateFence`. */
    val createFence: PFN_vkCreateFence = required("vkCreateFence")

    /** `vkDestroyFence`. */
    val destroyFence: PFN_vkDestroyFence = required("vkDestroyFence")

    /** `vkGetFenceStatus`. */
    val getFenceStatus: PFN_vkGetFenceStatus = required("vkGetFenceStatus")

    /** `vkWaitForFences`. */
    val waitForFences: PFN_vkWaitForFences = required("vkWaitForFences")

    /** `vkResetFences`. */
    val resetFences: PFN_vkResetFences = required("vkResetFences")

    private inline fun <reified T> required(name: String): T =
        loadVulkanFunction<T>(getDeviceProcAddr(name), name)
}

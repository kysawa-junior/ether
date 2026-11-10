@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.vulkan.internal.vkc.VkBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.VkDeviceMemory
import dev.instrum.ether.render.vulkan.internal.vkc.VkImage
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageView
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipeline
import dev.instrum.ether.render.vulkan.internal.vkc.VkSampler
import dev.instrum.ether.render.vulkan.internal.vkc.VkShaderModule
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Base type for every resource owned by a Vulkan backend device.
 */
internal sealed interface VulkanResource

/**
 * A GPU buffer with its bound device memory.
 */
internal class VulkanBufferResource(
    val buffer: VkBuffer,
    val memory: VkDeviceMemory,
    val size: ULong,
    /** Size of the bound device memory; it can be larger than [size] because of alignment. */
    val allocationSize: ULong,
    val descriptor: BufferDescriptor,
    val memoryTypeIndex: Int,
    val hostVisible: Boolean,
    val coherent: Boolean,
    /** Persistent mapping of [memory], or `null` when the buffer lives in device local memory. */
    val mapped: CPointer<ByteVar>?,
) : VulkanResource

/**
 * A GPU texture with its image view and bound device memory.
 */
internal class VulkanTextureResource(
    val image: VkImage,
    val view: VkImageView,
    val memory: VkDeviceMemory,
    val descriptor: TextureDescriptor,
    val format: UInt,
    val aspectMask: UInt,
) : VulkanResource

/**
 * A texture sampler.
 */
internal class VulkanSamplerResource(
    val sampler: VkSampler,
) : VulkanResource

/**
 * A compiled SPIR-V shader module.
 */
internal class VulkanShaderResource(
    val module: VkShaderModule,
    val stage: ShaderStage,
    val entryPoint: String,
) : VulkanResource

/**
 * A compiled graphics pipeline.
 */
internal class VulkanPipelineResource(
    val pipeline: VkPipeline,
) : VulkanResource

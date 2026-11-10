@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceType

/**
 * A Vulkan memory type copied out of the driver owned memory properties.
 */
internal data class VulkanMemoryType(
    val propertyFlags: UInt,
    val heapIndex: UInt,
)

/**
 * A Vulkan memory heap copied out of the driver owned memory properties.
 */
internal data class VulkanMemoryHeap(
    val size: ULong,
    val flags: UInt,
)

/**
 * The immutable description of a physical device selected by the backend.
 *
 * Every value is copied out of the driver during probing so no driver owned pointer outlives the
 * probe.
 */
internal data class VulkanPhysicalDeviceSummary(
    val name: String,
    val apiVersion: UInt,
    val driverVersion: UInt,
    val vendorId: UInt,
    val deviceType: VkPhysicalDeviceType,
    val queueFamilyIndex: UInt,
    val queueCount: UInt,
    val timestampValidBits: UInt,
    val supportsDynamicRendering: Boolean,
    val supportsSamplerAnisotropy: Boolean,
    val supportsDepthClamp: Boolean,
    val maxSamplerAnisotropy: Float,
    val maxImageDimension1D: UInt,
    val maxImageDimension2D: UInt,
    val maxImageDimension3D: UInt,
    val maxImageDimensionCube: UInt,
    val maxImageArrayLayers: UInt,
    val maxColorAttachments: UInt,
    val maxVertexInputBindings: UInt,
    val maxVertexInputAttributes: UInt,
    val maxVertexInputBindingStride: UInt,
    val maxBoundDescriptorSets: UInt,
    val maxPerStageDescriptorSamplers: UInt,
    val maxPerStageDescriptorUniformBuffers: UInt,
    val maxPerStageDescriptorStorageBuffers: UInt,
    val maxPerStageDescriptorSampledImages: UInt,
    val maxUniformBufferRange: UInt,
    val maxStorageBufferRange: UInt,
    val maxPushConstantsSize: UInt,
    val nonCoherentAtomSize: ULong,
    val maxComputeWorkGroupSize: List<UInt>,
    val maxComputeWorkGroupInvocations: UInt,
    val framebufferColorSampleCounts: UInt,
    val framebufferDepthSampleCounts: UInt,
    val framebufferNoAttachmentsSampleCounts: UInt,
    val memoryTypes: List<VulkanMemoryType>,
    val memoryHeaps: List<VulkanMemoryHeap>,
)

/**
 * Returns the human readable name of the physical device class.
 */
internal fun VulkanPhysicalDeviceSummary.deviceTypeName(): String = when (deviceType) {
    dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU"
    dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE_GPU"
    dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL_GPU"
    dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU"
    else -> "OTHER"
}

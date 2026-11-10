package dev.instrum.ether.render.lwjgl.internal

/**
 * A Vulkan memory type copied out of the driver owned memory properties.
 */
internal data class LwjglMemoryType(
    val propertyFlags: Int,
    val heapIndex: Int,
)

/**
 * A Vulkan memory heap copied out of the driver owned memory properties.
 */
internal data class LwjglMemoryHeap(
    val size: Long,
    val flags: Int,
)

/**
 * The immutable description of a physical device selected by the backend.
 *
 * Every value is copied out of the driver during probing so no driver owned pointer outlives the
 * probe. Values use JVM sized types (Int/Long) because LWJGL reports Vulkan numeric properties as
 * JVM sized values.
 */
internal data class LwjglPhysicalDeviceSummary(
    val name: String,
    val apiVersion: Int,
    val driverVersion: Int,
    val vendorId: Int,
    val deviceType: Int,
    val queueFamilyIndex: Int,
    val queueCount: Int,
    val timestampValidBits: Int,
    val supportsDynamicRendering: Boolean,
    val supportsSamplerAnisotropy: Boolean,
    val supportsDepthClamp: Boolean,
    val maxSamplerAnisotropy: Float,
    val maxImageDimension1D: Int,
    val maxImageDimension2D: Int,
    val maxImageDimension3D: Int,
    val maxImageDimensionCube: Int,
    val maxImageArrayLayers: Int,
    val maxColorAttachments: Int,
    val maxVertexInputBindings: Int,
    val maxVertexInputAttributes: Int,
    val maxVertexInputBindingStride: Int,
    val maxBoundDescriptorSets: Int,
    val maxPerStageDescriptorSamplers: Int,
    val maxPerStageDescriptorUniformBuffers: Int,
    val maxPerStageDescriptorStorageBuffers: Int,
    val maxPerStageDescriptorSampledImages: Int,
    val maxUniformBufferRange: Int,
    val maxStorageBufferRange: Int,
    val maxPushConstantsSize: Int,
    val nonCoherentAtomSize: Long,
    val maxComputeWorkGroupSize: List<Int>,
    val maxComputeWorkGroupInvocations: Int,
    val framebufferColorSampleCounts: Int,
    val framebufferDepthSampleCounts: Int,
    val framebufferNoAttachmentsSampleCounts: Int,
    val memoryTypes: List<LwjglMemoryType>,
    val memoryHeaps: List<LwjglMemoryHeap>,
)

/**
 * Returns the human readable name of the physical device class.
 */
internal fun LwjglPhysicalDeviceSummary.deviceTypeName(): String = when (deviceType) {
    VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU"
    VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE_GPU"
    VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL_GPU"
    VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU"
    else -> "OTHER"
}

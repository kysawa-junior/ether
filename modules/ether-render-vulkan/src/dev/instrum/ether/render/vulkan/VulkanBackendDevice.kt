@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan

import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BlendFactor
import dev.instrum.ether.render.api.BlendOperation
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.ColorChannel
import dev.instrum.ether.render.api.ColorWriteMask
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.SamplerDescriptor
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureDimension
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.api.VertexStepMode
import dev.instrum.ether.render.vulkan.internal.VulkanBufferResource
import dev.instrum.ether.render.vulkan.internal.VulkanCommandRecorder
import dev.instrum.ether.render.vulkan.internal.VulkanDeviceApi
import dev.instrum.ether.render.vulkan.internal.VulkanInstanceApi
import dev.instrum.ether.render.vulkan.internal.VulkanLibrary
import dev.instrum.ether.render.vulkan.internal.VulkanMemoryAllocator
import dev.instrum.ether.render.vulkan.internal.VulkanPhysicalDeviceSummary
import dev.instrum.ether.render.vulkan.internal.VulkanPipelineResource
import dev.instrum.ether.render.vulkan.internal.VulkanProbe
import dev.instrum.ether.render.vulkan.internal.VulkanSamplerResource
import dev.instrum.ether.render.vulkan.internal.VulkanShaderResource
import dev.instrum.ether.render.vulkan.internal.VulkanTextureResource
import dev.instrum.ether.render.vulkan.internal.checkVkResult
import dev.instrum.ether.render.vulkan.internal.deviceTypeName
import dev.instrum.ether.render.vulkan.internal.formatVersion
import dev.instrum.ether.render.vulkan.internal.nativeString
import dev.instrum.ether.render.vulkan.internal.readBytes
import dev.instrum.ether.render.vulkan.internal.toVkAddressMode
import dev.instrum.ether.render.vulkan.internal.toVkBlendFactor
import dev.instrum.ether.render.vulkan.internal.toVkBlendOp
import dev.instrum.ether.render.vulkan.internal.toVkCompareOp
import dev.instrum.ether.render.vulkan.internal.toVkCullMode
import dev.instrum.ether.render.vulkan.internal.toVkFilter
import dev.instrum.ether.render.vulkan.internal.toVkFormat
import dev.instrum.ether.render.vulkan.internal.toVkFrontFace
import dev.instrum.ether.render.vulkan.internal.toVkMipmapMode
import dev.instrum.ether.render.vulkan.internal.toVkTopology
import dev.instrum.ether.render.vulkan.internal.withNativeString
import dev.instrum.ether.render.vulkan.internal.writeBytes
import dev.instrum.ether.render.vulkan.internal.zeroed
import dev.instrum.ether.render.vulkan.internal.zeroedArray
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_INDEX_BUFFER_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_TRANSFER_DST_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COLOR_COMPONENT_A_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COLOR_COMPONENT_B_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COLOR_COMPONENT_G_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COLOR_COMPONENT_R_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMMAND_BUFFER_LEVEL_PRIMARY
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPARE_OP_NEVER
import dev.instrum.ether.render.vulkan.internal.vkc.VK_COMPONENT_SWIZZLE_IDENTITY
import dev.instrum.ether.render.vulkan.internal.vkc.VK_DYNAMIC_STATE_SCISSOR
import dev.instrum.ether.render.vulkan.internal.vkc.VK_DYNAMIC_STATE_VIEWPORT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_UNDEFINED
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_ASPECT_COLOR_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_ASPECT_DEPTH_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_ASPECT_STENCIL_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_LAYOUT_UNDEFINED
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_TILING_OPTIMAL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_TYPE_1D
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_TYPE_2D
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_TYPE_3D
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_USAGE_SAMPLED_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_USAGE_STORAGE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_USAGE_TRANSFER_DST_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_VIEW_TYPE_1D
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_VIEW_TYPE_2D
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_VIEW_TYPE_2D_ARRAY
import dev.instrum.ether.render.vulkan.internal.vkc.VK_IMAGE_VIEW_TYPE_3D
import dev.instrum.ether.render.vulkan.internal.vkc.VK_LOGIC_OP_COPY
import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_POLYGON_MODE_FILL
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SHADER_STAGE_FRAGMENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SHADER_STAGE_VERTEX_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SHARING_MODE_EXCLUSIVE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_SUBMIT_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SUCCESS
import dev.instrum.ether.render.vulkan.internal.vkc.VK_VERTEX_INPUT_RATE_INSTANCE
import dev.instrum.ether.render.vulkan.internal.vkc.VK_VERTEX_INPUT_RATE_VERTEX
import dev.instrum.ether.render.vulkan.internal.vkc.VK_WHOLE_SIZE
import dev.instrum.ether.render.vulkan.internal.vkc.VkBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.VkBufferCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkBufferVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandBuffer
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandBufferAllocateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandBufferVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandPool
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandPoolCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkCommandPoolVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkDevice
import dev.instrum.ether.render.vulkan.internal.vkc.VkDeviceCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkDeviceMemory
import dev.instrum.ether.render.vulkan.internal.vkc.VkDeviceMemoryVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkDeviceQueueCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkDeviceVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkFence
import dev.instrum.ether.render.vulkan.internal.vkc.VkFenceCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkFenceVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkGraphicsPipelineCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkImage
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageView
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageViewCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageViewVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkImageVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkMappedMemoryRange
import dev.instrum.ether.render.vulkan.internal.vkc.VkMemoryAllocateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkMemoryRequirements
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDevice
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceFeatures2
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceVulkan13Features
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipeline
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineColorBlendAttachmentState
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineColorBlendStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineDepthStencilStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineDynamicStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineInputAssemblyStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineLayout
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineLayoutCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineLayoutVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineMultisampleStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineRasterizationStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineRenderingCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineShaderStageCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineVertexInputStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkPipelineViewportStateCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkQueue
import dev.instrum.ether.render.vulkan.internal.vkc.VkQueueVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkSampler
import dev.instrum.ether.render.vulkan.internal.vkc.VkSamplerCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkSamplerVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkShaderModule
import dev.instrum.ether.render.vulkan.internal.vkc.VkShaderModuleCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkShaderModuleVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkSubmitInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkVertexInputAttributeDescription
import dev.instrum.ether.render.vulkan.internal.vkc.VkVertexInputBindingDescription
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.invoke
import kotlinx.cinterop.get
import kotlinx.cinterop.NativePlacement
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value

/**
 * A real Vulkan device created on a probed physical device.
 *
 * The backend opens the Vulkan loader dynamically and never links against Vulkan at build time,
 * so a binary built anywhere still starts on a machine without a driver and reports the backend
 * as unavailable (the availability probe is implemented by [VulkanProbe]).
 *
 * Resource model:
 * - Buffers declared `Uniform` or `Storage` live in device local memory and are written through a
 *   staging upload that runs to completion before [writeBuffer] returns.
 * - Every other buffer lives in host visible memory with a persistent mapping, so engine writes
 *   reach GPU memory directly. Each host write is ordered against queued GPU work by waiting for
 *   device idle first, which prevents races under the strict single threaded owner model.
 * - Textures live in device local memory without host mappings, and images rest in
 *   `VK_IMAGE_LAYOUT_GENERAL` between render passes; the command recorder inserts the layout
 *   transitions so every command buffer is self contained.
 *
 * Submission model: [submit] places one `VkSubmitInfo` per command buffer in a single
 * `vkQueueSubmit` call guarded by one fence. Entries of a submit call execute in submission order
 * on the queue, so ordered entries need no explicit semaphores. Command buffers are freed only
 * after their fence signals, and submissions still pending cannot be observed as complete.
 *
 * Lifetime: tokens are unique across all resource kinds of this device. The portable core calls
 * [destroy] only for resources that are not referenced by an in-flight submission, and ownership
 * of command buffer tokens transfers to this device at [submit]. Completed submissions are reaped
 * lazily; [isSubmissionComplete] reports `true` for tokens whose fence already signaled.
 *
 * The device is single threaded, matching the engine owner model. Unknown tokens raise
 * [IllegalArgumentException], mirroring the null backend reference implementation.
 */
public class VulkanBackendDevice internal constructor(
    /** Debug label supplied by the core when the device was created. */
    public val label: String,
    /** Identification of the physical device backing this device. */
    public val info: VulkanDeviceInfo,
    /** Capabilities reported by the creating backend. */
    public val capabilities: GraphicsCapabilities,
    private val library: VulkanLibrary,
    private val instanceApi: VulkanInstanceApi,
    private val instance: dev.instrum.ether.render.vulkan.internal.vkc.VkInstance,
    private val physicalDevice: VkPhysicalDevice,
    private val summary: VulkanPhysicalDeviceSummary,
) : RenderBackendDevice {

    // Property order is initialization order: the logical device must exist before the device
    // level entry points can be bound, and the queue, pool, and layout come from those.
    private val logicalDevice: VkDevice = createLogicalDevice()

    private val deviceApi: VulkanDeviceApi = VulkanDeviceApi { name ->
        instanceApi.lookupDevice(logicalDevice, name)
    }

    private val queue: VkQueue = fetchQueue()
    private val commandPool: VkCommandPool = createCommandPool()
    private val pipelineLayout: VkPipelineLayout = createEmptyPipelineLayout()
    private val allocator: VulkanMemoryAllocator = VulkanMemoryAllocator(summary.memoryTypes)
    private val recorder: VulkanCommandRecorder = VulkanCommandRecorder(
        api = deviceApi,
        resolveBuffer = ::resolveBuffer,
        resolveTexture = ::resolveTexture,
        resolvePipeline = ::resolvePipeline,
    )

    private val buffers: MutableMap<Long, VulkanBufferResource> = LinkedHashMap()
    private val textures: MutableMap<Long, VulkanTextureResource> = LinkedHashMap()
    private val samplers: MutableMap<Long, VulkanSamplerResource> = LinkedHashMap()
    private val shaders: MutableMap<Long, VulkanShaderResource> = LinkedHashMap()
    private val pipelines: MutableMap<Long, VulkanPipelineResource> = LinkedHashMap()
    private val commandBuffers: MutableMap<Long, VkCommandBuffer> = LinkedHashMap()
    private val inFlight: MutableMap<Long, InFlightSubmission> = LinkedHashMap()

    private var nextTokenValue: Long = 1L
    private var lastSubmissionToken: Long = 0L
    private var closed: Boolean = false

    /** One queue submission referencing the fence and command buffers it covers. */
    private class InFlightSubmission(
        val fence: VkFence,
        val commandBuffers: List<VkCommandBuffer>,
    )

    /**
     * Device memory allocated from the driver together with the host mapping metadata.
     */
    private class VulkanMemoryAllocation(
        val memory: VkDeviceMemory,
        val allocationSize: ULong,
        val memoryTypeIndex: Int,
        val hostVisible: Boolean,
        val coherent: Boolean,
    )

    internal companion object {
        /**
         * Takes over ownership of the probe: this device inherits the loaded loader and the
         * instance and releases them when [close] runs.
         */
        internal fun fromProbe(probe: VulkanProbe, label: String): VulkanBackendDevice =
            VulkanBackendDevice(
                label = label,
                info = VulkanDeviceInfo(
                    physicalDeviceName = probe.summary.name,
                    apiVersion = formatVersion(probe.summary.apiVersion),
                    driverVersion = probe.summary.driverVersion,
                    vendorId = probe.summary.vendorId,
                    deviceType = probe.summary.deviceTypeName(),
                    queueFamilyIndex = probe.summary.queueFamilyIndex,
                    queueCount = probe.summary.queueCount,
                ),
                capabilities = probe.capabilities,
                library = probe.library,
                instanceApi = probe.instanceApi,
                instance = probe.instance,
                physicalDevice = probe.physicalDevice,
                summary = probe.summary,
            )

        /** First word of every valid SPIR-V module, stored little endian in the byte stream. */
        private const val SPIRV_MAGIC_NUMBER: UInt = 0x07230203u
    }

    // ---------------------------------------------------------------------
    // Resource creation
    // ---------------------------------------------------------------------

    override fun createBuffer(descriptor: BufferDescriptor): Long {
        ensureOpen()
        val resource = allocateBufferResource(descriptor)
        val token = nextToken()
        buffers[token] = resource
        return token
    }

    override fun writeBuffer(bufferToken: Long, offsetBytes: Long, data: ByteArray) {
        ensureOpen()
        if (data.isEmpty()) return
        val resource = resolveBuffer(bufferToken)
        if (offsetBytes < 0L || offsetBytes + data.size.toLong() > resource.size.toLong()) {
            throw IllegalArgumentException(
                "Write of ${data.size} bytes at offset $offsetBytes exceeds the " +
                    "${resource.size.toLong()}-byte buffer",
            )
        }
        val mapped = resource.mapped
        if (mapped != null) {
            // The engine may overwrite a buffer that a submission is still consuming. Waiting for
            // device idle orders every host write against queued work under the single threaded
            // owner model.
            waitForDeviceIdle("direct host write")
            mapped.writeBytes(data, offsetBytes.toInt())
            if (!resource.coherent) {
                flushMappedMemory(resource.memory)
            }
        } else {
            stageBufferUpload(resource, offsetBytes, data)
        }
    }

    override fun createTexture(descriptor: TextureDescriptor): Long {
        ensureOpen()
        val resource = memScoped {
            val format = descriptor.format.toVkFormat()
            val aspectMask = descriptor.format.toAspectMask()

            val createInfo = zeroed<VkImageCreateInfo>()
            createInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
            when (descriptor.dimension) {
                TextureDimension.D1 -> {
                    createInfo.imageType = VK_IMAGE_TYPE_1D
                    createInfo.extent.width = descriptor.width.toUInt()
                    createInfo.extent.height = 1u
                    createInfo.extent.depth = 1u
                    createInfo.arrayLayers = 1u
                }

                TextureDimension.D2 -> {
                    createInfo.imageType = VK_IMAGE_TYPE_2D
                    createInfo.extent.width = descriptor.width.toUInt()
                    createInfo.extent.height = descriptor.height.toUInt()
                    createInfo.extent.depth = 1u
                    createInfo.arrayLayers = descriptor.depthOrLayers.toUInt()
                }

                TextureDimension.D3 -> {
                    createInfo.imageType = VK_IMAGE_TYPE_3D
                    createInfo.extent.width = descriptor.width.toUInt()
                    createInfo.extent.height = descriptor.height.toUInt()
                    createInfo.extent.depth = descriptor.depthOrLayers.toUInt()
                    createInfo.arrayLayers = 1u
                }
            }
            createInfo.format = format
            createInfo.mipLevels = descriptor.mipLevels.toUInt()
            createInfo.samples = descriptor.sampleCount.toUInt()
            createInfo.tiling = VK_IMAGE_TILING_OPTIMAL
            createInfo.usage = descriptor.usage.toVkImageUsage()
            createInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE
            // Vulkan only permits undefined or preinitialized initial layouts. The recorder
            // transitions every image with explicit barriers before each use, so contents before
            // the first write are never read.
            createInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED

            val imageVar = zeroed<VkImageVar>()
            checkVkResult(
                deviceApi.createImage.invoke(logicalDevice, createInfo.ptr, null, imageVar.ptr),
                "vkCreateImage",
            )
            val image = imageVar.value
                ?: throw VulkanException("vkCreateImage returned a null image handle")

            val requirements = zeroed<VkMemoryRequirements>()
            deviceApi.getImageMemoryRequirements.invoke(logicalDevice, image, requirements.ptr)
            val allocation = allocateMemory(
                requirements,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                "texture '${descriptor.label ?: "unnamed"}'",
            )
            checkVkResult(
                deviceApi.bindImageMemory.invoke(logicalDevice, image, allocation.memory, 0uL),
                "vkBindImageMemory",
            )

            val viewType = when (descriptor.dimension) {
                TextureDimension.D1 -> VK_IMAGE_VIEW_TYPE_1D
                TextureDimension.D2 ->
                    if (descriptor.depthOrLayers > 1) {
                        VK_IMAGE_VIEW_TYPE_2D_ARRAY
                    } else {
                        VK_IMAGE_VIEW_TYPE_2D
                    }

                TextureDimension.D3 -> VK_IMAGE_VIEW_TYPE_3D
            }
            val viewInfo = zeroed<VkImageViewCreateInfo>()
            viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
            viewInfo.image = image
            viewInfo.viewType = viewType
            viewInfo.format = format
            viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY
            viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY
            viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY
            viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY
            viewInfo.subresourceRange.aspectMask = aspectMask
            viewInfo.subresourceRange.baseMipLevel = 0u
            viewInfo.subresourceRange.levelCount = descriptor.mipLevels.toUInt()
            viewInfo.subresourceRange.baseArrayLayer = 0u
            viewInfo.subresourceRange.layerCount =
                if (descriptor.dimension == TextureDimension.D3) {
                    1u
                } else {
                    descriptor.depthOrLayers.toUInt()
                }

            val viewVar = zeroed<VkImageViewVar>()
            checkVkResult(
                deviceApi.createImageView.invoke(logicalDevice, viewInfo.ptr, null, viewVar.ptr),
                "vkCreateImageView",
            )
            val view = viewVar.value
                ?: throw VulkanException("vkCreateImageView returned a null image view handle")
            VulkanTextureResource(
                image = image,
                view = view,
                memory = allocation.memory,
                descriptor = descriptor,
                format = format,
                aspectMask = aspectMask,
            )
        }
        val token = nextToken()
        textures[token] = resource
        return token
    }

    override fun createSampler(descriptor: SamplerDescriptor): Long {
        ensureOpen()
        val resource = memScoped {
            val anisotropySupported = summary.supportsSamplerAnisotropy &&
                summary.maxSamplerAnisotropy > 1f
            if (descriptor.maxAnisotropy > 1 && !anisotropySupported) {
                throw VulkanException(
                    "Sampler requests anisotropy ${descriptor.maxAnisotropy} but the device " +
                        "does not support anisotropic filtering",
                )
            }
            val createInfo = zeroed<VkSamplerCreateInfo>()
            createInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO
            createInfo.magFilter = descriptor.magFilter.toVkFilter()
            createInfo.minFilter = descriptor.minFilter.toVkFilter()
            createInfo.mipmapMode = descriptor.mipmapFilter.toVkMipmapMode()
            createInfo.addressModeU = descriptor.addressModeU.toVkAddressMode()
            createInfo.addressModeV = descriptor.addressModeV.toVkAddressMode()
            createInfo.addressModeW = descriptor.addressModeW.toVkAddressMode()
            createInfo.mipLodBias = 0f
            createInfo.anisotropyEnable =
                if (anisotropySupported && descriptor.maxAnisotropy > 1) 1u else 0u
            createInfo.maxAnisotropy = if (anisotropySupported) {
                descriptor.maxAnisotropy.toFloat().coerceIn(1f, summary.maxSamplerAnisotropy)
            } else {
                1f
            }
            createInfo.compareEnable = 0u
            createInfo.compareOp = VK_COMPARE_OP_NEVER
            createInfo.minLod = descriptor.lodMinClamp
            createInfo.maxLod = descriptor.lodMaxClamp
            createInfo.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
            createInfo.unnormalizedCoordinates = 0u

            val samplerVar = zeroed<VkSamplerVar>()
            checkVkResult(
                deviceApi.createSampler.invoke(logicalDevice, createInfo.ptr, null, samplerVar.ptr),
                "vkCreateSampler",
            )
            VulkanSamplerResource(
                sampler = samplerVar.value
                    ?: throw VulkanException("vkCreateSampler returned a null sampler handle"),
            )
        }
        val token = nextToken()
        samplers[token] = resource
        return token
    }

    override fun createShaderModule(descriptor: ShaderModuleDescriptor): Long {
        ensureOpen()
        if (descriptor.stage == ShaderStage.Compute) {
            throw VulkanException(
                "The Vulkan backend does not report compute support; compute shader modules " +
                    "cannot be created yet",
            )
        }
        if (descriptor.code.size % 4 != 0) {
            throw IllegalArgumentException(
                "SPIR-V module must be a multiple of four bytes, was ${descriptor.code.size}",
            )
        }
        ensureSpirvMagic(descriptor.code)
        val resource = memScoped {
            val wordCount = descriptor.code.size / 4
            val words = zeroedArray<UIntVar>(wordCount)
            for (index in 0 until wordCount) {
                words[index] = readSpirvWord(descriptor.code, index)
            }
            val createInfo = zeroed<VkShaderModuleCreateInfo>()
            createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO
            createInfo.codeSize = descriptor.code.size.toULong()
            createInfo.pCode = words

            val moduleVar = zeroed<VkShaderModuleVar>()
            checkVkResult(
                deviceApi.createShaderModule.invoke(logicalDevice, createInfo.ptr, null, moduleVar.ptr),
                "vkCreateShaderModule",
            )
            VulkanShaderResource(
                module = moduleVar.value
                    ?: throw VulkanException(
                        "vkCreateShaderModule returned a null shader module handle",
                    ),
                stage = descriptor.stage,
                entryPoint = descriptor.entryPoint,
            )
        }
        val token = nextToken()
        shaders[token] = resource
        return token
    }

    override fun createRenderPipeline(
        descriptor: RenderPipelineDescriptor,
        vertexShaderToken: Long,
        fragmentShaderToken: Long?,
    ): Long {
        ensureOpen()
        val vertexShader = resolveShader(vertexShaderToken, ShaderStage.Vertex)
        val fragmentShader = fragmentShaderToken?.let { resolveShader(it, ShaderStage.Fragment) }
        val targets = descriptor.fragment?.targets ?: emptyList()
        if (descriptor.multisample.count > 1) {
            throw VulkanException(
                "Pipeline requests ${descriptor.multisample.count} samples but the device " +
                    "reports a maximum sample count of ${capabilities.limits.maxSampleCount}",
            )
        }
        if (descriptor.primitive.unclippedDepth && !summary.supportsDepthClamp) {
            throw VulkanException(
                "Pipeline requests unclipped depth but the device does not support depth clamp",
            )
        }

        val resource = memScoped {
            val stages = listOfNotNull(vertexShader, fragmentShader)
            val stageInfos = zeroedArray<VkPipelineShaderStageCreateInfo>(stages.size)
            stages.forEachIndexed { index, shader ->
                val info = stageInfos[index]
                info.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO
                info.stage = when (shader.stage) {
                    ShaderStage.Vertex -> VK_SHADER_STAGE_VERTEX_BIT
                    ShaderStage.Fragment -> VK_SHADER_STAGE_FRAGMENT_BIT
                    ShaderStage.Compute -> throw VulkanException(
                        "Compute stages cannot be used in a render pipeline",
                    )
                }
                info.module = shader.module
                info.pName = nativeString(shader.entryPoint)
            }

            val bufferLayouts = descriptor.vertex.buffers
            val bindingDescriptions =
                zeroedArrayOrEmptyList<VkVertexInputBindingDescription>(bufferLayouts.size)
            bufferLayouts.forEachIndexed { index, layout ->
                val description = bindingDescriptions!![index]
                description.binding = index.toUInt()
                description.stride = layout.strideBytes.toUInt()
                description.inputRate = when (layout.stepMode) {
                    VertexStepMode.Vertex -> VK_VERTEX_INPUT_RATE_VERTEX
                    VertexStepMode.Instance -> VK_VERTEX_INPUT_RATE_INSTANCE
                }
            }
            val attributeCount = bufferLayouts.sumOf { layout -> layout.attributes.size }
            val attributeDescriptions =
                zeroedArrayOrEmptyList<VkVertexInputAttributeDescription>(attributeCount)
            var attributeCursor = 0
            bufferLayouts.forEachIndexed { bindingIndex, layout ->
                layout.attributes.forEach { attribute ->
                    val description = attributeDescriptions!![attributeCursor]
                    description.location = attribute.location.toUInt()
                    description.binding = bindingIndex.toUInt()
                    description.format = attribute.format.toVkFormat()
                    description.offset = attribute.offsetBytes.toUInt()
                    attributeCursor++
                }
            }
            val vertexInputState = zeroed<VkPipelineVertexInputStateCreateInfo>()
            vertexInputState.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
            vertexInputState.vertexBindingDescriptionCount = bufferLayouts.size.toUInt()
            vertexInputState.pVertexBindingDescriptions = bindingDescriptions
            vertexInputState.vertexAttributeDescriptionCount = attributeCount.toUInt()
            vertexInputState.pVertexAttributeDescriptions = attributeDescriptions

            val inputAssemblyState = zeroed<VkPipelineInputAssemblyStateCreateInfo>()
            inputAssemblyState.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
            inputAssemblyState.topology = descriptor.primitive.topology.toVkTopology()
            inputAssemblyState.primitiveRestartEnable = 0u

            val viewportState = zeroed<VkPipelineViewportStateCreateInfo>()
            viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO
            viewportState.viewportCount = 1u
            viewportState.pViewports = null
            viewportState.scissorCount = 1u
            viewportState.pScissors = null

            val rasterizationState = zeroed<VkPipelineRasterizationStateCreateInfo>()
            rasterizationState.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO
            rasterizationState.depthClampEnable =
                if (descriptor.primitive.unclippedDepth) 1u else 0u
            rasterizationState.rasterizerDiscardEnable = 0u
            rasterizationState.polygonMode = VK_POLYGON_MODE_FILL
            rasterizationState.cullMode = descriptor.primitive.cullMode.toVkCullMode()
            rasterizationState.frontFace = descriptor.primitive.frontFace.toVkFrontFace()
            rasterizationState.lineWidth = 1f

            val multisampleState = zeroed<VkPipelineMultisampleStateCreateInfo>()
            multisampleState.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
            multisampleState.rasterizationSamples = descriptor.multisample.count.toUInt()
            multisampleState.sampleShadingEnable = 0u
            multisampleState.minSampleShading = 1f
            multisampleState.pSampleMask = null
            multisampleState.alphaToCoverageEnable =
                if (descriptor.multisample.alphaToCoverageEnabled) 1u else 0u

            val depthStencilState = descriptor.depthStencil?.let { state ->
                zeroed<VkPipelineDepthStencilStateCreateInfo>().also { info ->
                    info.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO
                    info.depthTestEnable = 1u
                    info.depthWriteEnable = if (state.depthWriteEnabled) 1u else 0u
                    info.depthCompareOp = state.depthCompare.toVkCompareOp()
                    info.depthBoundsTestEnable = 0u
                    info.stencilTestEnable = 0u
                }
            }

            val colorBlendState = if (targets.isEmpty()) {
                null
            } else {
                val attachmentStates =
                    zeroedArray<VkPipelineColorBlendAttachmentState>(targets.size)
                targets.forEachIndexed { index, target ->
                    val attachment = attachmentStates[index]
                    val blend = target.blend
                    if (blend != null) {
                        if (usesConstantColorFactor(blend)) {
                            throw VulkanException(
                                "ConstantColor blend factors are not supported by the Vulkan " +
                                    "backend; the portable API offers no way to set the " +
                                    "blend constants",
                            )
                        }
                        attachment.blendEnable = 1u
                        attachment.srcColorBlendFactor = blend.color.srcFactor.toVkBlendFactor()
                        attachment.dstColorBlendFactor = blend.color.dstFactor.toVkBlendFactor()
                        attachment.colorBlendOp = blend.color.operation.toVkBlendOp()
                        attachment.srcAlphaBlendFactor = blend.alpha.srcFactor.toVkBlendFactor()
                        attachment.dstAlphaBlendFactor = blend.alpha.dstFactor.toVkBlendFactor()
                        attachment.alphaBlendOp = blend.alpha.operation.toVkBlendOp()
                    } else {
                        attachment.blendEnable = 0u
                        attachment.srcColorBlendFactor = BlendFactor.One.toVkBlendFactor()
                        attachment.dstColorBlendFactor = BlendFactor.Zero.toVkBlendFactor()
                        attachment.colorBlendOp = BlendOperation.Add.toVkBlendOp()
                        attachment.srcAlphaBlendFactor = BlendFactor.One.toVkBlendFactor()
                        attachment.dstAlphaBlendFactor = BlendFactor.Zero.toVkBlendFactor()
                        attachment.alphaBlendOp = BlendOperation.Add.toVkBlendOp()
                    }
                    attachment.colorWriteMask = buildColorWriteMask(target.writeMask)
                }
                zeroed<VkPipelineColorBlendStateCreateInfo>().also { state ->
                    state.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
                    state.logicOpEnable = 0u
                    state.logicOp = VK_LOGIC_OP_COPY
                    state.attachmentCount = targets.size.toUInt()
                    state.pAttachments = attachmentStates
                }
            }

            val dynamicStates = zeroedArray<UIntVar>(2)
            dynamicStates[0] = VK_DYNAMIC_STATE_VIEWPORT
            dynamicStates[1] = VK_DYNAMIC_STATE_SCISSOR
            val dynamicState = zeroed<VkPipelineDynamicStateCreateInfo>()
            dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO
            dynamicState.dynamicStateCount = 2u
            dynamicState.pDynamicStates = dynamicStates

            // Dynamic rendering: the state that a render pass would normally carry is supplied
            // here, so no VkRenderPass or VkFramebuffer exists in this backend.
            val colorFormats = zeroedArrayOrEmptyList<UIntVar>(targets.size)
            targets.forEachIndexed { index, target ->
                colorFormats!![index] = target.format.toVkFormat()
            }
            val renderingCreateInfo = zeroed<VkPipelineRenderingCreateInfo>()
            renderingCreateInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO
            renderingCreateInfo.viewMask = 0u
            renderingCreateInfo.colorAttachmentCount = targets.size.toUInt()
            renderingCreateInfo.pColorAttachmentFormats = colorFormats
            val depthState = descriptor.depthStencil
            renderingCreateInfo.depthAttachmentFormat =
                depthState?.format?.toVkFormat() ?: VK_FORMAT_UNDEFINED
            renderingCreateInfo.stencilAttachmentFormat =
                if (depthState != null && depthState.format.hasStencilAspect()) {
                    depthState.format.toVkFormat()
                } else {
                    VK_FORMAT_UNDEFINED
                }

            val createInfo = zeroed<VkGraphicsPipelineCreateInfo>()
            createInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO
            createInfo.pNext = renderingCreateInfo.ptr
            createInfo.stageCount = stages.size.toUInt()
            createInfo.pStages = stageInfos
            createInfo.pVertexInputState = vertexInputState.ptr
            createInfo.pInputAssemblyState = inputAssemblyState.ptr
            createInfo.pTessellationState = null
            createInfo.pViewportState = viewportState.ptr
            createInfo.pRasterizationState = rasterizationState.ptr
            createInfo.pMultisampleState = multisampleState.ptr
            createInfo.pDepthStencilState = depthStencilState?.ptr
            createInfo.pColorBlendState = colorBlendState?.ptr
            createInfo.pDynamicState = dynamicState.ptr
            createInfo.layout = pipelineLayout
            createInfo.renderPass = null
            createInfo.subpass = 0u
            createInfo.basePipelineHandle = null
            createInfo.basePipelineIndex = -1

            val pipelineVar = zeroed<VkPipelineVar>()
            checkVkResult(
                deviceApi.createGraphicsPipelines.invoke(
                    logicalDevice,
                    null,
                    1u,
                    createInfo.ptr,
                    null,
                    pipelineVar.ptr,
                ),
                "vkCreateGraphicsPipelines",
            )
            VulkanPipelineResource(
                pipeline = pipelineVar.value
                    ?: throw VulkanException(
                        "vkCreateGraphicsPipelines returned a null pipeline handle",
                    ),
            )
        }
        val token = nextToken()
        pipelines[token] = resource
        return token
    }

    // ---------------------------------------------------------------------
    // Destruction and submissions
    // ---------------------------------------------------------------------

    override fun destroy(token: Long) {
        ensureOpen()
        val buffer = buffers.remove(token)
        if (buffer != null) {
            releaseBufferResource(buffer)
            return
        }
        val texture = textures.remove(token)
        if (texture != null) {
            deviceApi.destroyImageView.invoke(logicalDevice, texture.view, null)
            deviceApi.destroyImage.invoke(logicalDevice, texture.image, null)
            deviceApi.freeMemory.invoke(logicalDevice, texture.memory, null)
            return
        }
        val sampler = samplers.remove(token)
        if (sampler != null) {
            deviceApi.destroySampler.invoke(logicalDevice, sampler.sampler, null)
            return
        }
        val shader = shaders.remove(token)
        if (shader != null) {
            deviceApi.destroyShaderModule.invoke(logicalDevice, shader.module, null)
            return
        }
        val pipeline = pipelines.remove(token)
        if (pipeline != null) {
            deviceApi.destroyPipeline.invoke(logicalDevice, pipeline.pipeline, null)
            return
        }
        val commandBuffer = commandBuffers.remove(token)
        if (commandBuffer != null) {
            freeCommandBuffers(listOf(commandBuffer))
            return
        }
        throw IllegalArgumentException("Unknown or already destroyed token: $token")
    }

    override fun createCommandBuffer(label: String?, commands: List<BackendCommand>): Long {
        ensureOpen()
        val token = nextToken()
        val commandBuffer = allocateTransientCommandBuffer()
        try {
            recorder.record(commandBuffer, commands)
        } catch (error: Throwable) {
            // A failed record leaves the command buffer begun but invalid; freeing it keeps the
            // transient pool from leaking.
            freeCommandBuffers(listOf(commandBuffer))
            throw error
        }
        commandBuffers[token] = commandBuffer
        return token
    }

    override fun submit(commandBufferTokens: List<Long>): Long {
        ensureOpen()
        reapCompletedSubmissions()
        val submitted = commandBufferTokens.map { token ->
            commandBuffers.remove(token)
                ?: throw IllegalArgumentException("Unknown command buffer token: $token")
        }
        val submissionToken = nextToken()
        lastSubmissionToken = submissionToken
        if (submitted.isNotEmpty()) {
            val fence = createWaitFence()
            inFlight[submissionToken] = InFlightSubmission(fence, submitted)
            memScoped {
                val submitInfos = zeroedArray<VkSubmitInfo>(submitted.size)
                submitted.forEachIndexed { index, commandBuffer ->
                    val entry = zeroedArray<VkCommandBufferVar>(1)
                    entry[0] = commandBuffer
                    val info = submitInfos[index]
                    info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO
                    info.commandBufferCount = 1u
                    info.pCommandBuffers = entry
                }
                checkVkResult(
                    deviceApi.queueSubmit.invoke(queue, submitted.size.toUInt(), submitInfos, fence),
                    "vkQueueSubmit",
                )
            }
        }
        // An empty submission completes immediately: no fence is needed and the token will never
        // appear in the in-flight map, so isSubmissionComplete reports true for it.
        return submissionToken
    }

    override fun isSubmissionComplete(submissionToken: Long): Boolean {
        ensureOpen()
        if (submissionToken <= 0L || submissionToken > lastSubmissionToken) {
            throw IllegalArgumentException("Unknown submission token: $submissionToken")
        }
        reapCompletedSubmissions()
        return submissionToken !in inFlight
    }

    override fun waitIdle() {
        ensureOpen()
        waitForDeviceIdle("waitIdle")
        reapCompletedSubmissions()
    }

    override fun close() {
        if (closed) return
        closed = true
        deviceApi.deviceWaitIdle.invoke(logicalDevice)
        val inFlightIterator = inFlight.entries.iterator()
        while (inFlightIterator.hasNext()) {
            // Entry values must be read before removal when iterating Kotlin/Native maps.
            val submission = inFlightIterator.next().value
            freeCommandBuffers(submission.commandBuffers)
            deviceApi.destroyFence.invoke(logicalDevice, submission.fence, null)
            inFlightIterator.remove()
        }

        buffers.values.forEach { buffer -> releaseBufferResource(buffer) }
        buffers.clear()
        textures.values.forEach { texture ->
            deviceApi.destroyImageView.invoke(logicalDevice, texture.view, null)
            deviceApi.destroyImage.invoke(logicalDevice, texture.image, null)
            deviceApi.freeMemory.invoke(logicalDevice, texture.memory, null)
        }
        textures.clear()
        samplers.values.forEach { sampler ->
            deviceApi.destroySampler.invoke(logicalDevice, sampler.sampler, null)
        }
        samplers.clear()
        shaders.values.forEach { shader ->
            deviceApi.destroyShaderModule.invoke(logicalDevice, shader.module, null)
        }
        shaders.clear()
        pipelines.values.forEach { pipeline ->
            deviceApi.destroyPipeline.invoke(logicalDevice, pipeline.pipeline, null)
        }
        pipelines.clear()
        if (commandBuffers.isNotEmpty()) {
            freeCommandBuffers(commandBuffers.values.toList())
            commandBuffers.clear()
        }

        deviceApi.destroyCommandPool.invoke(logicalDevice, commandPool, null)
        deviceApi.destroyPipelineLayout.invoke(logicalDevice, pipelineLayout, null)
        instanceApi.destroyDevice.invoke(logicalDevice, null)
        instanceApi.destroyInstance.invoke(instance, null)
        library.close()
    }

    // ---------------------------------------------------------------------
    // Backend local texture readback
    // ---------------------------------------------------------------------

    /**
     * Copies the base mip level of a texture into host memory and returns the tightly packed
     * texels of the first array layer.
     *
     * This is a backend local diagnostic API: the portable core deliberately does not expose
     * texture readback because the shared API surface has no portable staging path for it yet.
     * The copy runs as a one-shot submission; the texture rests in `VK_IMAGE_LAYOUT_GENERAL`
     * before and after.
     *
     * @throws IllegalArgumentException if the token is not a live texture.
     * @throws VulkanException if the texture bytes cannot be staged in host visible memory.
     */
    public fun readTextureBytes(textureToken: Long): ByteArray {
        ensureOpen()
        val texture = textures[textureToken]
            ?: throw IllegalArgumentException(
                "Unknown or destroyed texture token: $textureToken",
            )
        val byteCount = texture.descriptor.width * texture.descriptor.height *
            texture.descriptor.format.bytesPerPixel
        val staging = allocateBufferResource(
            BufferDescriptor(
                sizeBytes = byteCount.toLong(),
                usage = setOf(BufferUsage.CopyDestination),
                label = "Ether texture readback staging",
            ),
        )
        val mapped = staging.mapped
            ?: throw VulkanException(
                "Texture readback requires host visible memory; the device " +
                    "'${info.physicalDeviceName}' cannot map a staging buffer",
            )
        try {
            val commandBuffer = allocateTransientCommandBuffer()
            try {
                recorder.recordTextureReadback(commandBuffer, texture, staging)
                submitAndWait(listOf(commandBuffer))
            } catch (error: Throwable) {
                freeCommandBuffers(listOf(commandBuffer))
                throw error
            }
            if (!staging.coherent) {
                invalidateMappedMemory(staging.memory)
            }
            val bytes = mapped.readBytes(byteCount)
            freeCommandBuffers(listOf(commandBuffer))
            return bytes
        } finally {
            releaseBufferResource(staging)
        }
    }

    // ---------------------------------------------------------------------
    // Logical device creation
    // ---------------------------------------------------------------------

    private fun createLogicalDevice(): VkDevice = memScoped {
        val queueCreateInfo = zeroed<VkDeviceQueueCreateInfo>()
        queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
        queueCreateInfo.queueFamilyIndex = summary.queueFamilyIndex
        queueCreateInfo.queueCount = 1u
        val priorities = zeroedArray<FloatVar>(1)
        priorities[0] = 1f
        queueCreateInfo.pQueuePriorities = priorities

        val features2 = zeroed<VkPhysicalDeviceFeatures2>()
        features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
        if (summary.supportsSamplerAnisotropy) {
            features2.features.samplerAnisotropy = 1u
        }
        if (summary.supportsDepthClamp) {
            features2.features.depthClamp = 1u
        }
        val vulkan13Features = zeroed<VkPhysicalDeviceVulkan13Features>()
        vulkan13Features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES
        vulkan13Features.dynamicRendering = 1u
        features2.pNext = vulkan13Features.ptr

        val createInfo = zeroed<VkDeviceCreateInfo>()
        createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
        createInfo.pNext = features2.ptr
        createInfo.queueCreateInfoCount = 1u
        createInfo.pQueueCreateInfos = queueCreateInfo.ptr

        val deviceVar = zeroed<VkDeviceVar>()
        checkVkResult(
            instanceApi.createDevice.invoke(physicalDevice, createInfo.ptr, null, deviceVar.ptr),
            "vkCreateDevice",
        )
        deviceVar.value ?: throw VulkanException("vkCreateDevice returned a null device handle")
    }

    private fun fetchQueue(): VkQueue = memScoped {
        val queueVar = zeroed<VkQueueVar>()
        deviceApi.getDeviceQueue.invoke(logicalDevice, summary.queueFamilyIndex, 0u, queueVar.ptr)
        queueVar.value ?: throw VulkanException("vkGetDeviceQueue returned a null queue handle")
    }

    private fun createCommandPool(): VkCommandPool = memScoped {
        val createInfo = zeroed<VkCommandPoolCreateInfo>()
        createInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
        createInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
        createInfo.queueFamilyIndex = summary.queueFamilyIndex
        val poolVar = zeroed<VkCommandPoolVar>()
        checkVkResult(
            deviceApi.createCommandPool.invoke(logicalDevice, createInfo.ptr, null, poolVar.ptr),
            "vkCreateCommandPool",
        )
        poolVar.value ?: throw VulkanException("vkCreateCommandPool returned a null pool handle")
    }

    /** The engine records no descriptor sets yet, so the shared pipeline layout stays empty. */
    private fun createEmptyPipelineLayout(): VkPipelineLayout = memScoped {
        val createInfo = zeroed<VkPipelineLayoutCreateInfo>()
        createInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        createInfo.setLayoutCount = 0u
        createInfo.pSetLayouts = null
        createInfo.pushConstantRangeCount = 0u
        createInfo.pPushConstantRanges = null
        val layoutVar = zeroed<VkPipelineLayoutVar>()
        checkVkResult(
            deviceApi.createPipelineLayout.invoke(logicalDevice, createInfo.ptr, null, layoutVar.ptr),
            "vkCreatePipelineLayout",
        )
        layoutVar.value
            ?: throw VulkanException("vkCreatePipelineLayout returned a null layout handle")
    }

    // ---------------------------------------------------------------------
    // Memory and buffers
    // ---------------------------------------------------------------------

    /**
     * Allocates memory from the first memory type satisfying [requiredFlags], falling back to any
     * host visible type before failing.
     */
    private fun allocateMemory(
        requirements: VkMemoryRequirements,
        requiredFlags: UInt,
        operation: String,
    ): VulkanMemoryAllocation {
        val typeIndex = allocator.findType(requirements.memoryTypeBits, requiredFlags)
        if (typeIndex >= 0) {
            return allocateFromType(typeIndex, requirements)
        }
        val fallback = allocator.findType(
            requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
        )
        if (fallback >= 0) {
            return allocateFromType(fallback, requirements)
        }
        throw VulkanException("No compatible Vulkan memory type for $operation")
    }

    private fun allocateFromType(
        typeIndex: Int,
        requirements: VkMemoryRequirements,
    ): VulkanMemoryAllocation = memScoped {
        val allocateInfo = zeroed<VkMemoryAllocateInfo>()
        allocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
        allocateInfo.allocationSize = requirements.size
        allocateInfo.memoryTypeIndex = typeIndex.toUInt()

        val memoryVar = zeroed<VkDeviceMemoryVar>()
        checkVkResult(
            deviceApi.allocateMemory.invoke(logicalDevice, allocateInfo.ptr, null, memoryVar.ptr),
            "vkAllocateMemory",
        )
        VulkanMemoryAllocation(
            memory = memoryVar.value
                ?: throw VulkanException("vkAllocateMemory returned a null memory handle"),
            allocationSize = requirements.size,
            memoryTypeIndex = typeIndex,
            hostVisible = allocator.isHostVisible(typeIndex),
            coherent = allocator.isCoherent(typeIndex),
        )
    }

    private fun allocateBufferResource(descriptor: BufferDescriptor): VulkanBufferResource =
        memScoped {
            val size = descriptor.sizeBytes.toULong()
            val createInfo = zeroed<VkBufferCreateInfo>()
            createInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
            createInfo.size = size
            createInfo.usage = descriptor.usage.toVkBufferUsage()
            createInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE

            val bufferVar = zeroed<VkBufferVar>()
            checkVkResult(
                deviceApi.createBuffer.invoke(logicalDevice, createInfo.ptr, null, bufferVar.ptr),
                "vkCreateBuffer",
            )
            val buffer = bufferVar.value
                ?: throw VulkanException("vkCreateBuffer returned a null buffer handle")

            try {
                val requirements = zeroed<VkMemoryRequirements>()
                deviceApi.getBufferMemoryRequirements.invoke(logicalDevice, buffer, requirements.ptr)

                // Uniform and storage buffers keep upload staging out of mapped memory; other
                // usages prefer a mapping so engine writes do not need a copy.
                val requiredFlags = if (descriptor.usage.contains(BufferUsage.Uniform) ||
                    descriptor.usage.contains(BufferUsage.Storage)
                ) {
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                } else {
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                }
                val operation = "buffer '${descriptor.label ?: "unnamed"}'"
                var allocation = allocateMemory(requirements, requiredFlags, operation)
                var mapped: CPointer<ByteVar>? = null
                if (allocation.hostVisible) {
                    mapped = mapWholeMemory(allocation.memory, allocation.allocationSize)
                } else if (requiredFlags == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                    // No device local type was found; the fallback may have chosen host visible
                    // memory even though the resource wanted a device local placement.
                    if (!allocation.hostVisible) {
                        throw VulkanException("No compatible Vulkan memory type for $operation")
                    }
                }
                checkVkResult(
                    deviceApi.bindBufferMemory.invoke(logicalDevice, buffer, allocation.memory, 0uL),
                    "vkBindBufferMemory",
                )

                VulkanBufferResource(
                    buffer = buffer,
                    memory = allocation.memory,
                    size = size,
                    allocationSize = allocation.allocationSize,
                    descriptor = descriptor,
                    memoryTypeIndex = allocation.memoryTypeIndex,
                    hostVisible = allocation.hostVisible,
                    coherent = allocation.coherent,
                    mapped = mapped,
                )
            } catch (error: Throwable) {
                deviceApi.destroyBuffer.invoke(logicalDevice, buffer, null)
                throw error
            }
        }

    private fun mapWholeMemory(memory: VkDeviceMemory, size: ULong): CPointer<ByteVar> {
        memScoped {
            val mappedVar = zeroed<COpaquePointerVar>()
            checkVkResult(
                deviceApi.mapMemory.invoke(logicalDevice, memory, 0uL, size, 0u, mappedVar.ptr),
                "vkMapMemory",
            )
            return mappedVar.value?.reinterpret<ByteVar>()
                ?: throw VulkanException("vkMapMemory returned a null mapping")
        }
    }

    /**
     * Copies [data] into the destination buffer. When the buffer is host visible the write is
     * applied directly to its persistent mapping; device local memory is fed through a staging
     * buffer whose copy runs to completion before the call returns.
     */
    private fun stageBufferUpload(
        target: VulkanBufferResource,
        offsetBytes: Long,
        data: ByteArray,
    ) {
        val staging = allocateBufferResource(
            BufferDescriptor(
                sizeBytes = data.size.toLong(),
                usage = setOf(BufferUsage.CopySource),
                label = "Ether staging upload",
            ),
        )
        val mapped = staging.mapped
            ?: throw VulkanException("Staging buffers must live in host visible memory")
        try {
            waitForDeviceIdle("staging upload write")
            mapped.writeBytes(data, 0)
            if (!staging.coherent) {
                flushMappedMemory(staging.memory)
            }
            val commandBuffer = allocateTransientCommandBuffer()
            try {
                recorder.recordBufferUpload(commandBuffer, staging, target, offsetBytes, data.size)
                submitAndWait(listOf(commandBuffer))
            } finally {
                freeCommandBuffers(listOf(commandBuffer))
            }
        } finally {
            releaseBufferResource(staging)
        }
    }

    private fun releaseBufferResource(resource: VulkanBufferResource) {
        if (resource.mapped != null) {
            deviceApi.unmapMemory.invoke(logicalDevice, resource.memory)
        }
        deviceApi.destroyBuffer.invoke(logicalDevice, resource.buffer, null)
        deviceApi.freeMemory.invoke(logicalDevice, resource.memory, null)
    }

    // ---------------------------------------------------------------------
    // Submission plumbing
    // ---------------------------------------------------------------------

    private fun allocateTransientCommandBuffer(): VkCommandBuffer = memScoped {
        val allocateInfo = zeroed<VkCommandBufferAllocateInfo>()
        allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
        allocateInfo.commandPool = commandPool
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY
        allocateInfo.commandBufferCount = 1u
        val bufferVar = zeroedArray<VkCommandBufferVar>(1)
        checkVkResult(
            deviceApi.allocateCommandBuffers.invoke(logicalDevice, allocateInfo.ptr, bufferVar),
            "vkAllocateCommandBuffers",
        )
        bufferVar[0] ?: throw VulkanException(
            "vkAllocateCommandBuffers returned a null command buffer handle",
        )
    }

    private fun createWaitFence(): VkFence = memScoped {
        val createInfo = zeroed<VkFenceCreateInfo>()
        createInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
        val fenceVar = zeroed<VkFenceVar>()
        checkVkResult(
            deviceApi.createFence.invoke(logicalDevice, createInfo.ptr, null, fenceVar.ptr),
            "vkCreateFence",
        )
        fenceVar.value ?: throw VulkanException("vkCreateFence returned a null fence handle")
    }

    /**
     * Submits [commandBuffers] and blocks until their fence signals the work complete.
     *
     * The fence of a single `vkQueueSubmit` call signals after every entry of the call completed
     * on the queue, which covers all work that was ordered before it.
     */
    private fun submitAndWait(commandBuffers: List<VkCommandBuffer>) {
        val fence = createWaitFence()
        try {
            memScoped {
                val submitInfos = zeroedArray<VkSubmitInfo>(commandBuffers.size)
                commandBuffers.forEachIndexed { index, commandBuffer ->
                    val entry = zeroedArray<VkCommandBufferVar>(1)
                    entry[0] = commandBuffer
                    val info = submitInfos[index]
                    info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO
                    info.commandBufferCount = 1u
                    info.pCommandBuffers = entry
                }
                checkVkResult(
                    deviceApi.queueSubmit.invoke(
                        queue,
                        commandBuffers.size.toUInt(),
                        submitInfos,
                        fence,
                    ),
                    "vkQueueSubmit",
                )
            }
            memScoped {
                val fences = zeroedArray<VkFenceVar>(1)
                fences[0] = fence
                checkVkResult(
                    deviceApi.waitForFences.invoke(logicalDevice, 1u, fences, 1u, ULong.MAX_VALUE),
                    "vkWaitForFences",
                )
                checkVkResult(
                    deviceApi.resetFences.invoke(logicalDevice, 1u, fences),
                    "vkResetFences",
                )
            }
        } finally {
            deviceApi.destroyFence.invoke(logicalDevice, fence, null)
        }
    }

    private fun waitForDeviceIdle(reason: String) {
        checkVkResult(
            deviceApi.deviceWaitIdle.invoke(logicalDevice),
            "vkDeviceWaitIdle ($reason)",
        )
    }

    private fun flushMappedMemory(memory: VkDeviceMemory) {
        memScoped {
            val range = zeroed<VkMappedMemoryRange>()
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE
            range.memory = memory
            range.offset = 0uL
            range.size = VK_WHOLE_SIZE
            checkVkResult(
                deviceApi.flushMappedMemoryRanges.invoke(logicalDevice, 1u, range.ptr),
                "vkFlushMappedMemoryRanges",
            )
        }
    }

    private fun invalidateMappedMemory(memory: VkDeviceMemory) {
        memScoped {
            val range = zeroed<VkMappedMemoryRange>()
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE
            range.memory = memory
            range.offset = 0uL
            range.size = VK_WHOLE_SIZE
            checkVkResult(
                deviceApi.invalidateMappedMemoryRanges.invoke(logicalDevice, 1u, range.ptr),
                "vkInvalidateMappedMemoryRanges",
            )
        }
    }

    private fun freeCommandBuffers(commandBuffers: List<VkCommandBuffer>) {
        if (commandBuffers.isEmpty()) return
        memScoped {
            val array = zeroedArray<VkCommandBufferVar>(commandBuffers.size)
            commandBuffers.forEachIndexed { index, commandBuffer -> array[index] = commandBuffer }
            deviceApi.freeCommandBuffers.invoke(
                logicalDevice,
                commandPool,
                commandBuffers.size.toUInt(),
                array,
            )
        }
    }

    /**
     * Frees every resource of a submission whose fence has signaled.
     *
     * Entry values are read before removal because Kotlin/Native map entries must not be touched
     * after their iterator slot was removed.
     */
    private fun reapCompletedSubmissions() {
        if (inFlight.isEmpty()) return
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val submission = iterator.next().value
            if (deviceApi.getFenceStatus.invoke(logicalDevice, submission.fence) == VK_SUCCESS) {
                freeCommandBuffers(submission.commandBuffers)
                deviceApi.destroyFence.invoke(logicalDevice, submission.fence, null)
                iterator.remove()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Resolution and validation
    // ---------------------------------------------------------------------

    private fun resolveBuffer(token: Long): VulkanBufferResource =
        buffers[token] ?: throw IllegalArgumentException("Unknown buffer token: $token")

    private fun resolveTexture(token: Long): VulkanTextureResource =
        textures[token] ?: throw IllegalArgumentException("Unknown texture token: $token")

    private fun resolvePipeline(token: Long): VulkanPipelineResource =
        pipelines[token] ?: throw IllegalArgumentException("Unknown pipeline token: $token")

    private fun resolveShader(token: Long, expectedStage: ShaderStage): VulkanShaderResource {
        val shader = shaders[token]
            ?: throw IllegalArgumentException("Unknown shader module token: $token")
        if (shader.stage != expectedStage) {
            throw IllegalArgumentException(
                "Shader module token $token has stage ${shader.stage}, expected $expectedStage",
            )
        }
        return shader
    }

    private fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Vulkan backend device '$label' is closed")
        }
    }

    private fun nextToken(): Long = nextTokenValue++

    private fun ensureSpirvMagic(code: ByteArray) {
        if (code.size < 4) {
            throw IllegalArgumentException(
                "SPIR-V module must be at least four bytes long, was ${code.size}",
            )
        }
        val magic = readSpirvWord(code, 0)
        if (magic != SPIRV_MAGIC_NUMBER) {
            throw IllegalArgumentException(
                "Invalid SPIR-V module magic number; expected $SPIRV_MAGIC_NUMBER, got $magic",
            )
        }
    }

    /** Reads one little endian SPIR-V word out of [code]. */
    private fun readSpirvWord(code: ByteArray, wordIndex: Int): UInt {
        val base = wordIndex * 4
        return ((code[base].toInt() and 0xFF) or
            ((code[base + 1].toInt() and 0xFF) shl 8) or
            ((code[base + 2].toInt() and 0xFF) shl 16) or
            ((code[base + 3].toInt() and 0xFF) shl 24)).toUInt()
    }

    private fun buildColorWriteMask(mask: ColorWriteMask): UInt {
        var bits = 0u
        if (mask.contains(ColorChannel.Red)) bits = bits or VK_COLOR_COMPONENT_R_BIT
        if (mask.contains(ColorChannel.Green)) bits = bits or VK_COLOR_COMPONENT_G_BIT
        if (mask.contains(ColorChannel.Blue)) bits = bits or VK_COLOR_COMPONENT_B_BIT
        if (mask.contains(ColorChannel.Alpha)) bits = bits or VK_COLOR_COMPONENT_A_BIT
        return bits
    }

    private fun usesConstantColorFactor(
        blend: dev.instrum.ether.render.api.BlendState,
    ): Boolean {
        val factors = listOf(
            blend.color.srcFactor,
            blend.color.dstFactor,
            blend.alpha.srcFactor,
            blend.alpha.dstFactor,
        )
        return factors.contains(BlendFactor.ConstantColor) ||
            factors.contains(BlendFactor.OneMinusConstantColor)
    }

    /**
     * Allocates an array of [count] descriptors, or returns an empty null pointer when the
     * count is zero because Vulkan permits null arrays with a zero count.
     */
    private inline fun <reified T : kotlinx.cinterop.CVariable> NativePlacement.zeroedArrayOrEmptyList(
        count: Int,
    ): CPointer<T>? =
        if (count == 0) {
            null
        } else {
            zeroedArray<T>(count)
        }

}

/**
 * Builds the Vulkan buffer usage bitmask; transfer bits are always present so staging reads
 * (readback, uploads, and future mip generation) can address any buffer.
 */
private fun Set<BufferUsage>.toVkBufferUsage(): UInt {
    var bits = 0u
    if (contains(BufferUsage.Vertex)) bits = bits or VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
    if (contains(BufferUsage.Index)) bits = bits or VK_BUFFER_USAGE_INDEX_BUFFER_BIT
    if (contains(BufferUsage.Uniform)) bits = bits or VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
    if (contains(BufferUsage.Storage)) bits = bits or VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    if (contains(BufferUsage.Indirect)) bits = bits or VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
    if (contains(BufferUsage.CopySource)) bits = bits or VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    if (contains(BufferUsage.CopyDestination)) bits = bits or VK_BUFFER_USAGE_TRANSFER_DST_BIT
    return bits or VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK_BUFFER_USAGE_TRANSFER_DST_BIT
}

private fun Set<TextureUsage>.toVkImageUsage(): UInt {
    var bits = 0u
    if (contains(TextureUsage.Sampled)) bits = bits or VK_IMAGE_USAGE_SAMPLED_BIT
    if (contains(TextureUsage.RenderTarget)) bits = bits or VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
    if (contains(TextureUsage.DepthStencil)) {
        bits = bits or VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
    }
    if (contains(TextureUsage.Storage)) bits = bits or VK_IMAGE_USAGE_STORAGE_BIT
    if (contains(TextureUsage.CopySource)) bits = bits or VK_IMAGE_USAGE_TRANSFER_SRC_BIT
    if (contains(TextureUsage.CopyDestination)) bits = bits or VK_IMAGE_USAGE_TRANSFER_DST_BIT
    return bits or VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_TRANSFER_DST_BIT
}

private fun TextureFormat.toAspectMask(): UInt = when (this) {
    TextureFormat.Depth24PlusStencil8 ->
        VK_IMAGE_ASPECT_DEPTH_BIT or VK_IMAGE_ASPECT_STENCIL_BIT
    TextureFormat.Depth16Unorm, TextureFormat.Depth24Plus, TextureFormat.Depth32Float ->
        VK_IMAGE_ASPECT_DEPTH_BIT
    else -> VK_IMAGE_ASPECT_COLOR_BIT
}

/** Whether the format carries a stencil aspect usable for stencil attachment formats. */
private fun TextureFormat.hasStencilAspect(): Boolean =
    this == TextureFormat.Depth24PlusStencil8

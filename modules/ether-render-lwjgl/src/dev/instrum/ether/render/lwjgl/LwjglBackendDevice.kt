package dev.instrum.ether.render.lwjgl

import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BlendFactor
import dev.instrum.ether.render.api.BlendOperation
import dev.instrum.ether.render.api.BlendState
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
import dev.instrum.ether.render.lwjgl.internal.LwjglBufferResource
import dev.instrum.ether.render.lwjgl.internal.LwjglCommandRecorder
import dev.instrum.ether.render.lwjgl.internal.LwjglPhysicalDeviceSummary
import dev.instrum.ether.render.lwjgl.internal.LwjglPipelineResource
import dev.instrum.ether.render.lwjgl.internal.LwjglMemoryAllocator
import dev.instrum.ether.render.lwjgl.internal.LwjglSamplerResource
import dev.instrum.ether.render.lwjgl.internal.LwjglShaderResource
import dev.instrum.ether.render.lwjgl.internal.LwjglTextureResource
import dev.instrum.ether.render.lwjgl.internal.LwjglVulkanProbe
import dev.instrum.ether.render.lwjgl.internal.checkVkResult
import dev.instrum.ether.render.lwjgl.internal.deviceTypeName
import dev.instrum.ether.render.lwjgl.internal.formatVersion
import dev.instrum.ether.render.lwjgl.internal.readBytes
import dev.instrum.ether.render.lwjgl.internal.toVkAddressMode
import dev.instrum.ether.render.lwjgl.internal.toVkBlendFactor
import dev.instrum.ether.render.lwjgl.internal.toVkBlendOp
import dev.instrum.ether.render.lwjgl.internal.toVkCompareOp
import dev.instrum.ether.render.lwjgl.internal.toVkCullMode
import dev.instrum.ether.render.lwjgl.internal.toVkFilter
import dev.instrum.ether.render.lwjgl.internal.toVkFormat
import dev.instrum.ether.render.lwjgl.internal.toVkFrontFace
import dev.instrum.ether.render.lwjgl.internal.toVkMipmapMode
import dev.instrum.ether.render.lwjgl.internal.toVkTopology
import dev.instrum.ether.render.lwjgl.internal.writeBytes
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkBufferCreateInfo
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageViewCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkMappedMemoryRange
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkSamplerCreateInfo
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import org.lwjgl.vulkan.VkSubmitInfo
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription
import java.nio.ByteBuffer

/**
 * A real Vulkan device created on a probed physical device.
 *
 * The backend loads the Vulkan loader through LWJGL, so a JVM that ships without the natives
 * fails while bootstrapping them and reports the backend as unavailable. The bootstrap is
 * implemented by [dev.instrum.ether.render.lwjgl.internal.NativeBootstrap]; the availability
 * probe is in [dev.instrum.ether.render.lwjgl.internal.LwjglVulkanProbe]. The loader library
 * stays mapped for the process lifetime on the JVM.
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
public class LwjglVulkanBackendDevice internal constructor(
    /** Debug label supplied by the core when the device was created. */
    public val label: String,
    /** Identification of the physical device backing this device. */
    public val info: LwjglDeviceInfo,
    /** Capabilities reported by the creating backend. */
    public val capabilities: GraphicsCapabilities,
    private val instance: VkInstance,
    private val physicalDevice: VkPhysicalDevice,
    private val summary: LwjglPhysicalDeviceSummary,
) : RenderBackendDevice {

    // Property order is initialization order: the logical device must exist before the device
    // level entry points can be used, and the queue, pool, and layout come from it.
    private val logicalDevice: VkDevice = createLogicalDevice()

    private val queue: VkQueue = fetchQueue()
    private val commandPool: Long = createCommandPool()
    private val pipelineLayout: Long = createEmptyPipelineLayout()
    private val allocator: LwjglMemoryAllocator = LwjglMemoryAllocator(summary.memoryTypes)
    private val recorder: LwjglCommandRecorder = LwjglCommandRecorder(
        resolveBuffer = ::resolveBuffer,
        resolveTexture = ::resolveTexture,
        resolvePipeline = ::resolvePipeline,
    )

    private val buffers: MutableMap<Long, LwjglBufferResource> = LinkedHashMap()
    private val textures: MutableMap<Long, LwjglTextureResource> = LinkedHashMap()
    private val samplers: MutableMap<Long, LwjglSamplerResource> = LinkedHashMap()
    private val shaders: MutableMap<Long, LwjglShaderResource> = LinkedHashMap()
    private val pipelines: MutableMap<Long, LwjglPipelineResource> = LinkedHashMap()
    private val commandBuffers: MutableMap<Long, VkCommandBuffer> = LinkedHashMap()
    private val inFlight: MutableMap<Long, InFlightSubmission> = LinkedHashMap()

    private var nextTokenValue: Long = 1L
    private var lastSubmissionToken: Long = 0L
    private var closed: Boolean = false

    /** One queue submission referencing the fence and command buffers it covers. */
    private class InFlightSubmission(
        val fence: Long,
        val commandBuffers: List<VkCommandBuffer>,
    )

    /**
     * Device memory allocated from the driver together with the host mapping metadata.
     */
    private class LwjglMemoryAllocation(
        val memory: Long,
        val allocationSize: Long,
        val memoryTypeIndex: Int,
        val hostVisible: Boolean,
        val coherent: Boolean,
    )

    internal companion object {
        /**
         * Takes over ownership of the probe: this device inherits the LWJGL loader and the
         * instance and releases the instance when [close] runs.
         */
        internal fun fromProbe(probe: LwjglVulkanProbe, label: String): LwjglVulkanBackendDevice =
            LwjglVulkanBackendDevice(
                label = label,
                info = LwjglDeviceInfo(
                    physicalDeviceName = probe.summary.name,
                    apiVersion = formatVersion(probe.summary.apiVersion),
                    driverVersion = probe.summary.driverVersion,
                    vendorId = probe.summary.vendorId,
                    deviceType = probe.summary.deviceTypeName(),
                    queueFamilyIndex = probe.summary.queueFamilyIndex,
                    queueCount = probe.summary.queueCount,
                ),
                capabilities = probe.capabilities,
                instance = probe.instance,
                physicalDevice = probe.physicalDevice,
                summary = probe.summary,
            )

        /** First word of every valid SPIR-V module, stored little endian in the byte stream. */
        private const val SPIRV_MAGIC_NUMBER: Int = 0x07230203
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
        if (offsetBytes < 0L || offsetBytes + data.size.toLong() > resource.size) {
            throw IllegalArgumentException(
                "Write of ${data.size} bytes at offset $offsetBytes exceeds the " +
                    "${resource.size}-byte buffer",
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
        val resource = MemoryStack.stackPush().use { stack ->
            val format = descriptor.format.toVkFormat()
            val aspectMask = descriptor.format.toAspectMask()

            val createInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            when (descriptor.dimension) {
                TextureDimension.D1 -> {
                    createInfo.imageType(VK10.VK_IMAGE_TYPE_1D)
                        .extent { extent -> extent.width(descriptor.width).height(1).depth(1) }
                        .arrayLayers(1)
                }

                TextureDimension.D2 -> {
                    createInfo.imageType(VK10.VK_IMAGE_TYPE_2D)
                        .extent { extent ->
                            extent.width(descriptor.width).height(descriptor.height).depth(1)
                        }
                        .arrayLayers(descriptor.depthOrLayers)
                }

                TextureDimension.D3 -> {
                    createInfo.imageType(VK10.VK_IMAGE_TYPE_3D)
                        .extent { extent ->
                            extent.width(descriptor.width)
                                .height(descriptor.height)
                                .depth(descriptor.depthOrLayers)
                        }
                        .arrayLayers(1)
                }
            }
            createInfo.format(format)
                .mipLevels(descriptor.mipLevels)
                .samples(descriptor.sampleCount)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(descriptor.usage.toVkImageUsage())
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                // Vulkan only permits undefined or preinitialized initial layouts. The recorder
                // transitions every image with explicit barriers before each use, so contents
                // before the first write are never read.
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)

            val imagePointer = stack.callocLong(1)
            checkVkResult(
                VK10.vkCreateImage(logicalDevice, createInfo, null, imagePointer),
                "vkCreateImage",
            )
            val image = imagePointer[0]

            val requirements = VkMemoryRequirements.calloc(stack)
            VK10.vkGetImageMemoryRequirements(logicalDevice, image, requirements)
            val allocation = allocateMemory(
                requirements,
                MemoryProperty.DEVICE_LOCAL,
                "texture '${descriptor.label ?: "unnamed"}'",
            )
            checkVkResult(
                VK10.vkBindImageMemory(logicalDevice, image, allocation.memory, 0L),
                "vkBindImageMemory",
            )

            val viewType = when (descriptor.dimension) {
                TextureDimension.D1 -> VK10.VK_IMAGE_VIEW_TYPE_1D
                TextureDimension.D2 ->
                    if (descriptor.depthOrLayers > 1) {
                        VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY
                    } else {
                        VK10.VK_IMAGE_VIEW_TYPE_2D
                    }

                TextureDimension.D3 -> VK10.VK_IMAGE_VIEW_TYPE_3D
            }
            val viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            viewInfo.image(image)
                .viewType(viewType)
                .format(format)
            viewInfo.components { components ->
                components.r(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .g(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .b(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .a(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
            }
            viewInfo.subresourceRange { range ->
                range.aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(descriptor.mipLevels)
                    .baseArrayLayer(0)
                    .layerCount(
                        if (descriptor.dimension == TextureDimension.D3) {
                            1
                        } else {
                            descriptor.depthOrLayers
                        },
                    )
            }

            val viewPointer = stack.callocLong(1)
            checkVkResult(
                VK10.vkCreateImageView(logicalDevice, viewInfo, null, viewPointer),
                "vkCreateImageView",
            )
            LwjglTextureResource(
                image = image,
                view = viewPointer[0],
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
        val resource = MemoryStack.stackPush().use { stack ->
            val anisotropySupported = summary.supportsSamplerAnisotropy &&
                summary.maxSamplerAnisotropy > 1f
            if (descriptor.maxAnisotropy > 1 && !anisotropySupported) {
                throw LwjglVulkanException(
                    "Sampler requests anisotropy ${descriptor.maxAnisotropy} but the device " +
                        "does not support anisotropic filtering",
                )
            }
            val createInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            createInfo.magFilter(descriptor.magFilter.toVkFilter())
                .minFilter(descriptor.minFilter.toVkFilter())
                .mipmapMode(descriptor.mipmapFilter.toVkMipmapMode())
                .addressModeU(descriptor.addressModeU.toVkAddressMode())
                .addressModeV(descriptor.addressModeV.toVkAddressMode())
                .addressModeW(descriptor.addressModeW.toVkAddressMode())
                .mipLodBias(0f)
                .anisotropyEnable(anisotropySupported && descriptor.maxAnisotropy > 1)
                .maxAnisotropy(
                    if (anisotropySupported) {
                        descriptor.maxAnisotropy.toFloat().coerceIn(1f, summary.maxSamplerAnisotropy)
                    } else {
                        1f
                    },
                )
                .compareEnable(false)
                .compareOp(VK10.VK_COMPARE_OP_NEVER)
                .minLod(descriptor.lodMinClamp)
                .maxLod(descriptor.lodMaxClamp)
                .borderColor(VK10.VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK)
                .unnormalizedCoordinates(false)

            val samplerPointer = stack.callocLong(1)
            checkVkResult(
                VK10.vkCreateSampler(logicalDevice, createInfo, null, samplerPointer),
                "vkCreateSampler",
            )
            LwjglSamplerResource(sampler = samplerPointer[0])
        }
        val token = nextToken()
        samplers[token] = resource
        return token
    }

    override fun createShaderModule(descriptor: ShaderModuleDescriptor): Long {
        ensureOpen()
        if (descriptor.stage == ShaderStage.Compute) {
            throw LwjglVulkanException(
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
        val resource = MemoryStack.stackPush().use { stack ->
            // LWJGL passes the module through a byte buffer; SPIR-V is little endian already, so
            // the raw bytes are copied without packing words by hand.
            val code = stack.calloc(descriptor.code.size)
            code.put(descriptor.code)
            code.position(0)

            val createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
            // The LWJGL setter derives codeSize from the buffer size.
            createInfo.pCode(code)

            val modulePointer = stack.callocLong(1)
            checkVkResult(
                VK10.vkCreateShaderModule(logicalDevice, createInfo, null, modulePointer),
                "vkCreateShaderModule",
            )
            LwjglShaderResource(
                module = modulePointer[0],
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
            throw LwjglVulkanException(
                "Pipeline requests ${descriptor.multisample.count} samples but the device " +
                    "reports a maximum sample count of ${capabilities.limits.maxSampleCount}",
            )
        }
        if (descriptor.primitive.unclippedDepth && !summary.supportsDepthClamp) {
            throw LwjglVulkanException(
                "Pipeline requests unclipped depth but the device does not support depth clamp",
            )
        }

        val resource = MemoryStack.stackPush().use { stack ->
            val stages = listOfNotNull(vertexShader, fragmentShader)
            val stageInfos = VkPipelineShaderStageCreateInfo.calloc(stages.size, stack)
            stages.forEachIndexed { index, shader ->
                val info = stageInfos[index]
                info.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                info.stage(
                    when (shader.stage) {
                        ShaderStage.Vertex -> VK10.VK_SHADER_STAGE_VERTEX_BIT
                        ShaderStage.Fragment -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT
                        ShaderStage.Compute -> throw LwjglVulkanException(
                            "Compute stages cannot be used in a render pipeline",
                        )
                    },
                )
                info.module(shader.module)
                info.pName(stack.UTF8(shader.entryPoint))
            }

            val bufferLayouts = descriptor.vertex.buffers
            val bindingDescriptions = VkVertexInputBindingDescription.calloc(
                bufferLayouts.size,
                stack,
            )
            bufferLayouts.forEachIndexed { index, layout ->
                bindingDescriptions[index].binding(index)
                    .stride(layout.strideBytes)
                    .inputRate(
                        when (layout.stepMode) {
                            VertexStepMode.Vertex -> VK10.VK_VERTEX_INPUT_RATE_VERTEX
                            VertexStepMode.Instance -> VK10.VK_VERTEX_INPUT_RATE_INSTANCE
                        },
                    )
            }
            val attributeCount = bufferLayouts.sumOf { layout -> layout.attributes.size }
            val attributeDescriptions = VkVertexInputAttributeDescription.calloc(
                attributeCount,
                stack,
            )
            var attributeCursor = 0
            bufferLayouts.forEachIndexed { bindingIndex, layout ->
                layout.attributes.forEach { attribute ->
                    attributeDescriptions[attributeCursor].location(attribute.location)
                        .binding(bindingIndex)
                        .format(attribute.format.toVkFormat())
                        .offset(attribute.offsetBytes)
                    attributeCursor++
                }
            }
            val vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            // The LWJGL buffer setters derive the description counts from the buffer sizes.
            vertexInputState.pVertexBindingDescriptions(bindingDescriptions)
                .pVertexAttributeDescriptions(attributeDescriptions)

            val inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
            inputAssemblyState.topology(descriptor.primitive.topology.toVkTopology())
                .primitiveRestartEnable(false)

            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
            viewportState.viewportCount(1)
                .pViewports(null)
                .scissorCount(1)
                .pScissors(null)

            val rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
            rasterizationState.depthClampEnable(descriptor.primitive.unclippedDepth)
                .rasterizerDiscardEnable(false)
                .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                .cullMode(descriptor.primitive.cullMode.toVkCullMode())
                .frontFace(descriptor.primitive.frontFace.toVkFrontFace())
                .lineWidth(1f)

            val multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
            multisampleState.rasterizationSamples(descriptor.multisample.count)
                .sampleShadingEnable(false)
                .minSampleShading(1f)
                .pSampleMask(null)
                .alphaToCoverageEnable(descriptor.multisample.alphaToCoverageEnabled)

            val depthStencilState: VkPipelineDepthStencilStateCreateInfo? =
                descriptor.depthStencil?.let { state ->
                    VkPipelineDepthStencilStateCreateInfo.calloc(stack).also { info ->
                        info.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                            .depthTestEnable(true)
                            .depthWriteEnable(state.depthWriteEnabled)
                            .depthCompareOp(state.depthCompare.toVkCompareOp())
                            .depthBoundsTestEnable(false)
                            .stencilTestEnable(false)
                    }
                }

            val colorBlendState: VkPipelineColorBlendStateCreateInfo? = if (targets.isEmpty()) {
                null
            } else {
                val attachmentStates = VkPipelineColorBlendAttachmentState.calloc(
                    targets.size,
                    stack,
                )
                targets.forEachIndexed { index, target ->
                    val attachment = attachmentStates[index]
                    val blend = target.blend
                    if (blend != null) {
                        if (usesConstantColorFactor(blend)) {
                            throw LwjglVulkanException(
                                "ConstantColor blend factors are not supported by the Vulkan " +
                                    "backend; the portable API offers no way to set the " +
                                    "blend constants",
                            )
                        }
                        attachment.blendEnable(true)
                            .srcColorBlendFactor(blend.color.srcFactor.toVkBlendFactor())
                            .dstColorBlendFactor(blend.color.dstFactor.toVkBlendFactor())
                            .colorBlendOp(blend.color.operation.toVkBlendOp())
                            .srcAlphaBlendFactor(blend.alpha.srcFactor.toVkBlendFactor())
                            .dstAlphaBlendFactor(blend.alpha.dstFactor.toVkBlendFactor())
                            .alphaBlendOp(blend.alpha.operation.toVkBlendOp())
                    } else {
                        attachment.blendEnable(false)
                            .srcColorBlendFactor(BlendFactor.One.toVkBlendFactor())
                            .dstColorBlendFactor(BlendFactor.Zero.toVkBlendFactor())
                            .colorBlendOp(BlendOperation.Add.toVkBlendOp())
                            .srcAlphaBlendFactor(BlendFactor.One.toVkBlendFactor())
                            .dstAlphaBlendFactor(BlendFactor.Zero.toVkBlendFactor())
                            .alphaBlendOp(BlendOperation.Add.toVkBlendOp())
                    }
                    attachment.colorWriteMask(buildColorWriteMask(target.writeMask))
                }
                VkPipelineColorBlendStateCreateInfo.calloc(stack).also { state ->
                    state.sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .logicOpEnable(false)
                        .logicOp(VK10.VK_LOGIC_OP_COPY)
                        .attachmentCount(targets.size)
                        .pAttachments(attachmentStates)
                }
            }

            val dynamicStates = stack.callocInt(2)
            dynamicStates.put(0, VK10.VK_DYNAMIC_STATE_VIEWPORT)
            dynamicStates.put(1, VK10.VK_DYNAMIC_STATE_SCISSOR)
            val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
            // The LWJGL setter derives dynamicStateCount from the buffer size.
            dynamicState.pDynamicStates(dynamicStates)

            // Dynamic rendering: the state that a render pass would normally carry is supplied
            // here, so no VkRenderPass or VkFramebuffer exists in this backend.
            val colorFormats = stack.callocInt(targets.size)
            targets.forEachIndexed { index, target ->
                colorFormats.put(index, target.format.toVkFormat())
            }
            val renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType(VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
            renderingCreateInfo.viewMask(0)
                .colorAttachmentCount(targets.size)
                .pColorAttachmentFormats(colorFormats)
            val depthState = descriptor.depthStencil
            renderingCreateInfo.depthAttachmentFormat(
                depthState?.format?.toVkFormat() ?: VK10.VK_FORMAT_UNDEFINED,
            )
            renderingCreateInfo.stencilAttachmentFormat(
                if (depthState != null && depthState.format.hasStencilAspect()) {
                    depthState.format.toVkFormat()
                } else {
                    VK10.VK_FORMAT_UNDEFINED
                },
            )

            val createInfos = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            val createInfo = createInfos[0]
                .sType(VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            createInfo.pNext(renderingCreateInfo)
                .pStages(stageInfos)
                .pVertexInputState(vertexInputState)
                .pInputAssemblyState(inputAssemblyState)
                .pViewportState(viewportState)
                .pRasterizationState(rasterizationState)
                .pMultisampleState(multisampleState)
                .pDepthStencilState(depthStencilState)
                .pColorBlendState(colorBlendState)
                .pDynamicState(dynamicState)
                .layout(pipelineLayout)
                .renderPass(0L)
                .subpass(0)
                .basePipelineHandle(0L)
                .basePipelineIndex(-1)

            val pipelinePointer = stack.callocLong(1)
            checkVkResult(
                VK10.vkCreateGraphicsPipelines(
                    logicalDevice,
                    VK10.VK_NULL_HANDLE,
                    createInfos,
                    null,
                    pipelinePointer,
                ),
                "vkCreateGraphicsPipelines",
            )
            LwjglPipelineResource(pipeline = pipelinePointer[0])
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
            VK10.vkDestroyImageView(logicalDevice, texture.view, null)
            VK10.vkDestroyImage(logicalDevice, texture.image, null)
            VK10.vkFreeMemory(logicalDevice, texture.memory, null)
            return
        }
        val sampler = samplers.remove(token)
        if (sampler != null) {
            VK10.vkDestroySampler(logicalDevice, sampler.sampler, null)
            return
        }
        val shader = shaders.remove(token)
        if (shader != null) {
            VK10.vkDestroyShaderModule(logicalDevice, shader.module, null)
            return
        }
        val pipeline = pipelines.remove(token)
        if (pipeline != null) {
            VK10.vkDestroyPipeline(logicalDevice, pipeline.pipeline, null)
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
            MemoryStack.stackPush().use { stack ->
                val submitInfos = VkSubmitInfo.calloc(submitted.size, stack)
                submitted.forEachIndexed { index, commandBuffer ->
                    val entry = stack.mallocPointer(1)
                    entry.put(0, commandBuffer.address())
                    submitInfos[index]
                        .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(entry)
                }
                checkVkResult(
                    VK10.vkQueueSubmit(queue, submitInfos, fence),
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
        VK10.vkDeviceWaitIdle(logicalDevice)
        val inFlightIterator = inFlight.entries.iterator()
        while (inFlightIterator.hasNext()) {
            val submission = inFlightIterator.next().value
            freeCommandBuffers(submission.commandBuffers)
            VK10.vkDestroyFence(logicalDevice, submission.fence, null)
            inFlightIterator.remove()
        }

        buffers.values.forEach { buffer -> releaseBufferResource(buffer) }
        buffers.clear()
        textures.values.forEach { texture ->
            VK10.vkDestroyImageView(logicalDevice, texture.view, null)
            VK10.vkDestroyImage(logicalDevice, texture.image, null)
            VK10.vkFreeMemory(logicalDevice, texture.memory, null)
        }
        textures.clear()
        samplers.values.forEach { sampler ->
            VK10.vkDestroySampler(logicalDevice, sampler.sampler, null)
        }
        samplers.clear()
        shaders.values.forEach { shader ->
            VK10.vkDestroyShaderModule(logicalDevice, shader.module, null)
        }
        shaders.clear()
        pipelines.values.forEach { pipeline ->
            VK10.vkDestroyPipeline(logicalDevice, pipeline.pipeline, null)
        }
        pipelines.clear()
        if (commandBuffers.isNotEmpty()) {
            freeCommandBuffers(commandBuffers.values.toList())
            commandBuffers.clear()
        }

        VK10.vkDestroyCommandPool(logicalDevice, commandPool, null)
        VK10.vkDestroyPipelineLayout(logicalDevice, pipelineLayout, null)
        VK10.vkDestroyDevice(logicalDevice, null)
        VK10.vkDestroyInstance(instance, null)
        // The JVM loader library stays mapped for the process lifetime; other Vulkan code may
        // still reference it, so no native close happens here.
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
     * @throws LwjglVulkanException if the texture bytes cannot be staged in host visible memory.
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
            ?: throw LwjglVulkanException(
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

    private fun createLogicalDevice(): VkDevice = MemoryStack.stackPush().use { stack ->
        val queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
        // The LWJGL setter derives queueCount from the priorities buffer size.
        queueCreateInfo[0]
            .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
            .queueFamilyIndex(summary.queueFamilyIndex)
            .pQueuePriorities(stack.floats(1.0f))

        val features2 = VkPhysicalDeviceFeatures2.calloc(stack)
            .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
        if (summary.supportsSamplerAnisotropy) {
            features2.features().samplerAnisotropy(true)
        }
        if (summary.supportsDepthClamp) {
            features2.features().depthClamp(true)
        }
        val vulkan13Features = VkPhysicalDeviceVulkan13Features.calloc(stack)
            .sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
        vulkan13Features.dynamicRendering(true)
        features2.pNext(vulkan13Features)

        val createInfo = VkDeviceCreateInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
        createInfo.pNext(features2)
            .pQueueCreateInfos(queueCreateInfo)

        val devicePointer = stack.mallocPointer(1)
        checkVkResult(
            VK10.vkCreateDevice(physicalDevice, createInfo, null, devicePointer),
            "vkCreateDevice",
        )
        // The wrapper binds the device capability table while the create info is still alive;
        // the construction has to stay inside the stack scope that owns it.
        VkDevice(devicePointer[0], physicalDevice, createInfo)
    }

    private fun fetchQueue(): VkQueue = MemoryStack.stackPush().use { stack ->
        val queuePointer = stack.mallocPointer(1)
        VK10.vkGetDeviceQueue(logicalDevice, summary.queueFamilyIndex, 0, queuePointer)
        VkQueue(queuePointer[0], logicalDevice)
    }

    private fun createCommandPool(): Long = MemoryStack.stackPush().use { stack ->
        val createInfo = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
        createInfo.flags(VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
            .queueFamilyIndex(summary.queueFamilyIndex)
        val poolPointer = stack.callocLong(1)
        checkVkResult(
            VK10.vkCreateCommandPool(logicalDevice, createInfo, null, poolPointer),
            "vkCreateCommandPool",
        )
        poolPointer[0]
    }

    /** The engine records no descriptor sets yet, so the shared pipeline layout stays empty. */
    private fun createEmptyPipelineLayout(): Long = MemoryStack.stackPush().use { stack ->
        val createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
        createInfo.setLayoutCount(0)
            .pSetLayouts(null)
            .pPushConstantRanges(null)
        val layoutPointer = stack.callocLong(1)
        checkVkResult(
            VK10.vkCreatePipelineLayout(logicalDevice, createInfo, null, layoutPointer),
            "vkCreatePipelineLayout",
        )
        layoutPointer[0]
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
        requiredFlags: Int,
        operation: String,
    ): LwjglMemoryAllocation {
        val typeIndex = allocator.findType(requirements.memoryTypeBits(), requiredFlags)
        if (typeIndex >= 0) {
            return allocateFromType(typeIndex, requirements)
        }
        val fallback = allocator.findType(
            requirements.memoryTypeBits(),
            MemoryProperty.HOST_VISIBLE,
        )
        if (fallback >= 0) {
            return allocateFromType(fallback, requirements)
        }
        throw LwjglVulkanException("No compatible Vulkan memory type for $operation")
    }

    private fun allocateFromType(
        typeIndex: Int,
        requirements: VkMemoryRequirements,
    ): LwjglMemoryAllocation = MemoryStack.stackPush().use { stack ->
        val allocateInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
        allocateInfo.allocationSize(requirements.size())
            .memoryTypeIndex(typeIndex)

        val memoryPointer = stack.callocLong(1)
        checkVkResult(
            VK10.vkAllocateMemory(logicalDevice, allocateInfo, null, memoryPointer),
            "vkAllocateMemory",
        )
        LwjglMemoryAllocation(
            memory = memoryPointer[0],
            allocationSize = requirements.size(),
            memoryTypeIndex = typeIndex,
            hostVisible = allocator.isHostVisible(typeIndex),
            coherent = allocator.isCoherent(typeIndex),
        )
    }

    private fun allocateBufferResource(descriptor: BufferDescriptor): LwjglBufferResource =
        MemoryStack.stackPush().use { stack ->
            val size = descriptor.sizeBytes
            val createInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            createInfo.size(size)
                .usage(descriptor.usage.toVkBufferUsage())
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)

            val bufferPointer = stack.callocLong(1)
            checkVkResult(
                VK10.vkCreateBuffer(logicalDevice, createInfo, null, bufferPointer),
                "vkCreateBuffer",
            )
            val buffer = bufferPointer[0]

            try {
                val requirements = VkMemoryRequirements.calloc(stack)
                VK10.vkGetBufferMemoryRequirements(logicalDevice, buffer, requirements)

                // Uniform and storage buffers keep upload staging out of mapped memory; other
                // usages prefer a mapping so engine writes do not need a copy.
                val requiredFlags = if (descriptor.usage.contains(BufferUsage.Uniform) ||
                    descriptor.usage.contains(BufferUsage.Storage)
                ) {
                    MemoryProperty.DEVICE_LOCAL
                } else {
                    MemoryProperty.HOST_VISIBLE
                }
                val operation = "buffer '${descriptor.label ?: "unnamed"}'"
                val allocation = allocateMemory(requirements, requiredFlags, operation)
                val mapped: ByteBuffer? = if (allocation.hostVisible) {
                    mapWholeMemory(allocation.memory, allocation.allocationSize)
                } else {
                    null
                }
                checkVkResult(
                    VK10.vkBindBufferMemory(logicalDevice, buffer, allocation.memory, 0L),
                    "vkBindBufferMemory",
                )

                LwjglBufferResource(
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
                VK10.vkDestroyBuffer(logicalDevice, buffer, null)
                throw error
            }
        }

    private fun mapWholeMemory(memory: Long, size: Long): ByteBuffer =
        MemoryStack.stackPush().use { stack ->
            if (size > Int.MAX_VALUE.toLong()) {
                throw LwjglVulkanException(
                    "Device memory of $size bytes cannot be mapped by the JVM backend, which " +
                        "only supports buffers up to ${Int.MAX_VALUE} bytes",
                )
            }
            val mappedPointer = stack.mallocPointer(1)
            checkVkResult(
                VK10.vkMapMemory(logicalDevice, memory, 0L, size, 0, mappedPointer),
                "vkMapMemory",
            )
            val mapped = mappedPointer[0]
            if (mapped == 0L) {
                throw LwjglVulkanException("vkMapMemory returned a null mapping")
            }
            MemoryUtil.memByteBuffer(mapped, size.toInt())
        }

    /**
     * Copies [data] into the destination buffer. When the buffer is host visible the write is
     * applied directly to its persistent mapping; device local memory is fed through a staging
     * buffer whose copy runs to completion before the call returns.
     */
    private fun stageBufferUpload(
        target: LwjglBufferResource,
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
            ?: throw LwjglVulkanException("Staging buffers must live in host visible memory")
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

    private fun releaseBufferResource(resource: LwjglBufferResource) {
        if (resource.mapped != null) {
            VK10.vkUnmapMemory(logicalDevice, resource.memory)
        }
        VK10.vkDestroyBuffer(logicalDevice, resource.buffer, null)
        VK10.vkFreeMemory(logicalDevice, resource.memory, null)
    }

    // ---------------------------------------------------------------------
    // Submission plumbing
    // ---------------------------------------------------------------------

    private fun allocateTransientCommandBuffer(): VkCommandBuffer = MemoryStack.stackPush().use { stack ->
        val allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
        allocateInfo.commandPool(commandPool)
            .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1)
        val bufferPointer = stack.mallocPointer(1)
        checkVkResult(
            VK10.vkAllocateCommandBuffers(logicalDevice, allocateInfo, bufferPointer),
            "vkAllocateCommandBuffers",
        )
        VkCommandBuffer(bufferPointer[0], logicalDevice)
    }

    private fun createWaitFence(): Long = MemoryStack.stackPush().use { stack ->
        val createInfo = VkFenceCreateInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
        val fencePointer = stack.callocLong(1)
        checkVkResult(
            VK10.vkCreateFence(logicalDevice, createInfo, null, fencePointer),
            "vkCreateFence",
        )
        fencePointer[0]
    }

    /**
     * Submits [submitted] and blocks until their fence signals the work complete.
     *
     * The fence of a single `vkQueueSubmit` call signals after every entry of the call completed
     * on the queue, which covers all work that was ordered before it.
     */
    private fun submitAndWait(submitted: List<VkCommandBuffer>) {
        val fence = createWaitFence()
        try {
            MemoryStack.stackPush().use { stack ->
                val submitInfos = VkSubmitInfo.calloc(submitted.size, stack)
                submitted.forEachIndexed { index, commandBuffer ->
                    val entry = stack.mallocPointer(1)
                    entry.put(0, commandBuffer.address())
                    submitInfos[index]
                        .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(entry)
                }
                checkVkResult(
                    VK10.vkQueueSubmit(queue, submitInfos, fence),
                    "vkQueueSubmit",
                )
            }
            MemoryStack.stackPush().use { stack ->
                // -1 is the all bits set sentinel the driver understands as "wait forever".
                checkVkResult(
                    VK10.vkWaitForFences(
                        logicalDevice,
                        stack.longs(fence),
                        true,
                        -1L,
                    ),
                    "vkWaitForFences",
                )
                checkVkResult(
                    VK10.vkResetFences(logicalDevice, stack.longs(fence)),
                    "vkResetFences",
                )
            }
        } finally {
            VK10.vkDestroyFence(logicalDevice, fence, null)
        }
    }

    private fun waitForDeviceIdle(reason: String) {
        checkVkResult(VK10.vkDeviceWaitIdle(logicalDevice), "vkDeviceWaitIdle ($reason)")
    }

    private fun flushMappedMemory(memory: Long) {
        MemoryStack.stackPush().use { stack ->
            val range = VkMappedMemoryRange.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
            range.memory(memory)
                .offset(0L)
                .size(VK10.VK_WHOLE_SIZE)
            checkVkResult(
                VK10.vkFlushMappedMemoryRanges(logicalDevice, range),
                "vkFlushMappedMemoryRanges",
            )
        }
    }

    private fun invalidateMappedMemory(memory: Long) {
        MemoryStack.stackPush().use { stack ->
            val range = VkMappedMemoryRange.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE)
            range.memory(memory)
                .offset(0L)
                .size(VK10.VK_WHOLE_SIZE)
            checkVkResult(
                VK10.vkInvalidateMappedMemoryRanges(logicalDevice, range),
                "vkInvalidateMappedMemoryRanges",
            )
        }
    }

    private fun freeCommandBuffers(list: List<VkCommandBuffer>) {
        if (list.isEmpty()) return
        MemoryStack.stackPush().use { stack ->
            val array = stack.callocPointer(list.size)
            list.forEachIndexed { index, commandBuffer ->
                array.put(index, commandBuffer.address())
            }
            VK10.vkFreeCommandBuffers(logicalDevice, commandPool, array)
        }
    }

    /**
     * Frees every resource of a submission whose fence has signaled.
     */
    private fun reapCompletedSubmissions() {
        if (inFlight.isEmpty()) return
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val submission = iterator.next().value
            if (VK10.vkGetFenceStatus(logicalDevice, submission.fence) == VK10.VK_SUCCESS) {
                freeCommandBuffers(submission.commandBuffers)
                VK10.vkDestroyFence(logicalDevice, submission.fence, null)
                iterator.remove()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Resolution and validation
    // ---------------------------------------------------------------------

    private fun resolveBuffer(token: Long): LwjglBufferResource =
        buffers[token] ?: throw IllegalArgumentException("Unknown buffer token: $token")

    private fun resolveTexture(token: Long): LwjglTextureResource =
        textures[token] ?: throw IllegalArgumentException("Unknown texture token: $token")

    private fun resolvePipeline(token: Long): LwjglPipelineResource =
        pipelines[token] ?: throw IllegalArgumentException("Unknown pipeline token: $token")

    private fun resolveShader(token: Long, expectedStage: ShaderStage): LwjglShaderResource {
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
    private fun readSpirvWord(code: ByteArray, wordIndex: Int): Int {
        val base = wordIndex * 4
        return (code[base].toInt() and 0xFF) or
            ((code[base + 1].toInt() and 0xFF) shl 8) or
            ((code[base + 2].toInt() and 0xFF) shl 16) or
            ((code[base + 3].toInt() and 0xFF) shl 24)
    }

    private fun buildColorWriteMask(mask: ColorWriteMask): Int {
        var bits = 0
        if (mask.contains(ColorChannel.Red)) bits = bits or VK10.VK_COLOR_COMPONENT_R_BIT
        if (mask.contains(ColorChannel.Green)) bits = bits or VK10.VK_COLOR_COMPONENT_G_BIT
        if (mask.contains(ColorChannel.Blue)) bits = bits or VK10.VK_COLOR_COMPONENT_B_BIT
        if (mask.contains(ColorChannel.Alpha)) bits = bits or VK10.VK_COLOR_COMPONENT_A_BIT
        return bits
    }

    private fun usesConstantColorFactor(blend: BlendState): Boolean {
        val factors = listOf(
            blend.color.srcFactor,
            blend.color.dstFactor,
            blend.alpha.srcFactor,
            blend.alpha.dstFactor,
        )
        return factors.contains(BlendFactor.ConstantColor) ||
            factors.contains(BlendFactor.OneMinusConstantColor)
    }
}

/**
 * Vulkan memory property flags used by the backend device, mirroring the driver values.
 */
private object MemoryProperty {
    const val DEVICE_LOCAL: Int = 0x00000001
    const val HOST_VISIBLE: Int = 0x00000002
}

/**
 * Builds the Vulkan buffer usage bitmask; transfer bits are always present so staging reads
 * (readback, uploads, and future mip generation) can address any buffer.
 */
private fun Set<BufferUsage>.toVkBufferUsage(): Int {
    var bits = 0
    if (contains(BufferUsage.Vertex)) bits = bits or VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
    if (contains(BufferUsage.Index)) bits = bits or VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT
    if (contains(BufferUsage.Uniform)) bits = bits or VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
    if (contains(BufferUsage.Storage)) bits = bits or VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
    if (contains(BufferUsage.Indirect)) bits = bits or VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
    if (contains(BufferUsage.CopySource)) bits = bits or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
    if (contains(BufferUsage.CopyDestination)) {
        bits = bits or VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
    }
    return bits or VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT or VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT
}

/**
 * Builds the Vulkan image usage bitmask; transfer bits are always present so readback and uploads
 * can address any texture.
 */
private fun Set<TextureUsage>.toVkImageUsage(): Int {
    var bits = 0
    if (contains(TextureUsage.Sampled)) bits = bits or VK10.VK_IMAGE_USAGE_SAMPLED_BIT
    if (contains(TextureUsage.RenderTarget)) {
        bits = bits or VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
    }
    if (contains(TextureUsage.DepthStencil)) {
        bits = bits or VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
    }
    if (contains(TextureUsage.Storage)) bits = bits or VK10.VK_IMAGE_USAGE_STORAGE_BIT
    if (contains(TextureUsage.CopySource)) bits = bits or VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
    if (contains(TextureUsage.CopyDestination)) {
        bits = bits or VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
    }
    return bits or VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
}

private fun TextureFormat.toAspectMask(): Int = when (this) {
    TextureFormat.Depth24PlusStencil8 ->
        VK10.VK_IMAGE_ASPECT_DEPTH_BIT or VK10.VK_IMAGE_ASPECT_STENCIL_BIT
    TextureFormat.Depth16Unorm, TextureFormat.Depth24Plus, TextureFormat.Depth32Float ->
        VK10.VK_IMAGE_ASPECT_DEPTH_BIT
    else -> VK10.VK_IMAGE_ASPECT_COLOR_BIT
}

/** Whether the format carries a stencil aspect usable for stencil attachment formats. */
private fun TextureFormat.hasStencilAspect(): Boolean =
    this == TextureFormat.Depth24PlusStencil8

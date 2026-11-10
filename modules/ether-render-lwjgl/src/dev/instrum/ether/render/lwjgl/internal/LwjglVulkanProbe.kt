package dev.instrum.ether.render.lwjgl.internal

import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.lwjgl.LwjglVulkanException
import dev.instrum.ether.render.lwjgl.NativeBootstrap
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkFormatProperties
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features
import org.lwjgl.vulkan.VkQueueFamilyProperties

/**
 * The property values a device needs after the probe call completed.
 */
internal data class LwjglPhysicalDeviceSnapshot(
    val apiVersion: Int,
    val deviceType: Int,
    val deviceName: String,
)

/**
 * The optional device features the backend depends on.
 */
internal data class LwjglDeviceFeatures(
    val dynamicRendering: Boolean,
    val samplerAnisotropy: Boolean,
    val depthClamp: Boolean,
)

/**
 * A short lived Vulkan instance used to inspect the available physical devices.
 *
 * The probe owns the Vulkan instance created through the LWJGL loader. A device created from a
 * probe takes the instance over; an unused probe releases it. Contrary to the Kotlin/Native
 * backend, the JVM process keeps the loader library mapped for its lifetime, so no explicit
 * loader close happens on the JVM: unloading a library other Vulkan code may still reference
 * would be unsafe.
 */
internal class LwjglVulkanProbe(
    val instance: VkInstance,
    val physicalDevice: VkPhysicalDevice,
    val summary: LwjglPhysicalDeviceSummary,
    val capabilities: GraphicsCapabilities,
) {
    /** Releases the Vulkan instance; the JVM loader library stays mapped. */
    fun destroy() {
        VK10.vkDestroyInstance(instance, null)
    }

    companion object {
        /**
         * Prepares the LWJGL natives, opens the loader and selects the best usable physical device.
         *
         * @param preferredDeviceName when set, only a device with this exact name is considered.
         * @throws LwjglVulkanException if no usable device exists.
         */
        fun create(preferredDeviceName: String? = null): LwjglVulkanProbe {
            NativeBootstrap.ensure()
            try {
                // LWJGL opens the loader in the static initializer of `VK`: merely loading the
                // class initializes Vulkan, and an explicit `VK.create()` would only throw
                // "Vulkan has already been created". Deployments that set VULKAN_EXPLICIT_INIT
                // own the loader themselves; the instance commands below then assume the loader
                // is already initialized. Contrary to the Kotlin/Native backend there is no
                // loader close: the library stays mapped for the process lifetime.
                Class.forName("org.lwjgl.vulkan.VK")
            } catch (error: Throwable) {
                val cause = if (error is ExceptionInInitializerError) error.cause ?: error else error
                throw LwjglVulkanException("The Vulkan loader could not be loaded: $cause", cause)
            }

            val loaderVersion = VK.getInstanceVersionSupported()
            if (loaderVersion < VK13.VK_API_VERSION_1_3) {
                throw LwjglVulkanException(
                    "The Vulkan loader reports version ${formatVersion(loaderVersion)}; " +
                        "Ether requires at least 1.3 for dynamic rendering",
                )
            }

            val instance = createInstance(VK13.VK_API_VERSION_1_3)
            try {
                val candidates = enumerateCandidates(instance)
                val selected = selectCandidate(candidates, preferredDeviceName)
                    ?: throw LwjglVulkanException(
                        if (preferredDeviceName == null) {
                            "No Vulkan physical device with a graphics queue and Vulkan 1.3 " +
                                "dynamic rendering support was found"
                        } else {
                            "No Vulkan physical device named '$preferredDeviceName' with a " +
                                "graphics queue and Vulkan 1.3 dynamic rendering support was found"
                        },
                    )
                val summary = describe(selected)
                val formats = querySupportedFormats(selected)
                if (formats.isEmpty()) {
                    throw LwjglVulkanException(
                        "Vulkan device '${summary.name}' supports none of the Ether texture formats",
                    )
                }
                return LwjglVulkanProbe(
                    instance = instance,
                    physicalDevice = selected,
                    summary = summary,
                    capabilities = buildCapabilities(summary, formats),
                )
            } catch (error: Throwable) {
                VK10.vkDestroyInstance(instance, null)
                throw error
            }
        }

        private fun createInstance(apiVersion: Int): VkInstance =
            MemoryStack.stackPush().use { stack ->
                val applicationInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Ether"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("Ether"))
                    .engineVersion(1)
                    .apiVersion(apiVersion)

                val createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(applicationInfo)

                val instancePointer: PointerBuffer = stack.mallocPointer(1)
                checkVkResult(
                    VK10.vkCreateInstance(createInfo, null, instancePointer),
                    "vkCreateInstance",
                )
                // The wrapper reads the create info to bind the per-instance capability table, so
                // the construction has to stay inside the stack scope that owns the info struct.
                VkInstance(instancePointer[0], createInfo)
            }

        private fun enumerateCandidates(instance: VkInstance): List<VkPhysicalDevice> =
            MemoryStack.stackPush().use { stack ->
                val count = stack.mallocInt(1)
                checkVkResult(
                    VK10.vkEnumeratePhysicalDevices(instance, count, null),
                    "vkEnumeratePhysicalDevices",
                )
                if (count[0] == 0) return emptyList()
                val devices = stack.mallocPointer(count[0])
                checkVkResult(
                    VK10.vkEnumeratePhysicalDevices(instance, count, devices),
                    "vkEnumeratePhysicalDevices",
                )
                List(devices.remaining()) { index -> VkPhysicalDevice(devices[index], instance) }
            }

        private fun selectCandidate(
            candidates: List<VkPhysicalDevice>,
            preferredDeviceName: String?,
        ): VkPhysicalDevice? {
            if (candidates.isEmpty()) return null
            if (preferredDeviceName != null) {
                return candidates.firstOrNull { device ->
                    snapshot(device).deviceName == preferredDeviceName && isUsable(device)
                }
            }
            return candidates
                .filter { isUsable(it) }
                .maxByOrNull { device -> deviceScore(snapshot(device)) }
        }

        private fun isUsable(device: VkPhysicalDevice): Boolean {
            val properties = snapshot(device)
            if (properties.apiVersion < VK13.VK_API_VERSION_1_3) return false
            if (findGraphicsQueueFamily(device) == null) return false
            return queryFeatures(device).dynamicRendering
        }

        private fun deviceScore(properties: LwjglPhysicalDeviceSnapshot): Int {
            val typeScore = when (properties.deviceType) {
                VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 3
                VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 2
                VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 1
                VK_PHYSICAL_DEVICE_TYPE_CPU -> 0
                else -> 0
            }
            return typeScore * 1_000_000
        }

        private fun snapshot(device: VkPhysicalDevice): LwjglPhysicalDeviceSnapshot =
            MemoryStack.stackPush().use { stack ->
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                VK10.vkGetPhysicalDeviceProperties(device, properties)
                LwjglPhysicalDeviceSnapshot(
                    apiVersion = properties.apiVersion(),
                    deviceType = properties.deviceType(),
                    deviceName = properties.deviceNameString(),
                )
            }

        private fun findGraphicsQueueFamily(device: VkPhysicalDevice): Int? =
            MemoryStack.stackPush().use { stack ->
                val count = stack.mallocInt(1)
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, null)
                if (count[0] == 0) return null
                val families = VkQueueFamilyProperties.malloc(count[0], stack)
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, count, families)
                for (index in 0 until count[0]) {
                    val family = families[index]
                    if (family.queueFlags() and VK10.VK_QUEUE_GRAPHICS_BIT != 0 &&
                        family.queueCount() > 0
                    ) {
                        return index
                    }
                }
                null
            }

        private fun describe(device: VkPhysicalDevice): LwjglPhysicalDeviceSummary =
            MemoryStack.stackPush().use { stack ->
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                VK10.vkGetPhysicalDeviceProperties(device, properties)

                val queueFamilyIndex = findGraphicsQueueFamily(device)
                    ?: throw LwjglVulkanException("The Vulkan device has no graphics queue family")

                val features = queryFeatures(device)
                val memory = VkPhysicalDeviceMemoryProperties.calloc(stack)
                VK10.vkGetPhysicalDeviceMemoryProperties(device, memory)

                val queueCount = stack.mallocInt(1)
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, queueCount, null)
                val families = VkQueueFamilyProperties.malloc(queueCount[0], stack)
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(device, queueCount, families)
                val family = families[queueFamilyIndex]

                val memoryTypes = buildList {
                    for (index in 0 until memory.memoryTypeCount()) {
                        val type = memory.memoryTypes(index)
                        add(LwjglMemoryType(type.propertyFlags(), type.heapIndex()))
                    }
                }
                val memoryHeaps = buildList {
                    for (index in 0 until memory.memoryHeapCount()) {
                        val heap = memory.memoryHeaps(index)
                        add(LwjglMemoryHeap(heap.size(), heap.flags()))
                    }
                }

                val limits = properties.limits()
                val compute = limits.maxComputeWorkGroupSize()

                LwjglPhysicalDeviceSummary(
                    name = properties.deviceNameString().ifEmpty { "Unknown Vulkan device" },
                    apiVersion = properties.apiVersion(),
                    driverVersion = properties.driverVersion(),
                    vendorId = properties.vendorID(),
                    deviceType = properties.deviceType(),
                    queueFamilyIndex = queueFamilyIndex,
                    queueCount = family.queueCount(),
                    timestampValidBits = family.timestampValidBits(),
                    supportsDynamicRendering = features.dynamicRendering,
                    supportsSamplerAnisotropy = features.samplerAnisotropy,
                    supportsDepthClamp = features.depthClamp,
                    maxSamplerAnisotropy = limits.maxSamplerAnisotropy(),
                    maxImageDimension1D = limits.maxImageDimension1D(),
                    maxImageDimension2D = limits.maxImageDimension2D(),
                    maxImageDimension3D = limits.maxImageDimension3D(),
                    maxImageDimensionCube = limits.maxImageDimensionCube(),
                    maxImageArrayLayers = limits.maxImageArrayLayers(),
                    maxColorAttachments = limits.maxColorAttachments(),
                    maxVertexInputBindings = limits.maxVertexInputBindings(),
                    maxVertexInputAttributes = limits.maxVertexInputAttributes(),
                    maxVertexInputBindingStride = limits.maxVertexInputBindingStride(),
                    maxBoundDescriptorSets = limits.maxBoundDescriptorSets(),
                    maxPerStageDescriptorSamplers = limits.maxPerStageDescriptorSamplers(),
                    maxPerStageDescriptorUniformBuffers =
                        limits.maxPerStageDescriptorUniformBuffers(),
                    maxPerStageDescriptorStorageBuffers =
                        limits.maxPerStageDescriptorStorageBuffers(),
                    maxPerStageDescriptorSampledImages = limits.maxPerStageDescriptorSampledImages(),
                    maxUniformBufferRange = limits.maxUniformBufferRange(),
                    maxStorageBufferRange = limits.maxStorageBufferRange(),
                    maxPushConstantsSize = limits.maxPushConstantsSize(),
                    nonCoherentAtomSize = limits.nonCoherentAtomSize(),
                    maxComputeWorkGroupSize = List(3) { compute[it] },
                    maxComputeWorkGroupInvocations = limits.maxComputeWorkGroupInvocations(),
                    framebufferColorSampleCounts = limits.framebufferColorSampleCounts(),
                    framebufferDepthSampleCounts = limits.framebufferDepthSampleCounts(),
                    framebufferNoAttachmentsSampleCounts =
                        limits.framebufferNoAttachmentsSampleCounts(),
                    memoryTypes = memoryTypes,
                    memoryHeaps = memoryHeaps,
                )
            }

        private fun queryFeatures(device: VkPhysicalDevice): LwjglDeviceFeatures =
            MemoryStack.stackPush().use { stack ->
                val features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                val vulkan13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                features2.pNext(vulkan13)
                VK11.vkGetPhysicalDeviceFeatures2(device, features2)
                LwjglDeviceFeatures(
                    dynamicRendering = vulkan13.dynamicRendering(),
                    samplerAnisotropy = features2.features().samplerAnisotropy(),
                    depthClamp = features2.features().depthClamp(),
                )
            }

        private fun querySupportedFormats(device: VkPhysicalDevice): Set<TextureFormat> {
            val supported = LinkedHashSet<TextureFormat>()
            for (format in TextureFormat.entries) {
                val featureBits = MemoryStack.stackPush().use { stack ->
                    val properties = VkFormatProperties.calloc(stack)
                    VK10.vkGetPhysicalDeviceFormatProperties(device, format.toVkFormat(), properties)
                    properties.optimalTilingFeatures()
                }
                if (featureBits == 0) continue
                val attachmentSupported = if (format.isDepthStencil) {
                    featureBits and VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT != 0
                } else {
                    featureBits and VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT != 0
                }
                if (!attachmentSupported) continue
                if (featureBits and VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT == 0) continue
                if (featureBits and VK13.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT == 0) continue
                if (featureBits and VK13.VK_FORMAT_FEATURE_TRANSFER_DST_BIT == 0) continue
                supported += format
            }
            return supported
        }
    }
}

/**
 * Formats a Vulkan version number as `major.minor.patch`.
 */
internal fun formatVersion(version: Int): String {
    val major = version shr 22
    val minor = (version shr 12) and 0x3FF
    val patch = (version shr 10) and 0x3FF
    return "$major.$minor.$patch"
}

/**
 * Builds the portable capabilities report from a physical device summary.
 */
internal fun buildCapabilities(
    summary: LwjglPhysicalDeviceSummary,
    supportedFormats: Set<TextureFormat>,
): GraphicsCapabilities {
    val features = buildSet {
        add(GraphicsFeature.InstancedDrawing)
        if (summary.supportsDepthClamp) {
            add(GraphicsFeature.DepthClamp)
        }
        if (summary.supportsSamplerAnisotropy && summary.maxSamplerAnisotropy > 1f) {
            add(GraphicsFeature.AnisotropicFiltering)
        }
    }

    // The portable API has no descriptor set or push constant concept yet, so the backend reports
    // zero for every binding limit instead of advertising device limits it cannot honour.
    val limits = GraphicsLimits(
        maxTexture1DSize = summary.maxImageDimension1D,
        maxTexture2DSize = summary.maxImageDimension2D,
        maxTexture3DSize = summary.maxImageDimension3D,
        maxTextureCubeSize = summary.maxImageDimensionCube,
        maxTextureArrayLayers = summary.maxImageArrayLayers,
        maxColorAttachments = summary.maxColorAttachments,
        maxSampleCount = 1,
        maxVertexBuffers = summary.maxVertexInputBindings,
        maxVertexAttributes = summary.maxVertexInputAttributes,
        maxVertexBufferStride = summary.maxVertexInputBindingStride,
        maxBindGroups = 0,
        maxBindingsPerGroup = 0,
        maxSampledTexturesPerStage = 0,
        maxSamplersPerStage = 0,
        maxUniformBuffersPerStage = 0,
        maxStorageBuffersPerStage = 0,
        maxUniformBufferBindingSize = 0L,
        maxStorageBufferBindingSize = 0L,
        maxBufferSize = largestHeapSize(summary),
        maxPushConstantSize = 0,
        maxComputeWorkgroupSizeX = 0,
        maxComputeWorkgroupSizeY = 0,
        maxComputeWorkgroupSizeZ = 0,
        maxComputeInvocations = 0,
        maxDrawCallsPerFrame = 100_000,
        maxFramesInFlight = 2,
        maxSamplerAnisotropy = summary.maxSamplerAnisotropy.toInt().coerceAtLeast(1),
    )

    val hasHostVisibleMemory = summary.memoryTypes.any { type ->
        type.propertyFlags and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0
    }

    return GraphicsCapabilities(
        backend = BackendKind.Vulkan,
        features = features,
        limits = limits,
        supportedTextureFormats = supportedFormats,
        clipSpaceDepthZeroToOne = true,
        framebufferOriginBottomLeft = false,
        supportsMappableBuffers = hasHostVisibleMemory,
        supportsPersistentMappedBuffers = hasHostVisibleMemory,
    )
}

/**
 * Returns the size of the largest memory heap, which bounds a single buffer allocation.
 */
private fun largestHeapSize(summary: LwjglPhysicalDeviceSummary): Long {
    val largest = summary.memoryHeaps.maxOfOrNull { it.size } ?: 0L
    return if (largest > 0L) largest else 1L
}

/**
 * Verifies a Vulkan result and raises [LwjglVulkanException] on failure.
 */
internal fun checkVkResult(result: Int, what: String) {
    if (result != VK10.VK_SUCCESS) {
        throw LwjglVulkanException("$what failed with result 0x${result.toUInt().toString(16)}")
    }
}

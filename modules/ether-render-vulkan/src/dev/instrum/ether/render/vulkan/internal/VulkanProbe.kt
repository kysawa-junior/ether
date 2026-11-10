@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.vulkan.VulkanException
import dev.instrum.ether.render.vulkan.internal.vkc.VK_API_VERSION_1_1
import dev.instrum.ether.render.vulkan.internal.vkc.VK_API_VERSION_1_3
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_FEATURE_TRANSFER_DST_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_CPU
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU
import dev.instrum.ether.render.vulkan.internal.vkc.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU
import dev.instrum.ether.render.vulkan.internal.vkc.VK_QUEUE_GRAPHICS_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_APPLICATION_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
import dev.instrum.ether.render.vulkan.internal.vkc.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES
import dev.instrum.ether.render.vulkan.internal.vkc.VkApplicationInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkFormatProperties
import dev.instrum.ether.render.vulkan.internal.vkc.VkInstance
import dev.instrum.ether.render.vulkan.internal.vkc.VkInstanceCreateInfo
import dev.instrum.ether.render.vulkan.internal.vkc.VkInstanceVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDevice
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceFeatures2
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceVar
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceMemoryProperties
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceProperties
import dev.instrum.ether.render.vulkan.internal.vkc.VkPhysicalDeviceVulkan13Features
import dev.instrum.ether.render.vulkan.internal.vkc.VkQueueFamilyProperties
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.get
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/**
 * A short lived Vulkan instance used to inspect the available physical devices.
 *
 * The probe owns the dynamically loaded library and the Vulkan instance. A device created from a
 * probe takes over both; an unused probe releases them.
 */
internal class VulkanProbe(
    val library: VulkanLibrary,
    val instanceApi: VulkanInstanceApi,
    val instance: VkInstance,
    val physicalDevice: VkPhysicalDevice,
    val summary: VulkanPhysicalDeviceSummary,
    val capabilities: GraphicsCapabilities,
) {
    /** Releases the instance and the dynamically loaded library. */
    fun destroy() {
        instanceApi.destroyInstance.invoke(instance, null)
        library.close()
    }

    companion object {
        /**
         * Opens the loader, creates an instance and selects the best usable physical device.
         *
         * @param preferredDeviceName when set, only a device with this exact name is considered.
         * @throws VulkanException if no usable device exists.
         */
        fun create(preferredDeviceName: String? = null): VulkanProbe {
            val library = VulkanLibrary.open()
            try {
                val globalApi = VulkanGlobalApi(library)
                val loaderVersion = globalApi.enumerateInstanceVersion?.let { version ->
                    memScoped {
                        val value = zeroed<UIntVar>()
                        version.invoke(value.ptr)
                        value.value
                    }
                } ?: VK_API_VERSION_1_1

                if (loaderVersion < VK_API_VERSION_1_3) {
                    throw VulkanException(
                        "The Vulkan loader reports version ${formatVersion(loaderVersion)}; " +
                            "Ether requires at least 1.3 for dynamic rendering",
                    )
                }

                val instance = createInstance(globalApi, VK_API_VERSION_1_3)
                val instanceApi = VulkanInstanceApi(library, instance)
                try {
                    val candidates = enumerateCandidates(instanceApi, instance)
                    val selected = selectCandidate(instanceApi, candidates, preferredDeviceName)
                        ?: throw VulkanException(
                            if (preferredDeviceName == null) {
                                "No Vulkan physical device with a graphics queue and Vulkan 1.3 " +
                                    "dynamic rendering support was found"
                            } else {
                                "No Vulkan physical device named '$preferredDeviceName' with a " +
                                    "graphics queue and Vulkan 1.3 dynamic rendering support was found"
                            },
                        )
                    val summary = describe(instanceApi, selected)
                    val formats = querySupportedFormats(instanceApi, selected)
                    if (formats.isEmpty()) {
                        throw VulkanException(
                            "Vulkan device '${summary.name}' supports none of the Ether texture formats",
                        )
                    }
                    return VulkanProbe(
                        library = library,
                        instanceApi = instanceApi,
                        instance = instance,
                        physicalDevice = selected,
                        summary = summary,
                        capabilities = buildCapabilities(summary, formats),
                    )
                } catch (error: Throwable) {
                    instanceApi.destroyInstance.invoke(instance, null)
                    throw error
                }
            } catch (error: Throwable) {
                library.close()
                throw error
            }
        }

        private fun createInstance(globalApi: VulkanGlobalApi, apiVersion: UInt): VkInstance =
            memScoped {
                val applicationInfo = zeroed<VkApplicationInfo>()
                applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO
                applicationInfo.pApplicationName = nativeString("Ether")
                applicationInfo.applicationVersion = 1u
                applicationInfo.pEngineName = nativeString("Ether")
                applicationInfo.engineVersion = 1u
                applicationInfo.apiVersion = apiVersion

                val createInfo = zeroed<VkInstanceCreateInfo>()
                createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
                createInfo.pApplicationInfo = applicationInfo.ptr

                val instanceVar = zeroed<VkInstanceVar>()
                checkVkResult(
                    globalApi.createInstance.invoke(createInfo.ptr, null, instanceVar.ptr),
                    "vkCreateInstance",
                )
                instanceVar.value
                    ?: throw VulkanException("vkCreateInstance returned a null instance handle")
            }

        private fun enumerateCandidates(
            instanceApi: VulkanInstanceApi,
            instance: VkInstance,
        ): List<VkPhysicalDevice> = memScoped {
            val count = zeroed<UIntVar>()
            checkVkResult(
                instanceApi.enumeratePhysicalDevices.invoke(instance, count.ptr, null),
                "vkEnumeratePhysicalDevices",
            )
            if (count.value == 0u) return@memScoped emptyList()
            val devices = zeroedArray<VkPhysicalDeviceVar>(count.value.toInt())
            checkVkResult(
                instanceApi.enumeratePhysicalDevices.invoke(instance, count.ptr, devices),
                "vkEnumeratePhysicalDevices",
            )
            List(count.value.toInt()) { index -> devices[index]!! }
        }

        private fun selectCandidate(
            instanceApi: VulkanInstanceApi,
            candidates: List<VkPhysicalDevice>,
            preferredDeviceName: String?,
        ): VkPhysicalDevice? {
            if (candidates.isEmpty()) return null
            if (preferredDeviceName != null) {
                return candidates.firstOrNull { device ->
                    deviceName(instanceApi, device) == preferredDeviceName &&
                        isUsable(instanceApi, device)
                }
            }
            return candidates
                .filter { isUsable(instanceApi, it) }
                .maxByOrNull { device -> deviceScore(instanceApi, device) }
        }

        private fun isUsable(instanceApi: VulkanInstanceApi, device: VkPhysicalDevice): Boolean {
            val properties = readProperties(instanceApi, device)
            if (properties.apiVersion < VK_API_VERSION_1_3) return false
            if (findGraphicsQueueFamily(instanceApi, device) == null) return false
            return queryFeatures(instanceApi, device).dynamicRendering
        }

        private fun deviceScore(instanceApi: VulkanInstanceApi, device: VkPhysicalDevice): Int {
            val properties = readProperties(instanceApi, device)
            val typeScore = when {
                properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 3
                properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 2
                properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 1
                properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU -> 0
                else -> 0
            }
            return typeScore * 1_000_000
        }

        private fun deviceName(
            instanceApi: VulkanInstanceApi,
            device: VkPhysicalDevice,
        ): String = readProperties(instanceApi, device).deviceName

        /**
         * Reads a device's relevant property values before the probe scope ends.
         *
         * The backing problem: the properties structure lives in the arena of this function's
         * `memScoped` block, so only copied values survive past the scope.
         */
        private fun readProperties(
            instanceApi: VulkanInstanceApi,
            device: VkPhysicalDevice,
        ): PhysicalDeviceSnapshot = memScoped {
            val value = zeroed<VkPhysicalDeviceProperties>()
            instanceApi.getPhysicalDeviceProperties.invoke(device, value.ptr)
            PhysicalDeviceSnapshot(
                apiVersion = value.apiVersion,
                deviceType = value.deviceType,
                deviceName = value.deviceName.toKString(),
            )
        }

        private fun findGraphicsQueueFamily(
            instanceApi: VulkanInstanceApi,
            device: VkPhysicalDevice,
        ): UInt? = memScoped {
            val count = zeroed<UIntVar>()
            instanceApi.getPhysicalDeviceQueueFamilyProperties.invoke(device, count.ptr, null)
            if (count.value == 0u) return@memScoped null
            val families = zeroedArray<VkQueueFamilyProperties>(count.value.toInt())
            instanceApi.getPhysicalDeviceQueueFamilyProperties.invoke(device, count.ptr, families)
            for (index in 0 until count.value.toInt()) {
                val flags = families[index].queueFlags
                if (flags and VK_QUEUE_GRAPHICS_BIT != 0u && families[index].queueCount > 0u) {
                    return@memScoped index.toUInt()
                }
            }
            null
        }

        private fun describe(
            instanceApi: VulkanInstanceApi,
            device: VkPhysicalDevice,
        ): VulkanPhysicalDeviceSummary = memScoped {
            val properties = zeroed<VkPhysicalDeviceProperties>()
            instanceApi.getPhysicalDeviceProperties.invoke(device, properties.ptr)

            val queueFamilyIndex = findGraphicsQueueFamily(instanceApi, device)
                ?: throw VulkanException("The Vulkan device has no graphics queue family")

            val features = queryFeatures(instanceApi, device)
            val memoryProperties = zeroed<VkPhysicalDeviceMemoryProperties>()
            instanceApi.getPhysicalDeviceMemoryProperties.invoke(device, memoryProperties.ptr)

            val queueCount = zeroed<UIntVar>()
            instanceApi.getPhysicalDeviceQueueFamilyProperties.invoke(device, queueCount.ptr, null)
            val queueFamilies = zeroedArray<VkQueueFamilyProperties>(queueCount.value.toInt())
            instanceApi.getPhysicalDeviceQueueFamilyProperties.invoke(device, queueCount.ptr, queueFamilies)
            val family = queueFamilies[queueFamilyIndex.toInt()]

            val memoryTypes = buildList {
                for (index in 0 until memoryProperties.memoryTypeCount.toInt()) {
                    val type = memoryProperties.memoryTypes[index]
                    add(VulkanMemoryType(type.propertyFlags, type.heapIndex))
                }
            }
            val memoryHeaps = buildList {
                for (index in 0 until memoryProperties.memoryHeapCount.toInt()) {
                    val heap = memoryProperties.memoryHeaps[index]
                    add(VulkanMemoryHeap(heap.size, heap.flags))
                }
            }

            val limits = properties.limits


            VulkanPhysicalDeviceSummary(
                name = properties.deviceName.toKString()
                    .ifEmpty { "Unknown Vulkan device" },
                apiVersion = properties.apiVersion,
                driverVersion = properties.driverVersion,
                vendorId = properties.vendorID,
                deviceType = properties.deviceType,
                queueFamilyIndex = queueFamilyIndex,
                queueCount = family.queueCount,
                timestampValidBits = family.timestampValidBits,
                supportsDynamicRendering = features.dynamicRendering,
                supportsSamplerAnisotropy = features.samplerAnisotropy,
                supportsDepthClamp = features.depthClamp,
                maxSamplerAnisotropy = limits.maxSamplerAnisotropy,
                maxImageDimension1D = limits.maxImageDimension1D,
                maxImageDimension2D = limits.maxImageDimension2D,
                maxImageDimension3D = limits.maxImageDimension3D,
                maxImageDimensionCube = limits.maxImageDimensionCube,
                maxImageArrayLayers = limits.maxImageArrayLayers,
                maxColorAttachments = limits.maxColorAttachments,
                maxVertexInputBindings = limits.maxVertexInputBindings,
                maxVertexInputAttributes = limits.maxVertexInputAttributes,
                maxVertexInputBindingStride = limits.maxVertexInputBindingStride,
                maxBoundDescriptorSets = limits.maxBoundDescriptorSets,
                maxPerStageDescriptorSamplers = limits.maxPerStageDescriptorSamplers,
                maxPerStageDescriptorUniformBuffers = limits.maxPerStageDescriptorUniformBuffers,
                maxPerStageDescriptorStorageBuffers = limits.maxPerStageDescriptorStorageBuffers,
                maxPerStageDescriptorSampledImages = limits.maxPerStageDescriptorSampledImages,
                maxUniformBufferRange = limits.maxUniformBufferRange,
                maxStorageBufferRange = limits.maxStorageBufferRange,
                maxPushConstantsSize = limits.maxPushConstantsSize,
                nonCoherentAtomSize = limits.nonCoherentAtomSize,
                maxComputeWorkGroupSize = List(3) { limits.maxComputeWorkGroupSize[it] },
                maxComputeWorkGroupInvocations = limits.maxComputeWorkGroupInvocations,
                framebufferColorSampleCounts = limits.framebufferColorSampleCounts,
                framebufferDepthSampleCounts = limits.framebufferDepthSampleCounts,
                framebufferNoAttachmentsSampleCounts = limits.framebufferNoAttachmentsSampleCounts,
                memoryTypes = memoryTypes,
                memoryHeaps = memoryHeaps,
            )
        }

        private fun queryFeatures(
            instanceApi: VulkanInstanceApi,
            device: VkPhysicalDevice,
        ): VulkanDeviceFeatures = memScoped {
            val features2 = zeroed<VkPhysicalDeviceFeatures2>()
            features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
            val vulkan13 = zeroed<VkPhysicalDeviceVulkan13Features>()
            vulkan13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES
            features2.pNext = vulkan13.ptr
            instanceApi.getPhysicalDeviceFeatures2.invoke(device, features2.ptr)
            VulkanDeviceFeatures(
                dynamicRendering = vulkan13.dynamicRendering != 0u,
                samplerAnisotropy = features2.features.samplerAnisotropy != 0u,
                depthClamp = features2.features.depthClamp != 0u,
            )
        }

        private fun querySupportedFormats(
            instanceApi: VulkanInstanceApi,
            device: VkPhysicalDevice,
        ): Set<TextureFormat> {
            val supported = LinkedHashSet<TextureFormat>()
            for (format in TextureFormat.entries) {
                // The format properties live in the arena of this block, so the feature bits
                // must be copied out before the arena ends.
                val features = memScoped {
                    val value = zeroed<VkFormatProperties>()
                    instanceApi.getPhysicalDeviceFormatProperties.invoke(
                        device,
                        format.toVkFormat(),
                        value.ptr,
                    )
                    value.optimalTilingFeatures
                }
                if (features == 0u) continue
                val attachmentSupported = if (format.isDepthStencil) {
                    features and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT != 0u
                } else {
                    features and VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT != 0u
                }
                if (!attachmentSupported) continue
                if (features and VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT == 0u) continue
                if (features and VK_FORMAT_FEATURE_TRANSFER_SRC_BIT == 0u) continue
                if (features and VK_FORMAT_FEATURE_TRANSFER_DST_BIT == 0u) continue
                supported += format
            }
            return supported
        }
    }
}

/**
 * The property values a kernel needs after the probe scope ended.
 */
private data class PhysicalDeviceSnapshot(
    val apiVersion: UInt,
    val deviceType: UInt,
    val deviceName: String,
)

/**
 * The optional device features the backend depends on.
 */
internal data class VulkanDeviceFeatures(
    val dynamicRendering: Boolean,
    val samplerAnisotropy: Boolean,
    val depthClamp: Boolean,
)

/**
 * Formats a Vulkan version number as `major.minor.patch`.
 */
internal fun formatVersion(version: UInt): String {
    val major = version shr 22
    val minor = (version shr 12) and 0x3FFu
    val patch = (version shr 10) and 0x3FFu
    return "$major.$minor.$patch"
}

/**
 * Builds the portable capabilities report from a physical device summary.
 */
internal fun buildCapabilities(
    summary: VulkanPhysicalDeviceSummary,
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
        maxTexture1DSize = summary.maxImageDimension1D.toInt(),
        maxTexture2DSize = summary.maxImageDimension2D.toInt(),
        maxTexture3DSize = summary.maxImageDimension3D.toInt(),
        maxTextureCubeSize = summary.maxImageDimensionCube.toInt(),
        maxTextureArrayLayers = summary.maxImageArrayLayers.toInt(),
        maxColorAttachments = summary.maxColorAttachments.toInt(),
        maxSampleCount = 1,
        maxVertexBuffers = summary.maxVertexInputBindings.toInt(),
        maxVertexAttributes = summary.maxVertexInputAttributes.toInt(),
        maxVertexBufferStride = summary.maxVertexInputBindingStride.toInt(),
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
        type.propertyFlags and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0u
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
private fun largestHeapSize(summary: VulkanPhysicalDeviceSummary): Long {
    val largest = summary.memoryHeaps.maxOfOrNull { it.size } ?: 0uL
    return if (largest > 0uL) largest.toLong() else 1L
}

/**
 * Pointer helper kept next to the probe because both use the same native helpers.
 */
internal typealias UIntPointer = CPointer<UIntVar>

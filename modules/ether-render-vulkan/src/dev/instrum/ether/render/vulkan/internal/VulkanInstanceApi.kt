@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import kotlinx.cinterop.invoke
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateDevice
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyDevice
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkDestroyInstance
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkEnumeratePhysicalDevices
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetDeviceProcAddr
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceFeatures
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceFeatures2
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceFormatProperties
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceMemoryProperties
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceProperties
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceProperties2
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetPhysicalDeviceQueueFamilyProperties
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkVoidFunction
import dev.instrum.ether.render.vulkan.internal.vkc.VkDevice
import dev.instrum.ether.render.vulkan.internal.vkc.VkInstance

/**
 * Vulkan entry points that are valid before a logical device exists.
 *
 * The implementation without an instance belongs to [VulkanGlobalApi]; every entry point here is
 * resolved through `vkGetInstanceProcAddr` bound to [instance] because the Vulkan loader only
 * publishes instance level functions once an instance exists.
 */
internal class VulkanInstanceApi(
    private val library: VulkanLibrary,
    /** The instance the entry points are bound to. */
    private val instance: VkInstance,
) {
    /** `vkDestroyInstance`. */
    val destroyInstance: PFN_vkDestroyInstance = required("vkDestroyInstance")

    /** `vkEnumeratePhysicalDevices`. */
    val enumeratePhysicalDevices: PFN_vkEnumeratePhysicalDevices =
        required("vkEnumeratePhysicalDevices")

    /** `vkGetPhysicalDeviceProperties`. */
    val getPhysicalDeviceProperties: PFN_vkGetPhysicalDeviceProperties =
        required("vkGetPhysicalDeviceProperties")

    /** `vkGetPhysicalDeviceProperties2`. */
    val getPhysicalDeviceProperties2: PFN_vkGetPhysicalDeviceProperties2 =
        required("vkGetPhysicalDeviceProperties2")

    /** `vkGetPhysicalDeviceFormatProperties`. */
    val getPhysicalDeviceFormatProperties: PFN_vkGetPhysicalDeviceFormatProperties =
        required("vkGetPhysicalDeviceFormatProperties")

    /** `vkGetPhysicalDeviceFeatures`. */
    val getPhysicalDeviceFeatures: PFN_vkGetPhysicalDeviceFeatures =
        required("vkGetPhysicalDeviceFeatures")

    /** `vkGetPhysicalDeviceFeatures2`. */
    val getPhysicalDeviceFeatures2: PFN_vkGetPhysicalDeviceFeatures2 =
        required("vkGetPhysicalDeviceFeatures2")

    /** `vkGetPhysicalDeviceMemoryProperties`. */
    val getPhysicalDeviceMemoryProperties: PFN_vkGetPhysicalDeviceMemoryProperties =
        required("vkGetPhysicalDeviceMemoryProperties")

    /** `vkGetPhysicalDeviceQueueFamilyProperties`. */
    val getPhysicalDeviceQueueFamilyProperties: PFN_vkGetPhysicalDeviceQueueFamilyProperties =
        required("vkGetPhysicalDeviceQueueFamilyProperties")

    /** `vkCreateDevice`. */
    val createDevice: PFN_vkCreateDevice = required("vkCreateDevice")

    /** `vkDestroyDevice`. */
    val destroyDevice: PFN_vkDestroyDevice = required("vkDestroyDevice")

    /** `vkGetDeviceProcAddr`. */
    val getDeviceProcAddr: PFN_vkGetDeviceProcAddr = required("vkGetDeviceProcAddr")

    /**
     * Looks up a device-level entry point.
     *
     * Device level entry points are resolved with `vkGetDeviceProcAddr` so the loader binds them
     * to the logical device dispatch table.
     *
     * @param device the logical device used for the lookup.
     * @param name the Vulkan function name.
     */
    fun lookupDevice(device: VkDevice, name: String): PFN_vkVoidFunction? =
        withNativeString(name) { getDeviceProcAddr.invoke(device, it) }

    private inline fun <reified T> required(name: String): T =
        loadVulkanFunction<T>(library.lookup(instance, name), name)

}

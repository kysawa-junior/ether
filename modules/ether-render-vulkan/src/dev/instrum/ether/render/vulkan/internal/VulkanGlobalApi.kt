@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkCreateInstance
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkEnumerateInstanceExtensionProperties
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkEnumerateInstanceVersion

/**
 * Vulkan entry points that are valid before any instance exists.
 *
 * The Vulkan loader exports these three functions through `vkGetInstanceProcAddr` even when the
 * query passes no instance; every instance level entry point requires an instance, so instance
 * level lookups live in [VulkanInstanceApi].
 */
internal class VulkanGlobalApi(
    private val library: VulkanLibrary,
) {
    /** `vkEnumerateInstanceVersion`, available from Vulkan 1.1 onwards. */
    val enumerateInstanceVersion: PFN_vkEnumerateInstanceVersion? =
        optional("vkEnumerateInstanceVersion")

    /** `vkEnumerateInstanceExtensionProperties`. */
    val enumerateInstanceExtensionProperties: PFN_vkEnumerateInstanceExtensionProperties =
        required("vkEnumerateInstanceExtensionProperties")

    /** `vkCreateInstance`. */
    val createInstance: PFN_vkCreateInstance = required("vkCreateInstance")

    private inline fun <reified T> required(name: String): T =
        loadVulkanFunction<T>(library.lookup(null, name), name)

    private inline fun <reified T> optional(name: String): T? {
        val raw = library.lookup(null, name) ?: return null
        return loadVulkanFunction<T>(raw, name)
    }
}

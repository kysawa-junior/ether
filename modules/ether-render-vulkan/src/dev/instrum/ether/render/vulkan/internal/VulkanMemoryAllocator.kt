@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
import dev.instrum.ether.render.vulkan.internal.vkc.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT

/**
 * Selects compatible memory types for a Vulkan allocation.
 *
 * The driver reports a fixed list of memory types; every allocation must use a type whose bit is
 * set in the resource's `memoryTypeBits` and whose property flags contain the requested ones.
 */
internal class VulkanMemoryAllocator(
    private val memoryTypes: List<VulkanMemoryType>,
) {
    /**
     * Finds the first compatible memory type that has every flag in [requiredFlags].
     *
     * @param typeBits the bits of the memory types allowed by the resource.
     * @param requiredFlags the properties the allocation needs.
     * @return the memory type index, or `-1` when no type satisfies the request.
     */
    fun findType(typeBits: UInt, requiredFlags: UInt): Int {
        for (index in memoryTypes.indices) {
            if (index >= 32) break
            if (typeBits and (1u shl index) == 0u) continue
            val flags = memoryTypes[index].propertyFlags
            if (flags and requiredFlags == requiredFlags) return index
        }
        return -1
    }

    /** Whether the memory type at [index] can be mapped by the host. */
    fun isHostVisible(index: Int): Boolean =
        propertyFlags(index) and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0u

    /** Whether the memory type at [index] is host coherent. */
    fun isCoherent(index: Int): Boolean =
        propertyFlags(index) and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT != 0u

    /** Whether the memory type at [index] is device local. */
    fun isDeviceLocal(index: Int): Boolean =
        propertyFlags(index) and VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT != 0u

    private fun propertyFlags(index: Int): UInt =
        memoryTypes.getOrNull(index)?.propertyFlags ?: 0u
}

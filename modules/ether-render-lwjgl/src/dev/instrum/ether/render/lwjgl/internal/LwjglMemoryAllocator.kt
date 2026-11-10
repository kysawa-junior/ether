package dev.instrum.ether.render.lwjgl.internal

/**
 * Vulkan memory property flags used by this backend, mirroring the driver values.
 */
internal const val VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT: Int = 0x00000001
internal const val VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT: Int = 0x00000002
internal const val VK_MEMORY_PROPERTY_HOST_COHERENT_BIT: Int = 0x00000004

/**
 * Vulkan physical device types, mirroring the driver values.
 */
internal const val VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: Int = 1
internal const val VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: Int = 2
internal const val VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: Int = 3
internal const val VK_PHYSICAL_DEVICE_TYPE_CPU: Int = 4

/**
 * Selects compatible memory types for a Vulkan allocation.
 *
 * The driver reports a fixed list of memory types; every allocation must use a type whose bit is
 * set in the resource's `memoryTypeBits` and whose property flags contain the requested ones.
 */
internal class LwjglMemoryAllocator(
    private val memoryTypes: List<LwjglMemoryType>,
) {
    /**
     * Finds the first compatible memory type that has every flag in [requiredFlags].
     *
     * @param typeBits the bits of the memory types allowed by the resource.
     * @param requiredFlags the properties the allocation needs.
     * @return the memory type index, or `-1` when no type satisfies the request.
     */
    fun findType(typeBits: Int, requiredFlags: Int): Int {
        for (index in memoryTypes.indices) {
            if (index >= 32) break
            if (typeBits and (1 shl index) == 0) continue
            val flags = memoryTypes[index].propertyFlags
            if (flags and requiredFlags == requiredFlags) return index
        }
        return -1
    }

    /** Whether the memory type at [index] can be mapped by the host. */
    fun isHostVisible(index: Int): Boolean =
        propertyFlags(index) and VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT != 0

    /** Whether the memory type at [index] is host coherent. */
    fun isCoherent(index: Int): Boolean =
        propertyFlags(index) and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT != 0

    /** Whether the memory type at [index] is device local. */
    fun isDeviceLocal(index: Int): Boolean =
        propertyFlags(index) and VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT != 0

    private fun propertyFlags(index: Int): Int =
        memoryTypes.getOrNull(index)?.propertyFlags ?: 0
}

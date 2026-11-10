@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.vulkan.VulkanException
import dev.instrum.ether.render.vulkan.internal.vkc.VK_SUCCESS

/**
 * Checks that a Vulkan call returned [VK_SUCCESS].
 *
 * @param result the raw `VkResult` value.
 * @param operation the operation name used in the failure message.
 */
internal fun checkVkResult(result: Int, operation: String) {
    if (result != VK_SUCCESS) {
        throw VulkanException("$operation failed with VkResult $result (${vkResultName(result)})")
    }
}

/**
 * Checks that a Vulkan call returned one of the accepted success codes.
 *
 * @param result the raw `VkResult` value.
 * @param operation the operation name used in the failure message.
 * @param accepted the accepted success codes.
 */
internal fun checkVkResult(result: Int, operation: String, accepted: IntArray) {
    if (result !in accepted) {
        throw VulkanException("$operation failed with VkResult $result (${vkResultName(result)})")
    }
}

/** Returns a human readable name for the common `VkResult` values. */
internal fun vkResultName(result: Int): String = when (result) {
    0 -> "VK_SUCCESS"
    1 -> "VK_NOT_READY"
    -1 -> "VK_TIMEOUT"
    2 -> "VK_EVENT_SET"
    3 -> "VK_EVENT_RESET"
    4 -> "VK_INCOMPLETE"
    -2 -> "VK_ERROR_OUT_OF_HOST_MEMORY"
    -3 -> "VK_ERROR_OUT_OF_DEVICE_MEMORY"
    -4 -> "VK_ERROR_INITIALIZATION_FAILED"
    -5 -> "VK_ERROR_DEVICE_LOST"
    -6 -> "VK_ERROR_MEMORY_MAP_FAILED"
    -7 -> "VK_ERROR_LAYER_NOT_PRESENT"
    -8 -> "VK_ERROR_EXTENSION_NOT_PRESENT"
    -9 -> "VK_ERROR_FEATURE_NOT_PRESENT"
    -10 -> "VK_ERROR_INCOMPATIBLE_DRIVER"
    -11 -> "VK_ERROR_TOO_MANY_OBJECTS"
    -12 -> "VK_ERROR_FORMAT_NOT_SUPPORTED"
    -13 -> "VK_ERROR_FRAGMENTED_POOL"
    -14 -> "VK_ERROR_UNKNOWN"
    -15 -> "VK_ERROR_OUT_OF_DATE_KHR"
    -1000000004 -> "VK_ERROR_VALIDATION_FAILED_EXT"
    else -> "VkResult($result)"
}

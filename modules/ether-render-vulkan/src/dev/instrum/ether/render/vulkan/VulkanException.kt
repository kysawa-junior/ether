package dev.instrum.ether.render.vulkan

/**
 * Raised when a Vulkan API call fails or the backend cannot satisfy a request.
 *
 * The message always names the failing operation so a failure can be traced without a debugger.
 */
public class VulkanException internal constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

package dev.instrum.ether.render.lwjgl

/**
 * Signals that the Vulkan JVM backend cannot proceed on the current machine or with the requested
 * configuration.
 *
 * Availability and capability paths throw this exception when the Vulkan loader is missing, no
 * physical device meets the Vulkan 1.3 dynamic rendering requirement, or a driver call fails. The
 * portable core treats any [RuntimeException] from backend creation as an availability failure, so
 * callers may catch the base class rather than this type.
 */
public class LwjglVulkanException : RuntimeException {

    /**
     * Creates an exception carrying a human readable cause description.
     *
     * @param message what failed and why, in backend terms.
     */
    public constructor(message: String) : super(message)

    /**
     * Creates an exception that wraps a driver level failure.
     *
     * @param message what failed and why, in backend terms.
     * @param cause the underlying driver error, if any.
     */
    public constructor(message: String, cause: Throwable) : super(message, cause)
}
